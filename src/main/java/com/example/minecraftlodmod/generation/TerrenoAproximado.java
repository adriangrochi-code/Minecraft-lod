package com.example.minecraftlodmod.generation;

import com.example.minecraftlodmod.core.SuperVoxel;

/**
 * Generación aproximada, parte pura (sección 25 punto 7, idea de los "rough
 * generators" de FarPlaneTwo): arma los nodos de LOD de un chunk que NUNCA se
 * generó a partir de unas pocas columnas de terreno (altura de superficie +
 * vóxel de superficie), sin correr la generación vanilla. La parte que lee el
 * generador de Minecraft está en {@link GeneradorAproximado}.
 *
 * Por chunk se muestrean 2×2 columnas (una por cuarto de 8×8 bloques) y se
 * arma directamente el nivel 3 (vóxeles de 8 bloques, grilla 2³ por
 * sección); el nivel 4 sale reduciéndolo como cualquier otro. Los niveles
 * 0-2 no existen para lo aproximado: si el render los pide, usa el 3.
 *
 * Se guardan en claves propias ({@link #nivelGuardado}), separadas de los
 * datos reales: cuando el chunk se genera de verdad, lo real tiene prioridad
 * y lo aproximado queda sin usar.
 */
public final class TerrenoAproximado {

    /** Niveles que se generan de forma aproximada. */
    public static final int NIVEL_MIN = 3, NIVEL_MAX = 4;
    /** Desplazamiento de nivel en la clave de nodo: nivel 3 aproximado se guarda como 11, el 4 como 12. */
    static final int DESPLAZAMIENTO_NIVEL = 8;
    /** Nivel reservado en la clave para la marca "este chunk tiene LOD aproximado". */
    public static final int NIVEL_MARCA = 14;
    /** Columnas por lado de un chunk (cada una representa 8×8 bloques). */
    public static final int COLUMNAS = 2;
    static final int LADO_VOXEL = 8;

    private TerrenoAproximado() {
    }

    /** Nivel con el que se guarda en {@code SectionExtractor.claveNodo} el nivel aproximado dado (3 o 4). */
    public static int nivelGuardado(int nivel) {
        if (nivel < NIVEL_MIN || nivel > NIVEL_MAX) {
            throw new IllegalArgumentException("Nivel aproximado fuera de rango: " + nivel);
        }
        return nivel + DESPLAZAMIENTO_NIVEL;
    }

    /**
     * Columna muestreada: altura del bloque sólido más alto, y los vóxeles
     * que la representan (superficie, subsuelo, y el agua si queda bajo el
     * nivel del mar).
     */
    public record Columna(int altura, SuperVoxel superficie, SuperVoxel subsuelo) {
    }

    /**
     * Grilla de nivel 3 (lado 2, indexada (x*2+y)*2+z) de una sección.
     *
     * Un vóxel de 8 bloques es sólido si la columna entra en él, con el
     * relleno ({@link SuperVoxel#relleno()}) a la altura real de la columna:
     * el sólido más alto de la columna lleva la superficie, los de abajo el
     * subsuelo. Si el mar le llega más alto que el suelo, es agua hasta el
     * nivel del mar.
     *
     * @param columnas 2×2 columnas, índice x*2+z
     * @param agua     vóxel de agua (con su color de bioma)
     * @return la grilla, o null si la sección queda toda de aire
     */
    public static SuperVoxel[] grillaNivel3(int seccionY, Columna[] columnas, SuperVoxel agua, int nivelMar) {
        SuperVoxel[] grilla = new SuperVoxel[8];
        boolean alguno = false;
        for (int x = 0; x < COLUMNAS; x++) {
            for (int z = 0; z < COLUMNAS; z++) {
                Columna c = columnas[x * COLUMNAS + z];
                for (int y = 0; y < 2; y++) {
                    SuperVoxel v = voxelDeColumna(c, seccionY * 16 + y * LADO_VOXEL, LADO_VOXEL, agua, nivelMar);
                    grilla[(x * 2 + y) * 2 + z] = v;
                    alguno |= v.material() != SuperVoxel.Material.AIRE;
                }
            }
        }
        return alguno ? grilla : null;
    }

    /**
     * El vóxel de {@code lado} bloques que empieza en {@code y0} de una
     * columna: sólido si la columna entra en él (superficie si es el de más
     * arriba), agua si el mar llega más alto que el suelo, aire si no.
     */
    static SuperVoxel voxelDeColumna(Columna c, int y0, int lado, SuperVoxel agua, int nivelMar) {
        int solido = Math.max(0, Math.min(lado, c.altura() + 1 - y0));
        int mar = Math.max(0, Math.min(lado, nivelMar - y0));
        if (solido > 0 && solido >= mar) {
            boolean superficie = c.altura() < y0 + lado;
            return (superficie ? c.superficie() : c.subsuelo()).conRelleno(relleno(solido, lado));
        }
        if (mar > 0) {
            return agua.conRelleno(relleno(mar, lado));
        }
        return AIRE;
    }

    /** Relleno de un vóxel de {@link #LADO_VOXEL} con {@code bloques} llenos desde abajo. */
    static int relleno(int bloques) {
        return relleno(bloques, LADO_VOXEL);
    }

    static int relleno(int bloques, int lado) {
        return Math.round(bloques * SuperVoxel.LLENO / (float) lado);
    }

    // ------------------------------------------------------------------ horizonte por región

    /**
     * Más lejos que esto (chunks) el horizonte aproximado deja de ir chunk por
     * chunk: se muestrea una columna por vóxel de un nodo grande entero (ver
     * {@link #nivelDeRegion}). Ahí el render ya usa vóxeles de 32 bloques o
     * más (3 px a 8 km en 1080p) y el costo baja cientos de veces.
     */
    public static final int CHUNKS_POR_REGION_DESDE = 512;
    /** Desde acá, nodos de nivel 6 (vóxeles de 64); desde el siguiente, de nivel 7 (128). */
    public static final int CHUNKS_NIVEL6_DESDE = 1024, CHUNKS_NIVEL7_DESDE = 4096;
    public static final int NIVEL_REGION_MIN = 5, NIVEL_REGION_MAX = 7;
    /** Nivel de clave de los nodos grandes aproximados (el nivel real va en la Y de la clave). */
    static final int NIVEL_CLAVE_GRANDE = 13;
    /** Columnas por lado de un nodo grande: una por vóxel. */
    public static final int COLUMNAS_GRANDE = NivelesGrandes.LADO;

    /**
     * Con qué nivel se aproxima la zona del nodo de nivel 5 dado, según la
     * distancia del CENTRO de su nodo de nivel 7, 6 o 5 al jugador: el más
     * grande que ya queda lejos. Así la partición es un árbol (un nodo se hace
     * entero en un solo nivel) y nunca quedan zonas a medias.
     *
     * @return {nivel, nodoX, nodoZ} del nodo a generar, o null si es de la
     *         zona chunk por chunk
     */
    public static int[] nivelDeRegion(int nodo5X, int nodo5Z, int chunkJugadorX, int chunkJugadorZ) {
        int[][] umbrales = {{7, CHUNKS_NIVEL7_DESDE}, {6, CHUNKS_NIVEL6_DESDE}, {5, CHUNKS_POR_REGION_DESDE}};
        for (int[] u : umbrales) {
            int nivel = u[0];
            int factor = 1 << (nivel - NIVEL_REGION_MIN);
            int nx = Math.floorDiv(nodo5X, factor), nz = Math.floorDiv(nodo5Z, factor);
            double mitad = NivelesGrandes.ladoEnSecciones(nivel) / 2.0;
            double centroX = nx * (double) NivelesGrandes.ladoEnSecciones(nivel) + mitad;
            double centroZ = nz * (double) NivelesGrandes.ladoEnSecciones(nivel) + mitad;
            if (Math.hypot(centroX - chunkJugadorX, centroZ - chunkJugadorZ) >= u[1]) {
                return new int[]{nivel, nx, nz};
            }
        }
        return null;
    }

    /**
     * Grilla 16³ de un nodo grande aproximado (vóxeles de 2^nivel bloques) en
     * la banda vertical {@code nodoY}, con las mismas reglas que el nivel 3.
     *
     * @param columnas 16×16 columnas, índice x*16+z
     * @return la grilla, o null si la banda queda toda de aire
     */
    public static SuperVoxel[] grillaGrande(int nivel, int nodoY, Columna[] columnas, SuperVoxel agua, int nivelMar) {
        int lado = COLUMNAS_GRANDE, tamano = 1 << nivel;
        SuperVoxel[] grilla = new SuperVoxel[lado * lado * lado];
        boolean alguno = false;
        for (int x = 0; x < lado; x++) {
            for (int z = 0; z < lado; z++) {
                Columna c = columnas[x * lado + z];
                for (int y = 0; y < lado; y++) {
                    SuperVoxel v = voxelDeColumna(c, (nodoY * lado + y) * tamano, tamano, agua, nivelMar);
                    grilla[(x * lado + y) * lado + z] = v;
                    alguno |= v.material() != SuperVoxel.Material.AIRE;
                }
            }
        }
        return alguno ? grilla : null;
    }

    /**
     * El vóxel de sección (16 bloques, nivel 4) en (seccionX, seccionY,
     * seccionZ) sacado de la grilla del nodo grande aproximado que la
     * contiene: mismo vóxel, con el relleno partido según a qué altura del
     * vóxel grande cae la sección. Así los niveles grandes reales (5 en
     * adelante) se arman igual con datos por región.
     */
    public static SuperVoxel seccionDe(SuperVoxel[] grilla, int nivel, int seccionX, int seccionY, int seccionZ) {
        int k = 1 << (nivel - 4); // secciones por lado de vóxel
        int porNodo = NivelesGrandes.LADO * k;
        int x = Math.floorMod(seccionX, porNodo) / k;
        int y = Math.floorMod(seccionY, porNodo) / k;
        int z = Math.floorMod(seccionZ, porNodo) / k;
        SuperVoxel v = grilla[(x * NivelesGrandes.LADO + y) * NivelesGrandes.LADO + z];
        if (v.material() == SuperVoxel.Material.AIRE) {
            return v;
        }
        double llenas = v.relleno() / (double) SuperVoxel.LLENO * k; // en secciones
        double enEsta = Math.max(0, Math.min(1, llenas - Math.floorMod(seccionY, k)));
        return enEsta <= 0 ? AIRE : v.conRelleno((int) Math.round(enEsta * SuperVoxel.LLENO));
    }

    /** Clave de un nodo grande aproximado (en la región de la esquina del nodo, como {@link NivelesGrandes#clave}). */
    public static long claveGrande(int nivel, int nodoX, int nodoY, int nodoZ) {
        int lado = NivelesGrandes.ladoEnSecciones(nivel);
        return SectionExtractor.claveNodo(NIVEL_CLAVE_GRANDE, nodoX * lado,
                ((nivel - NIVEL_REGION_MIN) << 8) | (nodoY & 0xFF), nodoZ * lado);
    }

    /** Marca "este nodo grande ya se aproximó" (aunque haya quedado todo de aire). */
    public static long claveMarcaGrande(int nivel, int nodoX, int nodoZ) {
        int lado = NivelesGrandes.ladoEnSecciones(nivel);
        return SectionExtractor.claveNodo(NIVEL_MARCA, nodoX * lado, 0x800 | (nivel - NIVEL_REGION_MIN), nodoZ * lado);
    }

    /** Nivel 4 (un vóxel por sección) a partir del 3, con la misma reducción que los datos reales. */
    public static SuperVoxel[] grillaNivel4(SuperVoxel[] nivel3) {
        return HierarchicalReducer.reducir(nivel3, 2);
    }

    static final SuperVoxel AIRE =
            new SuperVoxel((byte) 0, (byte) 0, (byte) 0, (byte) 0, SuperVoxel.Material.AIRE, (byte) 0);
}
