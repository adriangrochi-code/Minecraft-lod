package com.example.minecraftlodmod.network;

import com.example.minecraftlodmod.generation.SectionExtractor;

/**
 * Identifica un nodo en la red: nivel de LOD + sección vanilla que cubre.
 * La dimensión no viaja: es siempre la del jugador que pide (ver
 * {@link ProtocoloLod}).
 */
public record NodoId(int nivel, int seccionX, int seccionY, int seccionZ) {

    public NodoId {
        if (nivel < 0 || nivel >= SectionExtractor.NIVELES) {
            throw new IllegalArgumentException("Nivel de LOD fuera de rango: " + nivel);
        }
    }

    /** Clave de este nodo dentro de su región ({@code RegionFileStore}). */
    public long claveNodo() {
        return SectionExtractor.claveNodo(nivel, seccionX, seccionY, seccionZ);
    }

    public int regionX() {
        return SectionExtractor.regionDe(seccionX);
    }

    public int regionZ() {
        return SectionExtractor.regionDe(seccionZ);
    }
}
