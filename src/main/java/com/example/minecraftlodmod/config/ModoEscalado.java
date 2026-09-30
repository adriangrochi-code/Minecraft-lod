package com.example.minecraftlodmod.config;

/**
 * Cómo se lleva a la pantalla el mundo dibujado a menor resolución (ver
 * {@code render.Escalado}).
 */
public enum ModoEscalado {
    /** Resolución completa, sin escalado. */
    APAGADO,
    /** AMD FSR 1 (espacial: EASU + RCAS). Anda en cualquier GPU. */
    FSR1,
    /** Escalador temporal propio: junta varios cuadros con jitter. Anda en cualquier GPU. */
    TEMPORAL,
    /** Intel XeSS (Windows, necesita libxess.dll; GPUs con DP4a). */
    XESS,
    /** NVIDIA DLSS (Windows, solo RTX; necesita las DLL de NVIDIA). */
    DLSS;

    /** true para los que usan jitter, vectores de movimiento e historial. */
    public boolean temporal() {
        return this == TEMPORAL || this == XESS || this == DLSS;
    }
}
