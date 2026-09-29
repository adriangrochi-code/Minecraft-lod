package com.example.minecraftlodmod.config;

/**
 * Deriva los topes de memoria de un preset (sección 24 del documento de
 * arquitectura): el presupuesto de {@code BoundedRegionCache} y el
 * {@code maxTareasEnCola} de {@code GenerationTaskScheduler} salen ambos de
 * {@link QualityPreset#cacheRamMb}, en vez de quedar como valores libres.
 *
 * Reparto: la mayor parte va al cache de nodos (lo que evita releer disco);
 * el resto acota cuántas tareas de generación pueden estar vivas a la vez,
 * dividido por un tamaño estimado de contexto por tarea.
 */
public record PresupuestoMemoria(long bytesCacheRegiones, int maxTareasEnCola) {

    /** Fracción de {@code cacheRamMb} que va al cache de nodos; el resto, a la cola de generación. */
    public static final double FRACCION_CACHE = 0.8;

    /**
     * Estimado de RAM retenida por una tarea de generación encolada: una
     * sección de 16³ supervóxeles como objetos Java (~160 KB) más el mesh
     * intermedio. Estimado, no medido — recalibrar con un profiler en Pista B.
     */
    public static final long BYTES_ESTIMADOS_POR_TAREA = 256L * 1024;

    /** Techo absoluto de la cola, para que un preset con mucha RAM no encole sin sentido. */
    public static final int MAX_TAREAS_ABSOLUTO = 4096;

    public static PresupuestoMemoria para(QualityPreset preset) {
        return para(preset.cacheRamMb, preset.hilosGeneracion);
    }

    public static PresupuestoMemoria para(int cacheRamMb, int hilosGeneracion) {
        if (cacheRamMb <= 0) {
            throw new IllegalArgumentException("cacheRamMb debe ser positivo, fue: " + cacheRamMb);
        }
        if (hilosGeneracion < 1) {
            throw new IllegalArgumentException("hilosGeneracion debe ser al menos 1, fue: " + hilosGeneracion);
        }
        long totalBytes = (long) cacheRamMb * 1024 * 1024;
        long bytesCache = (long) (totalBytes * FRACCION_CACHE);
        long bytesCola = totalBytes - bytesCache;

        // Piso: al menos dos tareas por hilo, para que ningún hilo quede ocioso esperando cola.
        long tareas = bytesCola / BYTES_ESTIMADOS_POR_TAREA;
        int maxTareas = (int) Math.max(2L * hilosGeneracion, Math.min(MAX_TAREAS_ABSOLUTO, tareas));
        return new PresupuestoMemoria(bytesCache, maxTareas);
    }
}
