package com.example.minecraftlodmod.core;

/**
 * Auto-ajuste según el cuello de botella (sección 28 del documento de
 * arquitectura): con el tiempo de cuadro y el tiempo de GPU del cuadro decide
 * si el límite es el CPU o la GPU, y mueve la perilla que alivia a ese lado,
 * pasando trabajo al otro cuando se puede.
 *
 * Perillas (una por llamada, así los cambios son graduales):
 * - {@link #distanciaUnBuffer}: desde qué distancia las caras de una celda van
 *   en un solo buffer. Más cerca = más buffers por dirección = más llamadas de
 *   dibujo (CPU) y menos triángulos de espaldas (GPU). Invisible.
 * - {@link #factorOclusion}: radio de los oclusores del relieve (CPU en cada
 *   replanificación) contra celdas tapadas que igual se dibujan (GPU). Invisible.
 * - {@link #reduccionEscala}: puntos de porcentaje que se le restan a la
 *   resolución del escalado (solo si hay escalado). Alivia mucho la GPU.
 * - {@link #limiteConcurrencia}: tareas de generación simultáneas. Libera
 *   núcleos para el hilo de render; es lo que más ayuda contra los tirones.
 * - {@link #umbralPx} y {@link #radioChunks}: detalle y alcance del LOD, al
 *   final porque son las que más se ven.
 *
 * Al sobrar margen se recupera primero lo visible (radio, detalle, escala) y
 * la generación; las perillas invisibles de reparto quedan donde están.
 * Lógica pura: sin Minecraft, con tests.
 */
public final class BalanceadorCpuGpu {

    /** Qué limita el cuadro. DESCONOCIDO: sin medición de GPU (se usa el orden genérico). */
    public enum Limite { CPU, GPU, DESCONOCIDO }

    /** GPU ocupada al menos esta fracción del cuadro = el cuadro espera a la GPU. */
    static final double FRACCION_GPU = 0.85;
    /** Por debajo de esta fracción del objetivo sobra margen para recuperar calidad. */
    static final double MARGEN = 0.8;
    /** Ciclos seguidos con margen antes de recuperar: evita subir y bajar en cada ciclo. */
    static final int CICLOS_PARA_RECUPERAR = 2;
    /** El 1% peor por encima de esto × objetivo cuenta como tirones. */
    static final double FACTOR_TIRON = 2.5;

    /** Umbral más grueso al que lleva el ajuste (ver el constructor). */
    static final double UMBRAL_TECHO = 6;
    public static final double UN_BUFFER_INICIAL = 768, UN_BUFFER_MIN = 256, UN_BUFFER_MAX = 3072;
    static final double FACTOR_UN_BUFFER = 1.5;
    public static final double OCLUSION_MIN = 0.5;
    static final double PASO_OCLUSION = 0.125;
    public static final int REDUCCION_ESCALA_MAX = 15;
    static final int PASO_REDUCCION_ESCALA = 5;

    private final double umbralMin, umbralMax, pasoUmbral;
    private final int radioMin, radioMax, pasoRadio;
    private final int concurrenciaMin, concurrenciaMax;

    private double umbralPx;
    private int radioChunks;
    private int limiteConcurrencia;
    private double distanciaUnBuffer = UN_BUFFER_INICIAL;
    private double factorOclusion = 1;
    private int reduccionEscala;

    private Limite ultimoLimite = Limite.DESCONOCIDO;
    private int ciclosConMargen;
    private boolean tirones;

    /**
     * @param umbralBase detalle del preset (el mejor que se permite)
     * @param radioBase  radio del preset en chunks (el techo)
     * @param hilos      hilos de generación del preset (techo de concurrencia)
     */
    public BalanceadorCpuGpu(double umbralBase, int radioBase, int hilos) {
        if (umbralBase <= 0 || radioBase < 0 || hilos < 1) {
            throw new IllegalArgumentException("Base inválida: umbral=" + umbralBase + " radio=" + radioBase
                    + " hilos=" + hilos);
        }
        umbralMin = umbralBase;
        // Tope: el doble del preset y nunca más de UMBRAL_TECHO. Más grueso, el plan pasa a
        // teselas grandes también cerca, que todavía no tienen datos: el LOD desaparece
        // (y el FPS "mejora", así que el ajuste seguiría bajando). Medido en Xvfb: a 10 px
        // quedaban 6 piezas de 168.
        umbralMax = Math.max(umbralBase, Math.min(umbralBase * 2, UMBRAL_TECHO));
        pasoUmbral = Math.max(0.25, umbralBase * 0.25);
        radioMax = radioBase;
        radioMin = Math.min(radioBase, Math.max(8, radioBase / 4));
        pasoRadio = Math.max(4, radioBase / 16);
        concurrenciaMin = 1;
        concurrenciaMax = hilos;
        umbralPx = umbralMin;
        radioChunks = radioMax;
        limiteConcurrencia = concurrenciaMax;
    }

    /**
     * Qué limita el cuadro. Si la GPU trabajó casi todo el cuadro, el CPU la
     * está esperando: límite GPU. Si no, la GPU espera al CPU.
     *
     * @param gpuMs tiempo de GPU del cuadro; NaN si no se pudo medir
     */
    public static Limite diagnosticar(double frameMs, double gpuMs) {
        if (Double.isNaN(gpuMs) || gpuMs <= 0 || frameMs <= 0) {
            return Limite.DESCONOCIDO;
        }
        return gpuMs >= frameMs * FRACCION_GPU ? Limite.GPU : Limite.CPU;
    }

    /**
     * Un ciclo (≈1 s) con los promedios medidos.
     *
     * @param peorMs   cuadro lento representativo del ciclo (el 1% peor): tirones
     * @param escalado true si hay un modo de escalado activo (la perilla de escala sirve)
     * @return true si cambió alguna perilla
     */
    public boolean ajustar(double frameMs, double peorMs, double gpuMs, double objetivoMs, boolean escalado) {
        Limite anterior = ultimoLimite;
        ultimoLimite = diagnosticar(frameMs, gpuMs);
        // Justo en el borde el diagnóstico alterna entre CPU y GPU y las perillas de reparto
        // irían y vendrían: un cambio de lado se confirma en el ciclo siguiente.
        boolean cambioDeLado = anterior != Limite.DESCONOCIDO && ultimoLimite != Limite.DESCONOCIDO
                && ultimoLimite != anterior;
        tirones = peorMs > objetivoMs * FACTOR_TIRON;
        if (tirones && frameMs <= objetivoMs) {
            // El promedio alcanza pero hay tirones: casi siempre son hilos de generación
            // compitiendo con el de render. Menos generación simultánea, nada más visible.
            ciclosConMargen = 0;
            if (limiteConcurrencia > concurrenciaMin) {
                limiteConcurrencia--;
                return true;
            }
            return false;
        }
        if (frameMs > objetivoMs) {
            ciclosConMargen = 0;
            if (cambioDeLado) {
                return false;
            }
            return switch (ultimoLimite) {
                case GPU -> aliviarGpu(escalado);
                case CPU -> aliviarCpu();
                case DESCONOCIDO -> aliviarGenerico();
            };
        }
        if (frameMs < objetivoMs * MARGEN) {
            if (++ciclosConMargen < CICLOS_PARA_RECUPERAR) {
                return false;
            }
            ciclosConMargen = 0;
            return recuperar();
        }
        ciclosConMargen = 0;
        return false;
    }

    private boolean aliviarGpu(boolean escalado) {
        if (distanciaUnBuffer > UN_BUFFER_MIN) {
            distanciaUnBuffer = Math.max(UN_BUFFER_MIN, distanciaUnBuffer / FACTOR_UN_BUFFER);
        } else if (factorOclusion < 1) {
            factorOclusion = Math.min(1, factorOclusion + PASO_OCLUSION);
        } else if (escalado && reduccionEscala < REDUCCION_ESCALA_MAX) {
            reduccionEscala = Math.min(REDUCCION_ESCALA_MAX, reduccionEscala + PASO_REDUCCION_ESCALA);
        } else {
            return subirUmbralOBajarRadio();
        }
        return true;
    }

    private boolean aliviarCpu() {
        if (limiteConcurrencia > concurrenciaMin) {
            limiteConcurrencia--;
        } else if (distanciaUnBuffer < UN_BUFFER_MAX) {
            distanciaUnBuffer = Math.min(UN_BUFFER_MAX, distanciaUnBuffer * FACTOR_UN_BUFFER);
        } else if (factorOclusion > OCLUSION_MIN) {
            factorOclusion = Math.max(OCLUSION_MIN, factorOclusion - PASO_OCLUSION);
        } else {
            return subirUmbralOBajarRadio();
        }
        return true;
    }

    private boolean aliviarGenerico() {
        if (limiteConcurrencia > concurrenciaMin) {
            limiteConcurrencia--;
            return true;
        }
        return subirUmbralOBajarRadio();
    }

    private boolean subirUmbralOBajarRadio() {
        if (umbralPx < umbralMax) {
            umbralPx = Math.min(umbralMax, umbralPx + pasoUmbral);
        } else if (radioChunks > radioMin) {
            radioChunks = Math.max(radioMin, radioChunks - pasoRadio);
        } else {
            return false; // piso absoluto: el mod ya no puede aliviar más
        }
        return true;
    }

    private boolean recuperar() {
        if (limiteConcurrencia < concurrenciaMax) {
            limiteConcurrencia++;
        } else if (radioChunks < radioMax) {
            radioChunks = Math.min(radioMax, radioChunks + pasoRadio);
        } else if (umbralPx > umbralMin) {
            umbralPx = Math.max(umbralMin, umbralPx - pasoUmbral);
        } else if (reduccionEscala > 0) {
            reduccionEscala = Math.max(0, reduccionEscala - PASO_REDUCCION_ESCALA);
        } else {
            return false;
        }
        return true;
    }

    public double umbralPx() {
        return umbralPx;
    }

    public int radioChunks() {
        return radioChunks;
    }

    public int limiteConcurrencia() {
        return limiteConcurrencia;
    }

    public double distanciaUnBuffer() {
        return distanciaUnBuffer;
    }

    public double factorOclusion() {
        return factorOclusion;
    }

    public int reduccionEscala() {
        return reduccionEscala;
    }

    public Limite ultimoLimite() {
        return ultimoLimite;
    }

    /** true si el último ciclo tuvo tirones (1% peor muy por encima del objetivo). */
    public boolean conTirones() {
        return tirones;
    }

    /** Detalle y radio en el piso y generación al mínimo: el hardware no da ni para eso. */
    public boolean enPisoAbsoluto() {
        return umbralPx >= umbralMax && radioChunks <= radioMin && limiteConcurrencia <= concurrenciaMin;
    }
}
