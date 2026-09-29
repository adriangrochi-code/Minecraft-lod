package com.example.minecraftlodmod.generation;

/**
 * Prioridad según hacia dónde mira el jugador (lógica pura): lo que está
 * en su campo visual primero, después un margen alrededor (para que al
 * girar ya esté listo) y al final lo que tiene detrás. La usan el orden de
 * armado de mallas del render, los chunks pendientes de extracción y el
 * pregenerador.
 *
 * El costo es la distancia al cuadrado multiplicada por un factor según el
 * ángulo entre la mirada (horizontal) y la dirección al punto: menor costo,
 * antes se atiende.
 */
public final class PrioridadVista {

    /** Media apertura horizontal considerada "en vista" (FOV 70° a 16:9 ≈ 51°, más margen). */
    public static final double MEDIA_APERTURA_VISTA = Math.toRadians(60);
    /** Hasta acá es "margen": se prepara para cuando el jugador gire. */
    public static final double MEDIA_APERTURA_MARGEN = Math.toRadians(100);
    static final double FACTOR_VISTA = 1, FACTOR_MARGEN = 3, FACTOR_ATRAS = 8;
    /** Más cerca que esto (bloques) todo es "en vista": girar la cabeza es instantáneo. */
    public static final double DISTANCIA_SIEMPRE_VISTA = 48;

    private static final double COS_VISTA = Math.cos(MEDIA_APERTURA_VISTA);
    private static final double COS_MARGEN = Math.cos(MEDIA_APERTURA_MARGEN);

    private PrioridadVista() {
    }

    /**
     * @param dx, dz       punto relativo al jugador, en bloques
     * @param miraX, miraZ dirección horizontal de la mirada (no hace falta normalizarla;
     *                     0, 0 = sin dirección, todo cuenta como en vista)
     */
    public static double costo(double dx, double dz, double miraX, double miraZ) {
        double d2 = dx * dx + dz * dz;
        return d2 * factor(dx, dz, miraX, miraZ);
    }

    public static double factor(double dx, double dz, double miraX, double miraZ) {
        double d = Math.hypot(dx, dz), m = Math.hypot(miraX, miraZ);
        if (d < DISTANCIA_SIEMPRE_VISTA || m < 1e-9) {
            return FACTOR_VISTA;
        }
        double cos = (dx * miraX + dz * miraZ) / (d * m);
        return cos >= COS_VISTA ? FACTOR_VISTA : cos >= COS_MARGEN ? FACTOR_MARGEN : FACTOR_ATRAS;
    }

    /**
     * true si el punto está dentro de la media apertura dada alrededor de la
     * mirada (o muy cerca del jugador).
     */
    public static boolean dentro(double dx, double dz, double miraX, double miraZ, double mediaApertura) {
        double d = Math.hypot(dx, dz), m = Math.hypot(miraX, miraZ);
        if (d < DISTANCIA_SIEMPRE_VISTA || m < 1e-9) {
            return true;
        }
        return (dx * miraX + dz * miraZ) / (d * m) >= Math.cos(mediaApertura);
    }
}
