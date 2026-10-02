package com.example.minecraftlodmod.tierra;

/**
 * Cuevas de Tierra real ({@code docs/tierra-real/06-hitos.md}, H9), lógica
 * pura: dependen de la <b>profundidad bajo la superficie</b>, no de {@code y}
 * absoluto (las de vanilla viven en y -64..320 y en un mundo de -1296..1376
 * quedaban colgadas en medio de las montañas o sobre el fondo del mar).
 *
 * <ul>
 *   <li><b>Túneles</b> ("espagueti"): donde dos ruidos 3D están a la vez cerca
 *       de cero (la intersección de dos superficies es una curva: un tubo).</li>
 *   <li><b>Cavernas</b> ("queso"): donde un ruido 3D grande pasa un umbral,
 *       más frecuentes cuanto más hondo.</li>
 *   <li>Entre {@link #PROFUNDIDAD_MINIMA} y {@link #PROFUNDIDAD_MAXIMA} bloques
 *       bajo el suelo, con entradas raras hasta la superficie.</li>
 * </ul>
 * La densidad que devuelve es positiva en la roca y negativa en la cueva, con
 * pendiente suave (la interpolación de celdas de 4×8×4 la deja redondeada).
 * Determinística, sin semilla del mundo (igual que el detalle y el borde).
 */
public final class CuevasTierra {

    public static final double PROFUNDIDAD_MINIMA = 12;
    public static final double PROFUNDIDAD_MAXIMA = 400;
    /** Bajo esta profundidad, las cuevas de tierra firme se llenan de lava (vanilla: bajo y -54). */
    public static final int PROFUNDIDAD_LAVA = 160;

    private CuevasTierra() {}

    /**
     * Densidad con cuevas: {@code min(base, cuevas)}.
     *
     * @param base densidad de la superficie, {@code superficie - y}: es la profundidad bajo el suelo
     */
    public static double densidad(double x, double y, double z, double base) {
        return densidad(x, y, z, base, 0);
    }

    /** Franja antes de la costura en la que se mezcla con las cuevas de una vuelta antes ({@link Costura}). */
    static final double ANCHO_COSTURA = Costura.ANCHO_MEZCLA;

    /** Como {@link #densidad(double, double, double, double)}, periódica en x con período {@code periodoX} (0 = no). */
    public static double densidad(double x, double y, double z, double base, double periodoX) {
        if (base <= 0 || base > PROFUNDIDAD_MAXIMA + 40) return base; // aire, o muy hondo: nada que calcular
        x = Costura.envolver(x, periodoX);
        double d = cuevas(x, y, z, base);
        double w = Costura.peso(x, periodoX, ANCHO_COSTURA);
        if (w > 0) d += (cuevas(x - periodoX, y, z, base) - d) * w;
        return Math.min(base, d);
    }

    /** Densidad de las cuevas solas a esa profundidad (positiva = roca). */
    static double cuevas(double x, double y, double z, double profundidad) {
        // Túneles: dos ruidos cerca de cero a la vez
        double a = perlin(x / 56, y / 36, z / 56, 0), b = perlin(x / 56 + 91.3, y / 36, z / 56 - 47.1, 1);
        double ancho = 0.075 + 0.03 * perlin(x / 300, y / 200, z / 300, 2);
        double tunel = (Math.sqrt(a * a + b * b) - ancho) * 60;
        // Cavernas: más grandes con la profundidad
        double q = perlin(x / 110, y / 50, z / 110, 3) + 0.5 * perlin(x / 45, y / 22, z / 45, 4);
        double umbral = 0.62 - 0.14 * Math.min(1, profundidad / 300);
        double caverna = (umbral - q) * 40;
        double d = Math.min(tunel, caverna);
        // Cerca del suelo, roca (salvo en las entradas, solo túneles); lejos de la franja, también
        if (profundidad < PROFUNDIDAD_MINIMA) {
            boolean entrada = perlin(x / 400, 3.7, z / 400, 5) > 0.42;
            d = entrada ? Math.max(tunel, (2 - profundidad) * 0.5) : d + (PROFUNDIDAD_MINIMA - profundidad) * 2;
        }
        if (profundidad > PROFUNDIDAD_MAXIMA) d += (profundidad - PROFUNDIDAD_MAXIMA) * 0.5;
        return d;
    }

    // ------------------------------------------------------------------ Perlin 3D propio

    private static final int[][] GRADIENTES = {
            {1, 1, 0}, {-1, 1, 0}, {1, -1, 0}, {-1, -1, 0}, {1, 0, 1}, {-1, 0, 1}, {1, 0, -1}, {-1, 0, -1},
            {0, 1, 1}, {0, -1, 1}, {0, 1, -1}, {0, -1, -1}, {1, 1, 0}, {-1, 1, 0}, {0, -1, 1}, {0, -1, -1}};

    /** Perlin 3D en ~[-1, 1]. */
    static double perlin(double x, double y, double z, int canal) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y), iz = (int) Math.floor(z);
        double fx = x - ix, fy = y - iy, fz = z - iz;
        double u = suave(fx), v = suave(fy), w = suave(fz);
        double x00 = lerp(u, g(ix, iy, iz, canal, fx, fy, fz), g(ix + 1, iy, iz, canal, fx - 1, fy, fz));
        double x10 = lerp(u, g(ix, iy + 1, iz, canal, fx, fy - 1, fz), g(ix + 1, iy + 1, iz, canal, fx - 1, fy - 1, fz));
        double x01 = lerp(u, g(ix, iy, iz + 1, canal, fx, fy, fz - 1), g(ix + 1, iy, iz + 1, canal, fx - 1, fy, fz - 1));
        double x11 = lerp(u, g(ix, iy + 1, iz + 1, canal, fx, fy - 1, fz - 1),
                g(ix + 1, iy + 1, iz + 1, canal, fx - 1, fy - 1, fz - 1));
        return lerp(w, lerp(v, x00, x10), lerp(v, x01, x11));
    }

    private static double g(int ix, int iy, int iz, int canal, double dx, double dy, double dz) {
        long h = ix * 0x9E3779B97F4A7C15L ^ iy * 0xC2B2AE3D27D4EB4FL ^ iz * 0x165667B19E3779F9L ^ canal * 0xD6E8FEB86659FD93L;
        h ^= h >>> 32;
        h *= 0xD6E8FEB86659FD93L;
        h ^= h >>> 32;
        int[] gr = GRADIENTES[(int) (h & 15)];
        return gr[0] * dx + gr[1] * dy + gr[2] * dz;
    }

    private static double suave(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double lerp(double t, double a, double b) {
        return a + (b - a) * t;
    }
}
