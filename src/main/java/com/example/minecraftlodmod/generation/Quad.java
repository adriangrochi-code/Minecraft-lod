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
        SuperVoxel voxelRepresentativo
) {
    public enum Eje {X, Y, Z}
}
