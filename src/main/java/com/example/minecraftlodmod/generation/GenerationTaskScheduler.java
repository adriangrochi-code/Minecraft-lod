package com.example.minecraftlodmod.generation;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * Reparte las tareas de generación (extracción, reducción jerárquica,
 * greedy meshing por sección) entre todos los hilos del pool usando
 * work-stealing en vez de una cola única compartida.
 *
 * Por qué importa: las tareas de generación tienen costo muy dispar — una
 * sección vacía o homogénea se descarta casi al instante (ver el extractor,
 * sección "generation" del documento de arquitectura), mientras que una
 * sección con terreno mixto complejo puede tardar bastante más. Con una
 * cola FIFO compartida y N hilos consumiéndola, es fácil que un hilo quede
 * "colgado" en una tarea pesada mientras otro termina rápido y se queda sin
 * trabajo — desperdicia paralelismo y genera stutter irregular. Con
 * work-stealing (lo que da gratis {@link ForkJoinPool}), cada hilo tiene su
 * propia cola y roba trabajo de otras colas cuando la propia se vacía — el
 * reparto se equilibra solo, sin coordinación manual.
 *
 * Segunda pieza: un límite de concurrencia AJUSTABLE EN CALIENTE, separado
 * del tamaño del pool. El pool en sí se crea una vez con el máximo de hilos
 * del preset (o del hardware), pero cuántas tareas pueden estar EJECUTANDO
 * a la vez se controla con un semáforo cuyo límite el
 * {@code PerformanceAutoTuner} (sección 21 del documento de arquitectura)
 * puede subir o bajar en tiempo real, sin recrear el pool — evita el costo
 * de destruir/crear threads cada vez que el auto-ajuste decide throttlear.
 *
 * Nota sobre {@link #ajustarLimiteConcurrencia}: reducir el límite es
 * "eventual", no instantáneo — las tareas ya en ejecución no se interrumpen,
 * simplemente se retienen permisos para que menos tareas nuevas arranquen
 * hasta que las que ya corren liberen los suyos. Es el comportamiento
 * correcto para este caso de uso (throttling suave, no cancelación).
 *
 * Tercera pieza: BACKPRESSURE en la cola. Sin un tope, si el jugador se
 * mueve más rápido de lo que el pipeline puede generar, las tareas
 * encoladas (cada una reteniendo su contexto de generación en RAM) podrían
 * acumularse sin límite — memoria sin techo, justo lo que rompe estabilidad
 * en sesiones largas. Con {@code maxTareasEnCola}, una vez lleno el cupo,
 * {@link #enviar} bloquea a quien pide generación en vez de encolar sin fin
 * — aplica presión hacia atrás sobre el selector de LOD, que naturalmente
 * va a pedir menos si no puede encolar más.
 */
public final class GenerationTaskScheduler {

    private final ForkJoinPool pool;
    static final int PRIORIDAD_HILOS = Thread.NORM_PRIORITY - 2;
    private final SemaforoAjustable permisos;
    private volatile int limiteActual;
    private final Semaphore permisosDeCola;
    /** Apagado: el trabajo de fondo cede en su próximo punto de corte ({@link #hayPrioritariasEsperando}). */
    private volatile boolean apagado;

    /** Semáforo que puede quedar en negativo al bajar el límite (sin esperar a que se liberen permisos). */
    private static final class SemaforoAjustable extends Semaphore {
        SemaforoAjustable(int permisos) {
            super(permisos);
        }

        void reducir(int cantidad) {
            reducePermits(cantidad);
        }
    }

    public GenerationTaskScheduler(int hilosMaximos) {
        this(hilosMaximos, Integer.MAX_VALUE);
    }

    /**
     * @param hilosMaximos       tamaño del pool de work-stealing
     * @param maxTareasEnCola    tope de tareas pendientes o en ejecución a la
     *                           vez (backpressure) — evita que un jugador
     *                           moviéndose más rápido de lo que se puede
     *                           generar acumule memoria sin límite (cada
     *                           tarea encolada retiene su contexto de
     *                           generación en RAM hasta ejecutarse). Cuando
     *                           se alcanza el tope, {@link #enviar} BLOQUEA
     *                           al llamador en vez de encolar sin fin —
     *                           aplica presión hacia atrás sobre quien pide
     *                           generación (típicamente el selector de LOD
     *                           al recorrer el octree), en vez de dejar que
     *                           la cola crezca indefinidamente.
     */
    public GenerationTaskScheduler(int hilosMaximos, int maxTareasEnCola) {
        if (hilosMaximos < 1) {
            throw new IllegalArgumentException("hilosMaximos debe ser al menos 1, fue: " + hilosMaximos);
        }
        if (maxTareasEnCola < 1) {
            throw new IllegalArgumentException("maxTareasEnCola debe ser al menos 1, fue: " + maxTareasEnCola);
        }
        this.pool = new ForkJoinPool(hilosMaximos, pool -> {
            ForkJoinWorkerThread hilo = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
            hilo.setName("LOD-Generacion-" + hilo.getPoolIndex());
            // Por debajo del hilo de render y del servidor: con el procesador lleno (generación
            // aproximada usa todos los hilos que tiene), el juego va primero. En Windows la
            // prioridad de Java llega al sistema operativo.
            hilo.setPriority(PRIORIDAD_HILOS);
            return hilo;
        }, null, false);
        this.permisos = new SemaforoAjustable(hilosMaximos);
        this.limiteActual = hilosMaximos;
        this.permisosDeCola = new Semaphore(maxTareasEnCola);
    }

    /**
     * Envía una tarea de generación al pool. El work-stealing de ForkJoinPool
     * la reparte automáticamente. Si ya hay {@code maxTareasEnCola} tareas
     * pendientes o en ejecución, este método BLOQUEA hasta que se libere
     * espacio — es la presión hacia atrás (backpressure) que protege la RAM.
     */
    public <T> Future<T> enviar(Callable<T> tarea) {
        try {
            permisosDeCola.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrumpido esperando espacio en la cola de generación", e);
        }

        return enviarConCupoTomado(tarea);
    }

    /**
     * Como {@link #enviar}, pero sin bloquear: si la cola está llena devuelve
     * null y la tarea no se encola. Para llamadores que no pueden esperar —
     * el hilo del servidor al cargar chunks — y prefieren descartar trabajo
     * (se vuelve a pedir en la próxima carga) antes que frenar el juego.
     */
    public <T> Future<T> intentarEnviar(Callable<T> tarea) {
        if (!permisosDeCola.tryAcquire()) {
            return null;
        }
        return enviarConCupoTomado(tarea);
    }

    /** Lugares extra de la cola para {@link #intentarEnviarReservado}. */
    static final int RESERVADOS = 3;
    private final Semaphore reservados = new Semaphore(RESERVADOS);

    /**
     * Como {@link #intentarEnviar}, pero con la cola llena todavía entra en
     * uno de los {@link #RESERVADOS} lugares extra. Para trabajo poco
     * frecuente que no puede quedar sin turno detrás de una ráfaga (el
     * horizonte aproximado por región detrás de la pregeneración).
     */
    public <T> Future<T> intentarEnviarReservado(Callable<T> tarea) {
        if (permisosDeCola.tryAcquire()) {
            return enviarConCupoTomado(tarea);
        }
        if (!reservados.tryAcquire()) {
            return null;
        }
        prioritariasEsperando.incrementAndGet();
        return pool.submit(() -> {
            boolean contada = true;
            try {
                permisos.acquire();
                prioritariasEsperando.decrementAndGet();
                contada = false;
                try {
                    return tarea.call();
                } finally {
                    permisos.release();
                }
            } finally {
                if (contada) {
                    prioritariasEsperando.decrementAndGet();
                }
                reservados.release();
            }
        });
    }

    /**
     * Tareas prioritarias (todas menos las de fondo) encoladas o esperando
     * un permiso: mientras haya alguna, el trabajo de fondo no arranca y el
     * que está en curso cede en su próximo punto de corte.
     */
    private final java.util.concurrent.atomic.AtomicInteger prioritariasEsperando =
            new java.util.concurrent.atomic.AtomicInteger();
    /** Tareas de fondo enviadas y todavía no terminadas. */
    private final java.util.concurrent.atomic.AtomicInteger deFondo = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Trabajo de fondo (el horizonte aproximado): puede tardar minutos por
     * tarea en un procesador lento y no debe dejar sin turno a la extracción
     * de chunks reales cercanos, que es lo que el jugador ve primero. Solo
     * entra si no hay tareas prioritarias esperando y si deja libre al menos
     * un permiso del límite actual (con límite 1, entra de a una y solo con
     * el pool ocioso). Quien la ejecuta debería consultar
     * {@link #hayPrioritariasEsperando()} entre partes y ceder.
     *
     * @param conReserva con la cola llena, usar uno de los {@link #RESERVADOS}
     * @return null si no entró (se reintenta más tarde)
     */
    public <T> Future<T> intentarEnviarDeFondo(Callable<T> tarea, boolean conReserva) {
        if (prioritariasEsperando.get() > 0 || deFondo.get() >= maximoDeFondo(limiteActual)) {
            return null;
        }
        Semaphore cupo;
        if (permisosDeCola.tryAcquire()) {
            cupo = permisosDeCola;
        } else if (conReserva && reservados.tryAcquire()) {
            cupo = reservados;
        } else {
            return null;
        }
        deFondo.incrementAndGet();
        return pool.submit(() -> {
            try {
                permisos.acquire();
                try {
                    return tarea.call();
                } finally {
                    permisos.release();
                }
            } finally {
                deFondo.decrementAndGet();
                cupo.release();
            }
        });
    }

    /** Tareas de fondo a la vez con ese límite de concurrencia: todas menos una, y al menos una. */
    static int maximoDeFondo(int limite) {
        return Math.max(1, limite - 1);
    }

    /** ¿Hay extracción u otro trabajo prioritario esperando turno (o se apagó)? El trabajo de fondo cede si sí. */
    public boolean hayPrioritariasEsperando() {
        return apagado || prioritariasEsperando.get() > 0;
    }

    private <T> Future<T> enviarConCupoTomado(Callable<T> tarea) {
        prioritariasEsperando.incrementAndGet();
        return pool.submit(() -> {
            boolean contada = true;
            try {
                permisos.acquire();
                prioritariasEsperando.decrementAndGet();
                contada = false;
                try {
                    return tarea.call();
                } finally {
                    permisos.release();
                }
            } finally {
                if (contada) {
                    prioritariasEsperando.decrementAndGet(); // interrumpida antes de arrancar
                }
                permisosDeCola.release();
            }
        });
    }

    /**
     * Ajusta cuántas tareas pueden ejecutar en simultáneo, sin recrear el
     * pool. Pensado para que {@code PerformanceAutoTuner} lo llame
     * periódicamente junto con sus otras dos perillas (umbral de detalle y
     * radio activo — sección 21).
     *
     * No bloquea: al bajar, los permisos de sobra se descuentan aunque estén
     * en uso (el semáforo puede quedar en negativo) y las tareas en curso
     * los devuelven al terminar. Antes se esperaba a tomarlos todos juntos, y
     * con tareas entrando sin parar (que los toman de a uno) esa espera podía
     * no terminar nunca, trabando el hilo del auto-ajuste.
     */
    public synchronized void ajustarLimiteConcurrencia(int nuevoLimite) {
        if (nuevoLimite < 1) {
            throw new IllegalArgumentException("nuevoLimite debe ser al menos 1, fue: " + nuevoLimite);
        }
        int delta = nuevoLimite - limiteActual;
        if (delta > 0) {
            permisos.release(delta);
        } else if (delta < 0) {
            permisos.reducir(-delta);
        }
        limiteActual = nuevoLimite;
    }

    public int limiteConcurrenciaActual() {
        return limiteActual;
    }

    public int hilosEnElPool() {
        return pool.getParallelism();
    }

    /** Bloquea hasta que la tarea termine y devuelve su resultado — conveniencia para tests y uso simple. */
    public <T> T enviarYEsperar(Callable<T> tarea) throws ExecutionException, InterruptedException {
        return enviar(tarea).get();
    }

    /** Sin esperar: las tareas encoladas igual se ejecutan; las de fondo ceden. */
    public void apagar() {
        apagado = true;
        pool.shutdown();
    }

    /**
     * Apaga y espera a que terminen las tareas (como mucho {@code maximoMs}): quien
     * cierra el store después no pierde lo que todavía se estaba generando.
     *
     * @return true si terminaron todas
     */
    public boolean apagarYEsperar(long maximoMs) {
        apagar();
        try {
            return pool.awaitTermination(maximoMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
