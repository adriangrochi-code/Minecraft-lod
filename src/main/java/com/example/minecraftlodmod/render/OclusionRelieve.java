package com.example.minecraftlodmod.render;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Oclusión por relieve ("horizon culling"), lógica pura: qué piezas del
 * plan quedan completamente escondidas detrás de montañas y no hace falta
 * armarlas ni dibujarlas.
 *
 * Es el raycasting grueso de la sección 25 en versión barata y compatible
 * (sin GPU ni GL propio): en vez de un rayo por píxel, un "horizonte" por
 * dirección. Las columnas del relieve (32×32 bloques, del nivel 5 de
 * {@code NivelesGrandes}) se recorren de la más cercana a la más lejana y
 * cada una sube el ángulo de elevación del horizonte en las direcciones que
 * cubre COMPLETAS. Una pieza está oculta si, en todas las direcciones que
 * toca, su punto más alto queda por debajo del horizonte formado por
 * relieve estrictamente más cercano que ella.
 *
 * Conservador en las dos puntas (ante la duda, se dibuja):
 *  - lo que tapa usa el SUELO de la columna (base del vóxel sólido más alto,
 *    hasta 32 bloques por debajo del terreno real) medido desde su borde MÁS
 *    LEJANO (el ángulo más bajo posible);
 *  - lo tapado usa el TOPE de las columnas que toca (suelo + 32) medido
 *    desde su borde MÁS CERCANO (el ángulo más alto posible); sin datos,
 *    nunca se oculta.
 * Cerca de la cámara ({@link #RADIO_SIN_OCLUSORES}) no hay oclusores
 * (árboles, aleros), y bajo tierra no se oculta nada (bocas de cuevas).
 */
public final class OclusionRelieve {

    /** Bloques por columna de relieve (vóxel de nivel 5). */
    public static final int COLUMNA = 32;
    /** Columnas por lado de una región de relieve (nodo de nivel 5: 512 bloques). */
    public static final int COLUMNAS_POR_REGION = 16;
    public static final int REGION = COLUMNA * COLUMNAS_POR_REGION;
    /** Direcciones del horizonte (~0.18° c/u). */
    static final int SECTORES = 2048;
    /** El relieve más cerca que esto de la cámara no tapa nada. */
    public static final double RADIO_SIN_OCLUSORES = 64;
    /** Holgura angular: lo que asoma apenas por encima del horizonte se dibuja. */
    static final double MARGEN_RADIANES = 0.002;

    private OclusionRelieve() {
    }

    /** Relieve por región de 512 bloques; lo implementa el render leyendo el nivel 5 guardado. */
    public interface Relieve {
        /**
         * @return suelo ocluyente de cada columna (índice columnaX * 16 + columnaZ,
         *         NaN = columna sin terreno), o null si la región no tiene datos
         */
        float[] suelos(int regionX, int regionZ);
    }

    /** Rectángulo horizontal de una pieza del plan, en bloques. */
    public record Pieza(double minX, double minZ, double lado) {
    }

    /**
     * @param radioOclusores solo el relieve hasta esta distancia tapa (más lejos
     *                       aporta poco y cuesta mucho)
     * @return por pieza, true si está oculta
     */
    public static boolean[] ocultas(double camX, double camY, double camZ, List<Pieza> piezas, Relieve relieve,
                                    double radioOclusores) {
        boolean[] ocultas = new boolean[piezas.size()];
        if (bajoTierra(camX, camY, camZ, relieve)) {
            return ocultas;
        }
        List<double[]> oclusores = oclusores(camX, camY, camZ, relieve, radioOclusores);
        oclusores.sort((a, b) -> Double.compare(a[0], b[0])); // por distancia lejana

        Integer[] orden = new Integer[piezas.size()];
        double[] cercania = new double[piezas.size()];
        for (int i = 0; i < orden.length; i++) {
            orden[i] = i;
            Pieza p = piezas.get(i);
            cercania[i] = distanciaMinima(camX, camZ, p.minX(), p.minZ(), p.minX() + p.lado(), p.minZ() + p.lado());
        }
        Arrays.sort(orden, (a, b) -> Double.compare(cercania[a], cercania[b]));

        double[] horizonte = new double[SECTORES];
        Arrays.fill(horizonte, Double.NEGATIVE_INFINITY);
        int siguiente = 0;
        for (int i : orden) {
            // Solo el relieve estrictamente más cercano que la pieza forma su horizonte.
            while (siguiente < oclusores.size() && oclusores.get(siguiente)[0] < cercania[i]) {
                double[] o = oclusores.get(siguiente++);
                subirHorizonte(horizonte, (int) o[2], (int) o[3], o[1]);
            }
            ocultas[i] = oculta(camX, camY, camZ, piezas.get(i), cercania[i], relieve, horizonte);
        }
        return ocultas;
    }

    private static boolean bajoTierra(double camX, double camY, double camZ, Relieve relieve) {
        int columnaX = Math.floorDiv((int) Math.floor(camX), COLUMNA);
        int columnaZ = Math.floorDiv((int) Math.floor(camZ), COLUMNA);
        float[] suelos = relieve.suelos(Math.floorDiv(columnaX, COLUMNAS_POR_REGION),
                Math.floorDiv(columnaZ, COLUMNAS_POR_REGION));
        if (suelos == null) {
            return true; // sin datos donde está la cámara: no arriesgar
        }
        float suelo = suelos[Math.floorMod(columnaX, COLUMNAS_POR_REGION) * COLUMNAS_POR_REGION
                + Math.floorMod(columnaZ, COLUMNAS_POR_REGION)];
        return !Float.isNaN(suelo) && camY < suelo;
    }

    /** Cada oclusor: {distancia lejana, elevación, sector desde, sector hasta} (solo sectores cubiertos enteros). */
    private static List<double[]> oclusores(double camX, double camY, double camZ, Relieve relieve, double radio) {
        List<double[]> resultado = new ArrayList<>();
        int desdeRegionX = Math.floorDiv((int) Math.floor(camX - radio), REGION);
        int hastaRegionX = Math.floorDiv((int) Math.floor(camX + radio), REGION);
        int desdeRegionZ = Math.floorDiv((int) Math.floor(camZ - radio), REGION);
        int hastaRegionZ = Math.floorDiv((int) Math.floor(camZ + radio), REGION);
        for (int rx = desdeRegionX; rx <= hastaRegionX; rx++) {
            for (int rz = desdeRegionZ; rz <= hastaRegionZ; rz++) {
                float[] suelos = relieve.suelos(rx, rz);
                if (suelos == null) {
                    continue;
                }
                for (int cx = 0; cx < COLUMNAS_POR_REGION; cx++) {
                    for (int cz = 0; cz < COLUMNAS_POR_REGION; cz++) {
                        float suelo = suelos[cx * COLUMNAS_POR_REGION + cz];
                        if (Float.isNaN(suelo)) {
                            continue;
                        }
                        double x0 = (double) rx * REGION + cx * COLUMNA, z0 = (double) rz * REGION + cz * COLUMNA;
                        double cerca = distanciaMinima(camX, camZ, x0, z0, x0 + COLUMNA, z0 + COLUMNA);
                        if (cerca < RADIO_SIN_OCLUSORES || cerca > radio) {
                            continue;
                        }
                        double lejos = distanciaMaxima(camX, camZ, x0, z0, x0 + COLUMNA, z0 + COLUMNA);
                        int[] sectores = sectoresCubiertos(camX, camZ, x0, z0, COLUMNA, true);
                        if (sectores == null) {
                            continue;
                        }
                        double elevacion = Math.atan2(suelo - camY, lejos);
                        resultado.add(new double[]{lejos, elevacion, sectores[0], sectores[1]});
                    }
                }
            }
        }
        return resultado;
    }

    private static void subirHorizonte(double[] horizonte, int desde, int hasta, double elevacion) {
        for (int s = desde; ; s = (s + 1) % SECTORES) {
            if (elevacion > horizonte[s]) {
                horizonte[s] = elevacion;
            }
            if (s == hasta) {
                return;
            }
        }
    }

    private static boolean oculta(double camX, double camY, double camZ, Pieza p, double cercania, Relieve relieve,
                                  double[] horizonte) {
        if (cercania < RADIO_SIN_OCLUSORES) {
            return false;
        }
        float tope = topeDe(p, relieve);
        if (Float.isNaN(tope)) {
            return false;
        }
        int[] sectores = sectoresCubiertos(camX, camZ, p.minX(), p.minZ(), p.lado(), false);
        if (sectores == null) {
            return false;
        }
        double elevacion = Math.atan2(tope - camY, cercania) + MARGEN_RADIANES;
        for (int s = sectores[0]; ; s = (s + 1) % SECTORES) {
            if (!(horizonte[s] > elevacion)) {
                return false;
            }
            if (s == sectores[1]) {
                return true;
            }
        }
    }

    /**
     * Tope (suelo + una columna) más alto entre las columnas que toca la
     * pieza; NaN si alguna de sus regiones no tiene datos. Columnas sin
     * terreno no suben el tope.
     */
    private static float topeDe(Pieza p, Relieve relieve) {
        int desdeX = Math.floorDiv((int) Math.floor(p.minX()), COLUMNA);
        int hastaX = Math.floorDiv((int) Math.ceil(p.minX() + p.lado()) - 1, COLUMNA);
        int desdeZ = Math.floorDiv((int) Math.floor(p.minZ()), COLUMNA);
        int hastaZ = Math.floorDiv((int) Math.ceil(p.minZ() + p.lado()) - 1, COLUMNA);
        float maximo = Float.NEGATIVE_INFINITY;
        for (int rx = Math.floorDiv(desdeX, COLUMNAS_POR_REGION); rx <= Math.floorDiv(hastaX, COLUMNAS_POR_REGION); rx++) {
            for (int rz = Math.floorDiv(desdeZ, COLUMNAS_POR_REGION); rz <= Math.floorDiv(hastaZ, COLUMNAS_POR_REGION); rz++) {
                float[] suelos = relieve.suelos(rx, rz);
                if (suelos == null) {
                    return Float.NaN;
                }
                int x0 = Math.max(desdeX, rx * COLUMNAS_POR_REGION), x1 = Math.min(hastaX, rx * COLUMNAS_POR_REGION + 15);
                int z0 = Math.max(desdeZ, rz * COLUMNAS_POR_REGION), z1 = Math.min(hastaZ, rz * COLUMNAS_POR_REGION + 15);
                for (int cx = x0; cx <= x1; cx++) {
                    for (int cz = z0; cz <= z1; cz++) {
                        float suelo = suelos[(cx - rx * COLUMNAS_POR_REGION) * COLUMNAS_POR_REGION
                                + (cz - rz * COLUMNAS_POR_REGION)];
                        if (!Float.isNaN(suelo)) {
                            maximo = Math.max(maximo, suelo + COLUMNA);
                        }
                    }
                }
            }
        }
        return maximo == Float.NEGATIVE_INFINITY ? Float.NaN : maximo;
    }

    /**
     * Sectores del horizonte que ocupa un cuadrado visto desde la cámara:
     * {desde, hasta} (con vuelta por 0). {@code soloEnteros}: solo los que el
     * cuadrado cubre por completo (para lo que tapa); si no, todos los que
     * toca (para lo tapado). null si la cámara está dentro o no cubre ninguno.
     */
    static int[] sectoresCubiertos(double camX, double camZ, double x0, double z0, double lado, boolean soloEnteros) {
        if (camX >= x0 && camX <= x0 + lado && camZ >= z0 && camZ <= z0 + lado) {
            return null;
        }
        double[] angulos = {
                Math.atan2(z0 - camZ, x0 - camX), Math.atan2(z0 - camZ, x0 + lado - camX),
                Math.atan2(z0 + lado - camZ, x0 - camX), Math.atan2(z0 + lado - camZ, x0 + lado - camX)};
        // El cuadrado no contiene la cámara: sus esquinas caben en menos de 180°.
        // Se toma como referencia la primera y se miden las demás relativas a ella.
        double base = angulos[0], min = 0, max = 0;
        for (double a : angulos) {
            double relativo = Math.IEEEremainder(a - base, 2 * Math.PI);
            min = Math.min(min, relativo);
            max = Math.max(max, relativo);
        }
        double porSector = 2 * Math.PI / SECTORES;
        double desde = (base + min) / porSector, hasta = (base + max) / porSector;
        long d, h;
        if (soloEnteros) {
            d = (long) Math.ceil(desde);
            h = (long) Math.floor(hasta) - 1;
        } else {
            d = (long) Math.floor(desde);
            h = (long) Math.floor(hasta);
        }
        if (h < d) {
            return null;
        }
        if (h - d >= SECTORES) {
            return new int[]{0, SECTORES - 1};
        }
        return new int[]{(int) Math.floorMod(d, (long) SECTORES), (int) Math.floorMod(h, (long) SECTORES)};
    }

    static double distanciaMinima(double px, double pz, double x0, double z0, double x1, double z1) {
        double dx = Math.max(0, Math.max(x0 - px, px - x1));
        double dz = Math.max(0, Math.max(z0 - pz, pz - z1));
        return Math.hypot(dx, dz);
    }

    static double distanciaMaxima(double px, double pz, double x0, double z0, double x1, double z1) {
        double dx = Math.max(Math.abs(x0 - px), Math.abs(x1 - px));
        double dz = Math.max(Math.abs(z0 - pz), Math.abs(z1 - pz));
        return Math.hypot(dx, dz);
    }
}
