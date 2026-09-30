package com.example.minecraftlodmod.generation;

import com.example.minecraftlodmod.core.SuperVoxel;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Niveles de LOD más grandes que una sección (lógica pura), para que el
 * horizonte de varios km cueste poco guardar y dibujar.
 *
 * Los niveles 0-4 viven por sección (grilla de 16>>nivel). Desde
 * {@link #NIVEL_MIN} cada nodo es SIEMPRE una grilla de {@link #LADO}³
 * vóxeles de 2^nivel bloques, que cubre un cubo de 16·2^nivel bloques
 * (como Voxy): nivel 5 = una región entera (512 bloques, vóxeles de 32),
 * nivel 10 = 16 384 bloques (vóxeles de 1024, para el horizonte de decenas
 * de km). Los vóxeles más altos que el mundo no son un problema: su relleno
 * ({@link SuperVoxel#relleno()}) pone la superficie a la altura real.
 *
 * Derivación jerárquica (sección 2): el nivel 5 sale del nivel 4 de las
 * 32³ secciones que cubre (1 vóxel por sección), y cada nivel siguiente de
 * sus 2×2×2 hijos — siempre con {@link HierarchicalReducer}, así el color de
 * superficie, la luz y el estado de bloque se conservan igual que abajo.
 *
 * Coordenadas de nodo grande: (nodoX, nodoY, nodoZ) = esquina / tamaño del
 * nodo. El nodo se guarda en la región que contiene su esquina, con la
 * clave de {@link SectionExtractor#claveNodo} (nivel ≥ 5, X/Z locales 0 y
 * la Y de nodo en lugar de la Y de sección).
 */
public final class NivelesGrandes {

    public static final int NIVEL_MIN = SectionExtractor.NIVELES; // 5
    /** Hasta 10: el lado de la tesela (16 384 bloques) todavía entra en los shorts del vértice compacto. */
    public static final int NIVEL_MAX = 10;
    public static final int LADO = 16;

    private NivelesGrandes() {
    }

    /** Acceso a nodos ya guardados (lo implementa quien tiene el store). */
    public interface Acceso {
        /** Grilla de un nodo por sección (nivel 0-4, lado 16>>nivel), o null si no existe. */
        SuperVoxel[] seccion(int nivel, int seccionX, int seccionY, int seccionZ);

        /** Grilla 16³ de un nodo grande, o null si no existe. */
        SuperVoxel[] grande(int nivel, int nodoX, int nodoY, int nodoZ);

        void guardarGrande(int nivel, int nodoX, int nodoY, int nodoZ, SuperVoxel[] grilla);

        /**
         * true si el chunk tiene LOD por sección (real o aproximado chunk por
         * chunk): ahí una sección que falta es aire de verdad.
         */
        default boolean chunkConDatos(int chunkX, int chunkZ) {
            return true;
        }

        /**
         * Vóxel de sección (nivel 4) del horizonte aproximado por región
         * ({@link TerrenoAproximado#seccionDe}), o null si esa zona no se aproximó.
         */
        default SuperVoxel seccionAproximada(int seccionX, int seccionY, int seccionZ) {
            return null;
        }
    }

    /** Lado del nodo de ese nivel, en bloques. */
    public static int ladoEnBloques(int nivel) {
        return LADO << nivel;
    }

    /** Lado del nodo de ese nivel, en secciones (= chunks en X/Z). */
    public static int ladoEnSecciones(int nivel) {
        return 1 << nivel;
    }

    /** Nodo de ese nivel que contiene a la sección/chunk (una coordenada). */
    public static int nodoDe(int nivel, int seccion) {
        return Math.floorDiv(seccion, ladoEnSecciones(nivel));
    }

    /** Clave de guardado, en la región de la esquina del nodo. */
    public static long clave(int nivel, int nodoX, int nodoY, int nodoZ) {
        int lado = ladoEnSecciones(nivel);
        return SectionExtractor.claveNodo(nivel, nodoX * lado, nodoY, nodoZ * lado);
    }

    /** Coordenada de región (X o Z) donde se guarda el nodo. */
    public static int regionDe(int nivel, int nodo) {
        return SectionExtractor.regionDe(nodo * ladoEnSecciones(nivel));
    }

    /**
     * Reconstruye los nodos grandes afectados por los chunks indicados, de
     * {@link #NIVEL_MIN} a {@link #NIVEL_MAX}.
     *
     * @param chunksSucios chunks recién generados, empaquetados con {@link #empaquetar}
     * @param minSeccion   primera sección Y de la dimensión
     * @param maxSeccion   una más que la última sección Y
     * @return cantidad de nodos guardados
     */
    public static int actualizar(Set<Long> chunksSucios, int minSeccion, int maxSeccion, Acceso acceso) {
        Set<Long> sucios = new HashSet<>();
        for (long chunk : chunksSucios) {
            sucios.add(empaquetar(nodoDe(NIVEL_MIN, x(chunk)), nodoDe(NIVEL_MIN, z(chunk))));
        }
        int guardados = 0;
        for (int nivel = NIVEL_MIN; nivel <= NIVEL_MAX; nivel++) {
            int primerY = Math.floorDiv(minSeccion, ladoEnSecciones(nivel));
            int ultimoY = Math.floorDiv(maxSeccion - 1, ladoEnSecciones(nivel));
            Set<Long> padres = new HashSet<>();
            for (long nodo : sucios) {
                for (int nodoY = primerY; nodoY <= ultimoY; nodoY++) {
                    SuperVoxel[] grilla = nivel == NIVEL_MIN
                            ? desdeSecciones(x(nodo), nodoY, z(nodo), minSeccion, maxSeccion, acceso)
                            : desdeHijos(nivel, x(nodo), nodoY, z(nodo), acceso);
                    if (grilla != null) {
                        acceso.guardarGrande(nivel, x(nodo), nodoY, z(nodo), grilla);
                        guardados++;
                    }
                }
                padres.add(empaquetar(Math.floorDiv(x(nodo), 2), Math.floorDiv(z(nodo), 2)));
            }
            sucios = padres;
        }
        return guardados;
    }

    /**
     * Nivel 5: 32³ secciones, un vóxel de nivel 4 por sección. Sección
     * ausente = aire, salvo en chunks sin LOD por sección: ahí se usa el
     * horizonte aproximado por región, si lo hay.
     */
    private static SuperVoxel[] desdeSecciones(int nodoX, int nodoY, int nodoZ, int minSeccion, int maxSeccion,
                                               Acceso acceso) {
        int lado = LADO * 2;
        SuperVoxel[] entrada = new SuperVoxel[lado * lado * lado];
        Arrays.fill(entrada, AIRE);
        boolean alguno = false;
        int baseX = nodoX * lado, baseY = nodoY * lado, baseZ = nodoZ * lado;
        for (int dx = 0; dx < lado; dx++) {
            for (int dz = 0; dz < lado; dz++) {
                boolean conDatos = acceso.chunkConDatos(baseX + dx, baseZ + dz);
                for (int dy = 0; dy < lado; dy++) {
                    int seccionY = baseY + dy;
                    if (seccionY < minSeccion || seccionY >= maxSeccion) {
                        continue;
                    }
                    SuperVoxel v;
                    if (conDatos) {
                        SuperVoxel[] nodo = acceso.seccion(NIVEL_MIN - 1, baseX + dx, seccionY, baseZ + dz);
                        v = nodo == null ? null : nodo[0];
                    } else {
                        v = acceso.seccionAproximada(baseX + dx, seccionY, baseZ + dz);
                    }
                    if (v != null && v.material() != SuperVoxel.Material.AIRE) {
                        entrada[(dx * lado + dy) * lado + dz] = v;
                        alguno = true;
                    }
                }
            }
        }
        return alguno ? HierarchicalReducer.reducir(entrada, lado) : null;
    }

    /** Nivel > 5: 2×2×2 hijos de 16³ forman una grilla de 32³ que se reduce a 16³. */
    private static SuperVoxel[] desdeHijos(int nivel, int nodoX, int nodoY, int nodoZ, Acceso acceso) {
        int lado = LADO * 2;
        SuperVoxel[] entrada = new SuperVoxel[lado * lado * lado];
        Arrays.fill(entrada, AIRE);
        boolean alguno = false;
        for (int hx = 0; hx < 2; hx++) {
            for (int hy = 0; hy < 2; hy++) {
                for (int hz = 0; hz < 2; hz++) {
                    SuperVoxel[] hijo = acceso.grande(nivel - 1, nodoX * 2 + hx, nodoY * 2 + hy, nodoZ * 2 + hz);
                    if (hijo == null) {
                        continue;
                    }
                    alguno = true;
                    for (int x = 0; x < LADO; x++) {
                        for (int y = 0; y < LADO; y++) {
                            System.arraycopy(hijo, (x * LADO + y) * LADO, entrada,
                                    ((hx * LADO + x) * lado + hy * LADO + y) * lado + hz * LADO, LADO);
                        }
                    }
                }
            }
        }
        return alguno ? HierarchicalReducer.reducir(entrada, lado) : null;
    }

    private static final SuperVoxel AIRE =
            new SuperVoxel((byte) 0, (byte) 0, (byte) 0, (byte) 0, SuperVoxel.Material.AIRE, (byte) 0);

    public static long empaquetar(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public static int x(long empaquetado) {
        return (int) (empaquetado >> 32);
    }

    public static int z(long empaquetado) {
        return (int) empaquetado;
    }
}
