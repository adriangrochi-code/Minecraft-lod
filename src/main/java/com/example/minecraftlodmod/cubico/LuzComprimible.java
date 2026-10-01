package com.example.minecraftlodmod.cubico;

/** Capa de luz ({@code DataLayer}) que se puede guardar comprimida ({@link SeccionesComprimidas}). */
public interface LuzComprimible {

    /** Comprime los datos si tiene; devuelve los bytes que ocupa ahora, o -1 si no había nada que comprimir. */
    int minecraftlodmod$comprimir();

    boolean minecraftlodmod$estaComprimida();
}
