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
     * Un vóxel de 8 bloques es sólido si la columna cubre al menos su mitad
     * (misma regla de mayoría que {@link HierarchicalReducer}); el sólido más
     * alto de la columna lleva la superficie, los de abajo el subsuelo. Sin
     * sólido y con el nivel del mar cubriendo la mitad, es agua.
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
                    int y0 = seccionY * 16 + y * LADO_VOXEL;
                    SuperVoxel v;
                    if (c.altura() >= y0 + LADO_VOXEL / 2) {
                        boolean superficie = c.altura() < y0 + LADO_VOXEL + LADO_VOXEL / 2;
                        v = superficie ? c.superficie() : c.subsuelo();
                    } else if (nivelMar - 1 >= y0 + LADO_VOXEL / 2) {
                        v = agua;
                    } else {
                        v = AIRE;
                    }
                    grilla[(x * 2 + y) * 2 + z] = v;
                    alguno |= v.material() != SuperVoxel.Material.AIRE;
                }
            }
        }
        return alguno ? grilla : null;
    }

    /** Nivel 4 (un vóxel por sección) a partir del 3, con la misma reducción que los datos reales. */
    public static SuperVoxel[] grillaNivel4(SuperVoxel[] nivel3) {
        return HierarchicalReducer.reducir(nivel3, 2);
    }

    static final SuperVoxel AIRE =
            new SuperVoxel((byte) 0, (byte) 0, (byte) 0, (byte) 0, SuperVoxel.Material.AIRE, (byte) 0);
}
