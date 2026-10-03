package com.example.minecraftlodmod.render;

/**
 * Desplazamiento sub-píxel por cuadro para los escaladores temporales
 * (propio, XeSS, DLSS): la proyección se corre un poco distinto en cada
 * cuadro y el escalador junta esas muestras en la resolución de salida.
 *
 * Secuencia de Halton en bases 2 y 3, con la cantidad de fases que
 * recomienda AMD para FSR 2 según el factor de escala (más fases cuanto más
 * chica es la imagen de entrada). Lógica pura.
 */
public final class SecuenciaJitter {

    private SecuenciaJitter() {
    }

    /** Término {@code indice} (desde 1) de la secuencia de Halton en {@code base}, en [0, 1). */
    static double halton(int indice, int base) {
        double f = 1, r = 0;
        for (int i = indice; i > 0; i /= base) {
            f /= base;
            r += f * (i % base);
        }
        return r;
    }

    /** Fases de la secuencia: ceil(8 × (salida / entrada)²), mínimo 8. */
    public static int fases(int anchoEntrada, int anchoSalida) {
        double razon = (double) anchoSalida / Math.max(1, anchoEntrada);
        return Math.max(8, (int) Math.ceil(8 * razon * razon));
    }

    /**
     * Desplazamiento del cuadro, en píxeles de la imagen de entrada, cada
     * componente en [-0.5, 0.5).
     */
    public static double[] desplazamiento(long cuadro, int fases) {
        int i = (int) Math.floorMod(cuadro, (long) fases) + 1;
        return new double[] {halton(i, 2) - 0.5, halton(i, 3) - 0.5};
    }
}
