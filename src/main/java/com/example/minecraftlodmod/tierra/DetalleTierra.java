package com.example.minecraftlodmod.tierra;

/**
 * Detalle a escala de bloque ({@code docs/tierra-real/03-generador.md}): el
 * dato tiene una muestra cada ~116 bloques (30″ a 1:8) y entre muestras la
 * bicúbica es lisa. Se le suma ruido fractal de crestas (4 octavas, de
 * {@link #ONDA_MAYOR} a 20 bloques de longitud de onda) con amplitud según la
 * rugosidad del lugar ({@link FuenteTierra#elevacion(double, double, double[])}:
 * la pendiente de la bicúbica, continua). Las llanuras quedan llanas y las
 * laderas ganan crestas y valles.
 *
 * Cerca de la costa se apaga ({@link #COSTA_M}) y además nunca cambia el signo
 * de la elevación (no crea islas ni lagos falsos): lo que estaba sobre el mar
 * queda sobre el mar. Lógica pura, determinística (el mismo detalle en todos
 * los mundos, así el atajo del LOD da lo mismo que el generador).
 */
public final class DetalleTierra {

    /** Fracción del rango local que puede sumar o restar el detalle. */
    static final double FRACCION_RANGO = 0.12;
    /** Tope de la amplitud, en metros. */
    static final double AMPLITUD_MAXIMA_M = 300;
    /** Bajo esta elevación (en valor absoluto, metros) el detalle se va apagando. */
    static final double COSTA_M = 60;
    static final double ONDA_MAYOR = 160;

    private DetalleTierra() {}

    /**
     * Elevación con detalle, en metros.
     *
     * @param x, z     bloque (el detalle se mide en bloques, no en grados)
     * @param elevacion elevación de la bicúbica, en metros
     * @param rango     rugosidad: lo que sube la bicúbica en ~3 muestras, en metros
     */
    public static double conDetalle(double x, double z, double elevacion, double rango) {
        return conDetalle(x, z, elevacion, rango, 0);
    }

    /** Franja antes de la costura en la que el ruido se mezcla con el de una vuelta antes ({@link Costura}). */
    static final double ANCHO_COSTURA = 512;

    /**
     * Como {@link #conDetalle(double, double, double, double)}, periódico en x
     * con período {@code periodoX} (0 = no periódico; x ya envuelto a [-C/2, C/2)).
     */
    public static double conDetalle(double x, double z, double elevacion, double rango, double periodoX) {
        double amplitud = Math.min(AMPLITUD_MAXIMA_M, FRACCION_RANGO * rango);
        double t = Math.min(1, Math.abs(elevacion) / COSTA_M);
        amplitud *= t * t * (3 - 2 * t);
        if (amplitud < 0.5) return elevacion;
        double ruido = crestas(x / ONDA_MAYOR, z / ONDA_MAYOR);
        double w = Costura.peso(x, periodoX, ANCHO_COSTURA);
        if (w > 0) ruido += (crestas((x - periodoX) / ONDA_MAYOR, z / ONDA_MAYOR) - ruido) * w;
        double r = elevacion + amplitud * ruido;
        // Nunca cambia de lado del nivel del mar.
        return elevacion > 0 ? Math.max(r, elevacion * 0.25) : Math.min(r, elevacion * 0.25);
    }

    /**
     * Ruido fractal en ~[-1, 1]: la octava grande mezcla una cresta suave
     * (1 - p², sin el pliegue de 1 - |p|, que se veía como contornos de
     * manchas), las demás son ruido común, con pesos que bajan rápido (las
     * finas en igual peso daban un aspecto de "gusanos").
     */
    static double crestas(double u, double v) {
        double suma = 0, total = 0;
        for (int o = 0; o < 4; o++) {
            double p = perlin(u, v, o);
            // Cresta suave (sin pliegue) mezclada en la octava grande; ruido común en las demás
            double n = o == 0 ? 0.5 * p + 0.5 * ((1 - p * p) * 2 - 1) : p;
            double peso = PESOS[o];
            suma += peso * n;
            total += peso;
            u = u * 2 + 31.7;
            v = v * 2 - 17.3;
        }
        return suma / total;
    }

    private static final double[] PESOS = {1, 0.5, 0.25, 0.12};

    /** Perlin 2D en ~[-1, 1] con gradientes de 8 direcciones. */
    static double perlin(double u, double v, int canal) {
        int iu = (int) Math.floor(u), iv = (int) Math.floor(v);
        double fu = u - iu, fv = v - iv;
        double a = gradiente(iu, iv, canal, fu, fv), b = gradiente(iu + 1, iv, canal, fu - 1, fv);
        double c = gradiente(iu, iv + 1, canal, fu, fv - 1), d = gradiente(iu + 1, iv + 1, canal, fu - 1, fv - 1);
        double su = fu * fu * fu * (fu * (fu * 6 - 15) + 10), sv = fv * fv * fv * (fv * (fv * 6 - 15) + 10);
        double ab = a + (b - a) * su, cd = c + (d - c) * su;
        return (ab + (cd - ab) * sv) * 1.4142;
    }

    private static final double[] GX = {1, -1, 0, 0, 0.7071, -0.7071, 0.7071, -0.7071};
    private static final double[] GY = {0, 0, 1, -1, 0.7071, 0.7071, -0.7071, -0.7071};

    private static double gradiente(int iu, int iv, int canal, double du, double dv) {
        long h = iu * 0x9E3779B97F4A7C15L ^ iv * 0xC2B2AE3D27D4EB4FL ^ canal * 0x165667B19E3779F9L;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        int g = (int) (h >>> 61);
        return GX[g] * du + GY[g] * dv;
    }
}
