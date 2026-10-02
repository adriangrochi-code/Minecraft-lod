package com.example.minecraftlodmod.tierra;

/**
 * Bloque (x, z) ↔ (latitud, longitud) en grados. Lógica pura; la escala va en
 * la proyección (metros por bloque, ver {@code docs/tierra-real/01-decisiones.md}).
 *
 * La inversa no recorta: una latitud fuera de [-90, 90] o una longitud fuera
 * de [-180, 180) significa "fuera del planeta" (el borde de
 * {@code 05-borde.md}); quien consulta datos decide qué hacer ahí.
 */
public interface Proyeccion {

    /** Radio medio de la Tierra (IUGG), en metros. */
    double RADIO_TIERRA_M = 6_371_008.8;

    double latitud(double x, double z);

    double longitud(double x, double z);

    double x(double latitud, double longitud);

    double z(double latitud, double longitud);

    /** Metros reales por bloque (8 a 1:8). */
    double metrosPorBloque();

    /** Bloques por grado de arco de meridiano. */
    default double bloquesPorGrado() {
        return 2 * Math.PI * RADIO_TIERRA_M / metrosPorBloque() / 360.0;
    }

    /**
     * Período en x del mundo (la vuelta al ecuador, {@link Costura}), o 0 si
     * no es periódico (la Tierra plana).
     */
    default double periodoX() {
        return 0;
    }

    /** Lleva una longitud a [-180, 180). */
    static double normalizarLongitud(double lon) {
        double l = (lon + 180.0) % 360.0;
        if (l < 0) l += 360.0;
        return l - 180.0;
    }
}
