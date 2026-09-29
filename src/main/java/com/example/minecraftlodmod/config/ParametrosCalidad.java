package com.example.minecraftlodmod.config;

/**
 * Parámetros de calidad EFECTIVOS, ya resueltos a partir de la elección
 * del usuario (sección 11): un preset fijo, la recomendación automática por
 * hardware, o valores personalizados (modo "Personalizado" o resultado de
 * la calibración, sección 9). Es lo que consume el resto del mod — nadie
 * fuera de config/ debería leer {@link QualityPreset} directamente.
 *
 * Los rangos válidos viven acá (constantes MIN/MAX) para que el archivo de
 * config, la pantalla y esta validación no se desincronicen.
 */
public record ParametrosCalidad(
        int radioLodChunks,
        double umbralPx,
        int hilosGeneracion,
        int cacheRamMb,
        int colapsoDesdeNivel,
        int fpsObjetivo
) {

    /** Qué eligió el usuario en la config. */
    public enum Seleccion {
        AUTOMATICO, MINIMO, BAJO, MEDIO, ALTO, ULTRA, HORIZONTE, PERSONALIZADO;

        /** El preset fijo que representa, o null para AUTOMATICO/PERSONALIZADO. */
        public QualityPreset preset() {
            return switch (this) {
                case AUTOMATICO, PERSONALIZADO -> null;
                default -> QualityPreset.valueOf(name());
            };
        }
    }

    public static final int RADIO_MIN = 0, RADIO_MAX = 2048;
    public static final double UMBRAL_MIN = 0.25, UMBRAL_MAX = 32.0;
    public static final int HILOS_MIN = 1, HILOS_MAX = 32;
    public static final int CACHE_MIN_MB = 32, CACHE_MAX_MB = 16384;
    /**
     * El colapso nunca desde el nivel 0 (sección 2). 5 = uno más que el
     * último nivel derivado de una sección: no colapsar nunca (HORIZONTE).
     */
    public static final int COLAPSO_MIN = 1, COLAPSO_MAX = 5;
    public static final int FPS_MIN = 10, FPS_MAX = 360;

    public ParametrosCalidad {
        if (radioLodChunks < RADIO_MIN || radioLodChunks > RADIO_MAX
                || !(umbralPx >= UMBRAL_MIN && umbralPx <= UMBRAL_MAX)
                || hilosGeneracion < HILOS_MIN || hilosGeneracion > HILOS_MAX
                || cacheRamMb < CACHE_MIN_MB || cacheRamMb > CACHE_MAX_MB
                || colapsoDesdeNivel < COLAPSO_MIN || colapsoDesdeNivel > COLAPSO_MAX
                || fpsObjetivo < FPS_MIN || fpsObjetivo > FPS_MAX) {
            throw new IllegalArgumentException("Parámetros de calidad fuera de rango: radio=" + radioLodChunks
                    + " umbral=" + umbralPx + " hilos=" + hilosGeneracion + " cache=" + cacheRamMb
                    + " colapso=" + colapsoDesdeNivel + " fps=" + fpsObjetivo);
        }
    }

    public static ParametrosCalidad de(QualityPreset preset) {
        return new ParametrosCalidad(preset.radioLodChunks, preset.umbralPxInicial, preset.hilosGeneracion,
                preset.cacheRamMb, preset.colapsoHomogeneoDesdeNivel, preset.objetivoFpsSugerido);
    }

    /**
     * Resuelve la selección del usuario.
     *
     * @param personalizados valores usados solo con {@link Seleccion#PERSONALIZADO};
     *                       se recortan a los rangos válidos (un archivo de
     *                       config editado a mano no debe romper el arranque)
     * @param nucleosCpu     para AUTOMATICO
     * @param ramTotalMb     para AUTOMATICO
     */
    public static ParametrosCalidad resolver(Seleccion seleccion, ParametrosCalidad.Crudos personalizados,
                                             int nucleosCpu, long ramTotalMb) {
        return switch (seleccion) {
            case AUTOMATICO -> de(QualityPreset.recomendarPorHardware(nucleosCpu, ramTotalMb));
            case PERSONALIZADO -> personalizados.recortados();
            default -> de(seleccion.preset());
        };
    }

    /**
     * Frame time objetivo en ms para alimentar el auto-ajuste (sección 7);
     * mismo cálculo que {@code ControlDeRendimiento.objetivoMsDesdeFps}.
     */
    public double frameTimeObjetivoMs() {
        return 1000.0 / fpsObjetivo;
    }

    /** Valores tal como vienen del archivo, sin validar. */
    public record Crudos(int radioLodChunks, double umbralPx, int hilosGeneracion,
                         int cacheRamMb, int colapsoDesdeNivel, int fpsObjetivo) {

        public ParametrosCalidad recortados() {
            double umbral = Double.isNaN(umbralPx) ? UMBRAL_MIN : umbralPx;
            return new ParametrosCalidad(
                    Math.clamp(radioLodChunks, RADIO_MIN, RADIO_MAX),
                    Math.clamp(umbral, UMBRAL_MIN, UMBRAL_MAX),
                    Math.clamp(hilosGeneracion, HILOS_MIN, HILOS_MAX),
                    Math.clamp(cacheRamMb, CACHE_MIN_MB, CACHE_MAX_MB),
                    Math.clamp(colapsoDesdeNivel, COLAPSO_MIN, COLAPSO_MAX),
                    Math.clamp(fpsObjetivo, FPS_MIN, FPS_MAX));
        }
    }
}
