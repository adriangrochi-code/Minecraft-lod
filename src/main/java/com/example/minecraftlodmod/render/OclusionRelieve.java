package com.example.minecraftlodmod.render;

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
    /** Direcciones del horizonte (~0,18° c/u en promedio, ver {@link #pseudoAngulo}). */
    static final int SECTORES = 2048;
    /** El relieve más cerca que esto de la cámara no tapa nada. */
    public static final double RADIO_SIN_OCLUSORES = 64;
    /** Holgura angular: lo que asoma apenas por encima del horizonte se dibuja. */
    static final double MARGEN_RADIANES = 0.002;
    private static final double TAN_MARGEN = Math.tan(MARGEN_RADIANES);
    /** Una vuelta en unidades de {@link #pseudoAngulo}. */
    static final double VUELTA = 4;

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
        // Arreglos primitivos e índices ordenados sin boxing: con decenas de miles de columnas,
        // ordenar una lista de double[] (y un Integer[] de piezas) era la mitad del plan.
        Oclusores oclusores = oclusores(camX, camY, camZ, relieve, radioOclusores);
        int[] ordenOclusores = oclusores.porDistancia();

        int n = piezas.size();
        double[] cercania = new double[n];
        for (int i = 0; i < n; i++) {
            Pieza p = piezas.get(i);
            cercania[i] = distanciaMinima(camX, camZ, p.minX(), p.minZ(), p.minX() + p.lado(), p.minZ() + p.lado());
        }
        int[] orden = ordenPor(cercania, n);

        double[] horizonte = new double[SECTORES];
        Arrays.fill(horizonte, Double.NEGATIVE_INFINITY);
        int siguiente = 0;
        for (int i : orden) {
            // Solo el relieve estrictamente más cercano que la pieza forma su horizonte.
            while (siguiente < oclusores.cantidad && oclusores.lejos[ordenOclusores[siguiente]] < cercania[i]) {
                int k = ordenOclusores[siguiente++];
                subirHorizonte(horizonte, oclusores.desde[k], oclusores.hasta[k], oclusores.elevacion[k]);
            }
            ocultas[i] = oculta(camX, camY, camZ, piezas.get(i), cercania[i], relieve, horizonte);
        }
        return ocultas;
    }

    /** Índices 0..n-1 ordenados por su clave, de menor a mayor (sin boxing). */
    static int[] ordenPor(double[] claves, int n) {
        int[] indices = new int[n];
        for (int i = 0; i < n; i++) {
            indices[i] = i;
        }
        it.unimi.dsi.fastutil.ints.IntArrays.quickSort(indices, (a, b) -> Double.compare(claves[a], claves[b]));
        return indices;
    }

    /** Lo que tapa: distancia lejana, elevación (pendiente) y sectores cubiertos enteros, en arreglos paralelos. */
    private static final class Oclusores {
        double[] lejos = new double[1024], elevacion = new double[1024];
        int[] desde = new int[1024], hasta = new int[1024];
        int cantidad;

        /**
         * Índices por distancia lejana: la distancia como float (sus bits ordenan igual que el valor,
         * es positiva) y el índice en un long, ordenados como primitivos. Con un empate por el
         * redondeo, uno puede quedar después de otro apenas más lejano: la pieza que está entre los
         * dos no lo cuenta (se oculta menos, nunca de más).
         */
        int[] porDistancia() {
            long[] claves = new long[cantidad];
            for (int i = 0; i < cantidad; i++) {
                claves[i] = (long) Float.floatToRawIntBits((float) lejos[i]) << 32 | i;
            }
            Arrays.sort(claves);
            int[] indices = new int[cantidad];
            for (int i = 0; i < cantidad; i++) {
                indices[i] = (int) claves[i];
            }
            return indices;
        }

        void agregar(double l, double e, int d, int h) {
            if (cantidad == lejos.length) {
                int nuevo = cantidad * 2;
                lejos = Arrays.copyOf(lejos, nuevo);
                elevacion = Arrays.copyOf(elevacion, nuevo);
                desde = Arrays.copyOf(desde, nuevo);
                hasta = Arrays.copyOf(hasta, nuevo);
            }
            lejos[cantidad] = l;
            elevacion[cantidad] = e;
            desde[cantidad] = d;
            hasta[cantidad] = h;
            cantidad++;
        }
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

    /** Las columnas que tapan (solo con los sectores que cubren enteros). */
    private static Oclusores oclusores(double camX, double camY, double camZ, Relieve relieve, double radio) {
        Oclusores resultado = new Oclusores();
        int desdeRegionX = Math.floorDiv((int) Math.floor(camX - radio), REGION);
        int hastaRegionX = Math.floorDiv((int) Math.floor(camX + radio), REGION);
        int desdeRegionZ = Math.floorDiv((int) Math.floor(camZ - radio), REGION);
        int hastaRegionZ = Math.floorDiv((int) Math.floor(camZ + radio), REGION);
        double minimo2 = RADIO_SIN_OCLUSORES * RADIO_SIN_OCLUSORES, radio2 = radio * radio;
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
                        double dx = Math.max(0, Math.max(x0 - camX, camX - x0 - COLUMNA));
                        double dz = Math.max(0, Math.max(z0 - camZ, camZ - z0 - COLUMNA));
                        double cerca2 = dx * dx + dz * dz;
                        if (cerca2 < minimo2 || cerca2 > radio2) {
                            continue;
                        }
                        long sectores = sectores(camX, camZ, x0, z0, COLUMNA, true);
                        if (sectores < 0) {
                            continue;
                        }
                        double lejos = distanciaMaxima(camX, camZ, x0, z0, x0 + COLUMNA, z0 + COLUMNA);
                        // Pendiente (tangente de la elevación): ordena igual que el ángulo, sin atan2.
                        resultado.agregar(lejos, (suelo - camY) / lejos, (int) (sectores >>> 32), (int) sectores);
                    }
                }
            }
        }
        return resultado;
    }

    private static void subirHorizonte(double[] horizonte, int desde, int hasta, double elevacion) {
        for (int s = desde; ; s = s + 1 == SECTORES ? 0 : s + 1) {
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
        long sectores = sectores(camX, camZ, p.minX(), p.minZ(), p.lado(), false);
        if (sectores < 0) {
            return false;
        }
        int desde = (int) (sectores >>> 32), hasta = (int) sectores;
        // Pendiente con el margen angular sumado: tan(atan(r) + m) = (r + tan m) / (1 - r tan m);
        // pasado los 90° no hay horizonte que la tape.
        double r = (tope - camY) / cercania;
        if (r * TAN_MARGEN >= 1) {
            return false;
        }
        double elevacion = (r + TAN_MARGEN) / (1 - r * TAN_MARGEN);
        for (int s = desde; ; s = s + 1 == SECTORES ? 0 : s + 1) {
            if (!(horizonte[s] > elevacion)) {
                return false;
            }
            if (s == hasta) {
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
        long s = sectores(camX, camZ, x0, z0, lado, soloEnteros);
        return s < 0 ? null : new int[]{(int) (s >>> 32), (int) s};
    }

    /** Como {@link #sectoresCubiertos}, empaquetado (desde en los 32 bits altos) y -1 = ninguno: sin un arreglo por llamada. */
    static long sectores(double camX, double camZ, double x0, double z0, double lado, boolean soloEnteros) {
        if (camX >= x0 && camX <= x0 + lado && camZ >= z0 && camZ <= z0 + lado) {
            return -1;
        }
        double base = pseudoAngulo(x0 - camX, z0 - camZ);
        // El cuadrado no contiene la cámara: sus esquinas caben en menos de media vuelta.
        // Se toma como referencia la primera y se miden las demás relativas a ella.
        double min = 0, max = 0;
        for (int esquina = 1; esquina < 4; esquina++) {
            double a = pseudoAngulo(x0 + (esquina & 1) * lado - camX, z0 + (esquina >> 1) * lado - camZ);
            double relativo = a - base;
            relativo = relativo > VUELTA / 2 ? relativo - VUELTA : relativo < -VUELTA / 2 ? relativo + VUELTA : relativo;
            min = Math.min(min, relativo);
            max = Math.max(max, relativo);
        }
        double porSector = VUELTA / SECTORES;
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
            return -1;
        }
        if (h - d >= SECTORES) {
            return SECTORES - 1;
        }
        return Math.floorMod(d, (long) SECTORES) << 32 | Math.floorMod(h, (long) SECTORES);
    }

    /**
     * Ángulo "de rombo" de la dirección (dx, dz), en [0, {@link #VUELTA}): crece con el
     * ángulo real (misma vuelta, mismo sentido que atan2(dz, dx)) sin calcular atan2, que
     * con las decenas de miles de columnas del relieve era lo más caro del plan. Los
     * sectores quedan de ancho angular algo distinto entre sí (hasta ~1,4×), pero lo que
     * tapa y lo tapado se miden igual, así que el criterio conservador no cambia.
     */
    static double pseudoAngulo(double dx, double dz) {
        double suma = Math.abs(dx) + Math.abs(dz);
        if (suma == 0) {
            return 0;
        }
        if (dz >= 0) {
            return dx >= 0 ? dz / suma : 1 - dx / suma;
        }
        return dx < 0 ? 2 - dz / suma : 3 + dx / suma;
    }

    static double distanciaMinima(double px, double pz, double x0, double z0, double x1, double z1) {
        double dx = Math.max(0, Math.max(x0 - px, px - x1));
        double dz = Math.max(0, Math.max(z0 - pz, pz - z1));
        return Math.sqrt(dx * dx + dz * dz); // hypot es exacto pero varias veces más lento
    }

    static double distanciaMaxima(double px, double pz, double x0, double z0, double x1, double z1) {
        double dx = Math.max(Math.abs(x0 - px), Math.abs(x1 - px));
        double dz = Math.max(Math.abs(z0 - pz), Math.abs(z1 - pz));
        return Math.sqrt(dx * dx + dz * dz);
    }
}
