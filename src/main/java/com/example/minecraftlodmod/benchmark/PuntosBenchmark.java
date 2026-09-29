package com.example.minecraftlodmod.benchmark;

import java.util.List;

/**
 * El mundo de benchmark y sus puntos representativos (sección 9). Datos
 * puros.
 *
 * El benchmark es un MUNDO aparte ({@link #NOMBRE_MUNDO}) con seed fija,
 * no una dimensión dentro del mundo del jugador: se puede calibrar desde el
 * menú principal sin tener un mundo propio, y la generación vanilla con
 * seed fija ya es determinista (terreno, estructuras y árboles iguales en
 * cualquier máquina), sin necesitar un generador propio ni mixins. Si
 * cambia el algoritmo de LOD, el cache del mundo se invalida solo por
 * {@code GeneradorLocal.VERSION_ALGORITMO}.
 *
 * Coordenadas elegidas analizando el relieve REAL de la seed 12345 en
 * Minecraft 1.21.1 (60×60 chunks alrededor del spawn, leídos de los
 * {@code .mlod} del propio mod), no con {@code /locate biome}: ese comando
 * reporta biomas 3D a la altura de búsqueda y ubica "picos" sobre océanos.
 * Por chunk: montaña = mayor altura media (~189); llanura = desvío de
 * altura 0.4 a y≈66 sin agua ni follaje; bosque = 251/256 columnas con
 * follaje en superficie; cueva = mayor volumen de aire bajo la superficie,
 * concentrado en y≈-45. Todos a menos de 500 bloques del spawn. Si cambia
 * el generador vanilla (otra versión de Minecraft), hay que re-ubicarlos.
 */
public final class PuntosBenchmark {

    public static final long SEED = 12345L;
    public static final String NOMBRE_MUNDO = "minecraftlodmod-benchmark";

    /** Dónde se para la cámara en cada punto. */
    public enum Tipo {
        /** Sobre el terreno, en la altura del heightmap. */
        SUPERFICIE,
        /** Dentro de una cueva: el primer hueco de aire cerca de {@link Punto#yReferencia()}. */
        CUEVA
    }

    /**
     * @param yReferencia solo para CUEVA: altura desde donde buscar aire
     * @param yaw         hacia dónde mira (grados, convención de Minecraft)
     * @param pitch       0 = horizonte, donde más pesa el LOD
     */
    public record Punto(String nombre, int x, int z, Tipo tipo, int yReferencia, float yaw, float pitch) {
    }

    public static final List<Punto> PUNTOS = List.of(
            new Punto("llanura", 344, 360, Tipo.SUPERFICIE, 0, 0f, 0f),
            new Punto("bosque", -56, -424, Tipo.SUPERFICIE, 0, 90f, 0f),
            new Punto("montaña", 440, -104, Tipo.SUPERFICIE, 0, 45f, 5f),
            new Punto("cueva", 456, -296, Tipo.CUEVA, -45, 180f, 0f)
    );

    private PuntosBenchmark() {
    }
}
