package com.example.minecraftlodmod.tierra;

/**
 * El borde de la Tierra plana ({@code docs/tierra-real/05-borde.md}), lógica
 * pura: la densidad pasando el polo sur (el borde del disco de la proyección
 * azimutal), en función de
 * <ul>
 *   <li>{@code d}: bloques más allá del borde del disco (≥ 0),</li>
 *   <li>{@code s}: bloques a lo largo del borde (arco, para que el patrón no se
 *       estire al dar la vuelta),</li>
 *   <li>{@code y} y la densidad de la superficie normal ({@code superficie - y},
 *       que más allá del borde es la meseta del polo sur).</li>
 * </ul>
 * Tres bandas:
 * <ol>
 *   <li><b>Transición</b> ({@link #TRANSICION} bloques): la meseta de hielo se
 *       parte en placas (celdas de {@link #CELDA_PLACA}) separadas por grietas
 *       cada vez más anchas y hondas.</li>
 *   <li><b>Farlands</b> (desde ahí; las propiamente dichas en los primeros
 *       {@link #ANCHO_FARLANDS} bloques y siguen hasta el borde del mundo):
 *       imitan el artefacto de la Beta (el ruido desbordado repite la misma
 *       rebanada): la coordenada radial va <b>escalonada</b> cada
 *       {@link #ESCALON} bloques (paredes de corte vertical) y el ruido se
 *       estira en vertical (capas y túneles horizontales que se repiten), hasta
 *       casi el techo del mundo. Aparecen en una rampa de {@link #RAMPA}
 *       bloques.</li>
 * </ol>
 * Determinística (sin semilla del mundo: el borde es el mismo en todos los
 * mundos) y barata: solo se evalúa pasando el borde.
 */
public final class FarlandsCongeladas {

    public static final double TRANSICION = 1000;
    public static final double ANCHO_FARLANDS = 4000;
    public static final double CELDA_PLACA = 120;
    public static final double RAMPA = 160;
    public static final int ESCALON = 16;
    /** Distancia al techo del mundo que las paredes dejan libre. */
    public static final int MARGEN_TECHO = 24;

    private FarlandsCongeladas() {}

    /**
     * Densidad en el punto (positiva = sólido).
     *
     * @param base densidad de la superficie normal en el punto ({@code superficie - y})
     * @param superficie altura de la superficie normal (la meseta) en la columna
     */
    public static double densidad(double d, double s, int y, double base, double superficie, int minY, int maxY) {
        if (d < 0) return base;
        double dens = base;
        if (d < TRANSICION + RAMPA) {
            dens = conGrietas(d, s, y, base, superficie);
        }
        if (d >= TRANSICION) {
            double a = Math.min(1, (d - TRANSICION) / RAMPA);
            double paredes = paredes(d, s, y, minY, maxY);
            // Las paredes crecen desde la meseta: al principio de la rampa solo asoman las más altas.
            dens = Math.max(dens, paredes - (1 - a) * 6);
        }
        return dens;
    }

    /** La meseta con grietas entre placas: más anchas y hondas hacia afuera. */
    static double conGrietas(double d, double s, int y, double base, double superficie) {
        double t = Math.min(1, d / TRANSICION);
        double ancho = 1.5 + 9 * t;          // bloques
        double hondo = 12 + 260 * t * t;     // bloques bajo la meseta
        double borde = distanciaABordeDeCelda(s / CELDA_PLACA, d / CELDA_PLACA) * CELDA_PLACA;
        if (borde >= ancho) return base;
        // Paredes de la grieta en V: más angosta al fondo.
        double fondo = superficie - hondo * (1 - borde / ancho);
        return Math.min(base, fondo - y);
    }

    /**
     * Las paredes, a la manera de las farlands de la Beta: franjas concéntricas
     * (cada rebanada radial de {@link #ESCALON} bloques es pared maciza o
     * pasillo de aire, según una tirada por rebanada y por tramo largo del
     * borde), atravesadas por túneles horizontales regulares (corridos de una
     * rebanada a la siguiente) y con agujeros chicos. Positivo = hielo.
     */
    static double paredes(double d, double s, int y, int minY, int maxY) {
        int rebanada = (int) Math.floor(d / ESCALON);
        if (y > cimaPared(rebanada, s, maxY)) return -1;
        // Túneles horizontales: cada 28 bloques, 6 de alto, corridos según la rebanada.
        int fase = Math.floorMod(y + rebanada * 5, 28);
        if (fase < 6 && ruido3(rebanada * 0.5, y / 28.0, s / 90.0, 5) > -0.35) return -1;
        // Agujeros chicos.
        if (ruido3(rebanada * 1.7, y / 9.0, s / 11.0, 6) > 0.62) return -1;
        return 1;
    }

    /**
     * Cima de la pared de la rebanada en ese punto del borde (por encima, aire),
     * o {@code Integer.MIN_VALUE} si ahí hay un pasillo. Las paredes llegan casi
     * al techo del mundo, con almenas grandes.
     */
    static int cimaPared(int rebanada, double s, int maxY) {
        // Pared o pasillo: tramos de ~600 bloques a lo largo del borde, distintos por rebanada.
        if (ruido3(rebanada * 0.93, 0.5, s / 600.0, 3) < -0.25) return Integer.MIN_VALUE;
        int techo = maxY - MARGEN_TECHO;
        return (int) Math.floor(techo - 40 - 120 * (0.5 + 0.5 * ruido3(rebanada * 0.37, 7.5, s / 180.0, 4)));
    }

    /**
     * Altura de la superficie en la columna (el sólido más alto), exacta: se
     * baja de a un bloque desde la cima posible (la de la pared o la meseta).
     * Para el LOD y la franja vertical.
     */
    public static int alturaColumna(double d, double s, double superficie, int minY, int maxY) {
        int desde = (int) Math.ceil(superficie);
        if (d >= TRANSICION) {
            desde = Math.max(desde, cimaPared((int) Math.floor(d / ESCALON), s, maxY));
        }
        for (int y = Math.min(maxY - 1, desde); y >= minY; y--) {
            if (densidad(d, s, y, superficie - y, superficie, minY, maxY) > 0) return y;
        }
        return minY - 1;
    }

    // ------------------------------------------------------------------ ruido propio (sin semilla)

    /** Distancia (en celdas) al borde más cercano entre celdas de Voronoi (F2 - F1, mitad). */
    static double distanciaABordeDeCelda(double u, double v) {
        int iu = (int) Math.floor(u), iv = (int) Math.floor(v);
        double f1 = Double.MAX_VALUE, f2 = Double.MAX_VALUE;
        for (int a = -1; a <= 1; a++) {
            for (int b = -1; b <= 1; b++) {
                int cu = iu + a, cv = iv + b;
                double pu = cu + 0.15 + 0.7 * hash01(cu, cv, 0, 11);
                double pv = cv + 0.15 + 0.7 * hash01(cu, cv, 0, 23);
                double dist = Math.hypot(u - pu, v - pv);
                if (dist < f1) {
                    f2 = f1;
                    f1 = dist;
                } else if (dist < f2) {
                    f2 = dist;
                }
            }
        }
        return (f2 - f1) / 2;
    }

    /** Ruido de valores 3D suave en [-1, 1]. */
    static double ruido3(double x, double y, double z, int canal) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y), iz = (int) Math.floor(z);
        double fx = suave(x - ix), fy = suave(y - iy), fz = suave(z - iz);
        double v000 = hash(ix, iy, iz, canal), v100 = hash(ix + 1, iy, iz, canal);
        double v010 = hash(ix, iy + 1, iz, canal), v110 = hash(ix + 1, iy + 1, iz, canal);
        double v001 = hash(ix, iy, iz + 1, canal), v101 = hash(ix + 1, iy, iz + 1, canal);
        double v011 = hash(ix, iy + 1, iz + 1, canal), v111 = hash(ix + 1, iy + 1, iz + 1, canal);
        double a = lerp(fx, v000, v100), b = lerp(fx, v010, v110);
        double c = lerp(fx, v001, v101), e = lerp(fx, v011, v111);
        return lerp(fz, lerp(fy, a, b), lerp(fy, c, e));
    }

    private static double suave(double t) {
        return t * t * (3 - 2 * t);
    }

    private static double lerp(double t, double a, double b) {
        return a + (b - a) * t;
    }

    private static double hash(int x, int y, int z, int canal) {
        return hash01(x, y, z, canal) * 2 - 1;
    }

    private static double hash01(int x, int y, int z, int canal) {
        long h = x * 0x9E3779B97F4A7C15L ^ y * 0xC2B2AE3D27D4EB4FL ^ z * 0x165667B19E3779F9L ^ canal * 0xD6E8FEB86659FD93L;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }
}
