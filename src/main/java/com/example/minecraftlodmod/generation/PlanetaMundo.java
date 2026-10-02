package com.example.minecraftlodmod.generation;

import net.minecraft.world.level.Level;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Un tipo de mundo que es un planeta a escala (el mod Farlands, "Tierra
 * real") le da al LOD su radio para la curvatura y el horizonte real, y el
 * relieve lejano que el horizonte tiene que dejar asomar. Se engancha con
 * {@link #registrar}, como {@link FuenteAltura}; el LOD no conoce al mod.
 */
public interface PlanetaMundo {

    /** Radio del planeta en bloques en ese nivel, o 0 si el nivel no es de este tipo de mundo. */
    double radioBloques(Level nivel);

    /** Relieve lejano para el horizonte real, en bloques (solo se pide si {@link #radioBloques} &gt; 0). */
    double relieveHorizonte(Level nivel);

    List<PlanetaMundo> REGISTRADOS = new CopyOnWriteArrayList<>();

    static void registrar(PlanetaMundo planeta) {
        REGISTRADOS.add(planeta);
    }

    /** El planeta del nivel (radio &gt; 0), o {@code null}. */
    static PlanetaMundo de(Level nivel) {
        if (nivel == null) return null;
        for (PlanetaMundo p : REGISTRADOS) {
            if (p.radioBloques(nivel) > 0) return p;
        }
        return null;
    }
}
