package com.example.minecraftlodmod.generation;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
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
    private final Semaphore permisos;
    private int limiteActual;
    private final Semaphore permisosDeCola;

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
        this.pool = new ForkJoinPool(hilosMaximos);
        this.permisos = new Semaphore(hilosMaximos);
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

    private <T> Future<T> enviarConCupoTomado(Callable<T> tarea) {
        return pool.submit(() -> {
            try {
                permisos.acquire();
                try {
                    return tarea.call();
                } finally {
                    permisos.release();
                }
            } finally {
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
     * IMPORTANTE: si el nuevo límite es menor al actual, este método
     * BLOQUEA hasta poder retener los permisos de sobra — se espera que
     * quien llama lo haga desde el hilo de control del auto-ajuste (no
     * desde el hilo principal del juego), donde una espera corta es
     * aceptable.
     */
    public void ajustarLimiteConcurrencia(int nuevoLimite) {
        if (nuevoLimite < 1) {
            throw new IllegalArgumentException("nuevoLimite debe ser al menos 1, fue: " + nuevoLimite);
        }
        int delta = nuevoLimite - limiteActual;
        if (delta > 0) {
            permisos.release(delta);
        } else if (delta < 0) {
            permisos.acquireUninterruptibly(-delta);
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

    public void apagar() {
        pool.shutdown();
    }
}
