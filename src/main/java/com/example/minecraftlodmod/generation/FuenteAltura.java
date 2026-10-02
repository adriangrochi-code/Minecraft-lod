package com.example.minecraftlodmod.generation;

import net.minecraft.server.level.ServerLevel;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Atajo para el LOD aproximado ({@code docs/tierra-real/04-integracion-lod.md},
 * punto 2): un tipo de mundo que conoce la altura de su superficie sin buscarla
 * en la densidad (Tierra real: los datos de elevación) la da directo, una
 * consulta por columna en vez de las decenas de evaluaciones de densidad de
 * {@link GeneradorAproximado}. Debe dar lo mismo que el generador.
 *
 * Los tipos de mundo se enganchan con {@link #registrar}; {@code generation/}
 * no los conoce.
 */
public interface FuenteAltura {

    /** y del bloque sólido más alto de la columna (x, z), igual que lo genera el mundo. Thread-safe. */
    int altura(int x, int z);

    /**
     * Si la columna es una superficie simple (sólido abajo, aire arriba): ahí la
     * franja vertical de {@code cubico/} puede recortar cerca de la superficie.
     * Con grietas, túneles o voladizos (el borde de la Tierra plana) da false y
     * la columna se genera entera.
     */
    default boolean simple(int x, int z) {
        return true;
    }

    /** Hasta qué y llega el agua en la columna (llena y &lt; esto), o {@link Integer#MIN_VALUE} si no hay. */
    default int nivelAgua(int x, int z) {
        return Integer.MIN_VALUE;
    }

    List<Function<ServerLevel, FuenteAltura>> RESOLVEDORES = new CopyOnWriteArrayList<>();

    /** Registra quién sabe dar la fuente de un nivel (devuelve {@code null} si no es suyo). */
    static void registrar(Function<ServerLevel, FuenteAltura> resolvedor) {
        RESOLVEDORES.add(resolvedor);
    }

    /** La fuente del nivel, o {@code null} si ningún tipo de mundo la da (se busca en la densidad). */
    static FuenteAltura de(ServerLevel nivel) {
        if (Boolean.getBoolean("minecraftlodmod.sinAtajoAltura")) {
            return null; // para medir contra la búsqueda en la densidad
        }
        for (Function<ServerLevel, FuenteAltura> r : RESOLVEDORES) {
            FuenteAltura f = r.apply(nivel);
            if (f != null) return f;
        }
        return null;
    }
}
