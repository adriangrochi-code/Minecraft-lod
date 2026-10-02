package com.example.minecraftlodmod.tierra;

/**
 * La costura este-oeste de la Tierra cilíndrica ({@code docs/tierra-real/05-borde.md},
 * H10), lógica pura. El mundo es periódico en x con período C (la vuelta al
 * ecuador, múltiplo de 16: los chunks de un lado y del otro coinciden): el
 * terreno en x y en x ± C es el mismo bloque a bloque, así que pasando el
 * antimeridiano (x = ±C/2) se ve la continuación del otro lado, y al
 * jugador se lo lleva al otro lado cuando ya cruzó ({@link #salto}).
 *
 * Los ruidos propios (detalle, cuevas) no son periódicos: en una franja de
 * {@code ancho} bloques antes de +C/2 se mezclan con su valor una vuelta
 * antes ({@link #peso}), así en +C/2 valen lo mismo que en -C/2 sin escalón.
 */
public final class Costura {

    /** Pasando la costura por más de esto, el jugador salta al otro lado (histéresis: no rebota). */
    public static final double MARGEN_SALTO = 32;
    /** Franja antes de +C/2 en la que los ruidos propios se mezclan con los de una vuelta antes. */
    public static final double ANCHO_MEZCLA = 512;

    private Costura() {}

    /** Vuelta al ecuador en bloques, redondeada a múltiplo de 16. */
    public static long circunferencia(double metrosPorBloque) {
        return Math.round(2 * Math.PI * Proyeccion.RADIO_TIERRA_M / metrosPorBloque / 16) * 16;
    }

    /** x llevado a [-C/2, C/2). Exacto para x con parte fraccionaria en múltiplos de 1/2^k. */
    public static double envolver(double x, double c) {
        if (c <= 0) return x;
        return x - c * Math.floor((x + c / 2) / c);
    }

    /**
     * Peso (0..1, curva suave) del valor de una vuelta antes en {@code x}
     * envuelto: 0 lejos de la costura, 1 al llegar a +C/2.
     */
    public static double peso(double xEnvuelto, double c, double ancho) {
        if (c <= 0) return 0;
        double t = (xEnvuelto - (c / 2 - ancho)) / ancho;
        if (t <= 0) return 0;
        t = Math.min(1, t);
        return t * t * (3 - 2 * t);
    }

    /**
     * Cuánto mover en x algo que está en {@code x}: -C o +C si pasó la costura
     * por más de {@code margen}, 0 si no.
     */
    public static double salto(double x, double c, double margen) {
        if (c <= 0) return 0;
        if (x > c / 2 + margen) return -c;
        if (x < -c / 2 - margen) return c;
        return 0;
    }
}
