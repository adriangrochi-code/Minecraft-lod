package com.example.minecraftlodmod.generation;

import com.example.minecraftlodmod.core.SuperVoxel;

/**
 * Una cara rectangular ya fusionada por el greedy mesher: representa N
 * caras de supervóxeles coplanares y del mismo material/color combinadas
 * en un solo quad, para minimizar vértices (y por lo tanto draw calls /
 * carga de GPU — ver sección 6 del documento de arquitectura).
 *
 * Coordenadas en espacio local del nodo (no de mundo — la traducción a
 * mundo la hace quien arma el buffer final en render/).
 */
public record Quad(
        int x, int y, int z,       // esquina inferior del quad
        int ancho, int alto,       // dimensiones del quad, en unidades de supervóxel
        Eje eje,                   // a qué eje es perpendicular esta cara
        boolean positivo,          // si mira hacia el lado positivo o negativo del eje
        SuperVoxel voxelRepresentativo,
        /*
         * Oclusión ambiental de las 4 esquinas, 2 bits c/u (0 = rincón cerrado,
         * 3 = sin oclusión), en el orden (u0,v0) (u1,v0) (u0,v1) (u1,v1) de la
         * descomposición de GreedyMesher; ver {@link #oclusionEn}.
         */
        int oclusion
) {
    public enum Eje {X, Y, Z}

    /** Las 4 esquinas sin oclusión. */
    public static final int SIN_OCLUSION = 0xFF;

    public Quad(int x, int y, int z, int ancho, int alto, Eje eje, boolean positivo,
                SuperVoxel voxelRepresentativo) {
        this(x, y, z, ancho, alto, eje, positivo, voxelRepresentativo, SIN_OCLUSION);
    }

    /**
     * @param uMax false = esquina en u0, true = en u1
     * @param vMax false = esquina en v0, true = en v1
     * @return 0 (rincón cerrado) a 3 (sin oclusión)
     */
    public int oclusionEn(boolean uMax, boolean vMax) {
        return (oclusion >> ((uMax ? 2 : 0) + (vMax ? 4 : 0))) & 3;
    }
}
