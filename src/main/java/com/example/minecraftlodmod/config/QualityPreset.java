package com.example.minecraftlodmod.config;

/**
 * Los 4 presets de calidad definidos en la sección 8 del documento de
 * arquitectura. Es Java puro (sin dependencias de Minecraft), así que esta
 * parte queda completamente funcional sin necesitar compilar contra NeoForge.
 *
 * La integración con Cloth Config (pantalla in-game, persistencia del
 * archivo de config) todavía no está implementada (Pista A, ítem 5).
 */
public enum QualityPreset {

    /**
     * Pensado para hardware muy por debajo del Ryzen 3500U de referencia,
     * como la Lenovo ThinkPad A275 (AMD PRO A12-8830B, arquitectura
     * Excavator pre-Zen) — radio de LOD bajo y objetivo de FPS más
     * conservador (24 en vez de 30), porque en este hardware ni el juego
     * base tiene margen holgado. Ver sección 21 del documento de arquitectura.
     */
    MINIMO(32, 6.0, 1, 100, 1, 24),
    BAJO(96, 4.0, 1, 250, 1, 30),
    MEDIO(160, 2.5, 2, 500, 2, 40),
    ALTO(224, 1.5, 3, 900, 3, 50),
    ULTRA(320, 1.0, 4, 1500, 4, 60),
    /**
     * Pensado para hardware con GPU dedicada (ej. GTX 1060 + i5-9400, no el
     * Ryzen 3500U de referencia): radio mucho mayor, apuntando a que el
     * terreno lejano se perciba como un horizonte real en vez de simplemente
     * "buen LOD". Requiere generación completa + reducción jerárquica como
     * mínimo; se beneficia de "rough generation" (secciones 15-17 del
     * documento de arquitectura) para estirar el radio todavía más, pero no
     * depende de esa optimización para ser viable, a diferencia de lo que
     * pasaría intentando este preset en el 3500U.
     */
    HORIZONTE(1024, 0.75, 5, 4000, 5, 45);

    public final int radioLodChunks;
    public final double umbralPxInicial;
    public final int hilosGeneracion;
    public final int cacheRamMb;
    public final int colapsoHomogeneoDesdeNivel;
    /** FPS objetivo sugerido para este preset — punto de partida para la calibración (sección 9), no un mínimo garantizado. */
    public final int objetivoFpsSugerido;

    QualityPreset(int radioLodChunks, double umbralPxInicial, int hilosGeneracion,
                  int cacheRamMb, int colapsoHomogeneoDesdeNivel, int objetivoFpsSugerido) {
        this.radioLodChunks = radioLodChunks;
        this.umbralPxInicial = umbralPxInicial;
        this.hilosGeneracion = hilosGeneracion;
        this.cacheRamMb = cacheRamMb;
        this.colapsoHomogeneoDesdeNivel = colapsoHomogeneoDesdeNivel;
        this.objetivoFpsSugerido = objetivoFpsSugerido;
    }

    /**
     * Preset inmediatamente más liviano, o null si ya es el más bajo.
     * Usado por la calibración (sección 9) para retroceder un escalón.
     */
    public QualityPreset masLiviano() {
        return switch (this) {
            case HORIZONTE -> ULTRA;
            case ULTRA -> ALTO;
            case ALTO -> MEDIO;
            case MEDIO -> BAJO;
            case BAJO -> MINIMO;
            case MINIMO -> null;
        };
    }

    /** Preset inmediatamente más pesado, o null si ya es el más alto. */
    public QualityPreset masPesado() {
        return switch (this) {
            case MINIMO -> BAJO;
            case BAJO -> MEDIO;
            case MEDIO -> ALTO;
            case ALTO -> ULTRA;
            case ULTRA -> HORIZONTE;
            case HORIZONTE -> null;
        };
    }

    /**
     * Heurística simple de preset recomendado por defecto, basada en núcleos
     * de CPU disponibles y RAM total del sistema en MB. Es solo un punto de
     * partida sugerido — el jugador puede elegir otro y calibrar desde ahí
     * (sección 9 del documento de arquitectura).
     *
     * Nota: esta heurística no tiene en cuenta la GPU (no hay forma simple
     * de detectar "GPU dedicada con X de VRAM" de forma portable), así que
     * nunca recomienda {@link #HORIZONTE} automáticamente — ese preset está
     * pensado para elegirse a mano en hardware con GPU dedicada (ver su
     * javadoc), no para ser sugerido por esta heurística.
     *
     * Tampoco distingue arquitectura de CPU, solo cantidad de núcleos y RAM:
     * una Lenovo ThinkPad A275 (4 núcleos Excavator, 16GB) y un Ryzen 3500U
     * (4 núcleos Zen+, 12-16GB) caen en el mismo rango de esta heurística
     * aunque el rendimiento real por núcleo sea muy distinto — la heurística
     * probablemente sobreestime a la A275 y recomiende MEDIO en vez de
     * MINIMO. Es la calibración real por benchmark (sección 9) la que
     * corrige esto, no la heurística — que es solo un punto de partida.
     */
    public static QualityPreset recomendarPorHardware(int nucleosCpu, long ramTotalMb) {
        if (nucleosCpu <= 2 && ramTotalMb < 4000) {
            return MINIMO;
        }
        if (nucleosCpu <= 2 || ramTotalMb < 6000) {
            return BAJO;
        }
        if (nucleosCpu <= 4 || ramTotalMb < 10000) {
            return MEDIO;
        }
        if (nucleosCpu <= 6 || ramTotalMb < 16000) {
            return ALTO;
        }
        return ULTRA;
    }
}
