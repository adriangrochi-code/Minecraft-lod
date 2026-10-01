package com.example.minecraftlodmod.core;

/**
 * Curvatura del horizonte: el terreno LOD baja con la distancia como la
 * superficie de un planeta de radio R (1 bloque = 1 m; la Tierra 1:1 es
 * 6 371 km), y el "horizonte real" es hasta dónde se ve esa superficie
 * desde la altura de los ojos. Lógica pura, sin Minecraft.
 *
 * La bajada arranca en el borde de lo que dibuja vanilla (que no se curva),
 * con pendiente cero ahí, para que no quede un escalón entre los dos:
 * {@code bajada(d) = (d - inicio)² / 2R}. Es la aproximación parabólica de
 * la esfera ({@code R - sqrt(R² - d²)}), exacta a menos de 0,1% mientras la
 * distancia sea menor que el 5% del radio; con radios chicos (planeta de
 * juguete) sigue bajando en vez de dar la vuelta, que es lo que se quiere
 * para esconder lo que queda detrás.
 */
public final class HorizonteCurvo {

    /** Radio de la Tierra en bloques (metros). */
    public static final double RADIO_TIERRA = 6_371_000;

    /**
     * Altura sobre el nivel del mar del relieve lejano que se tiene en cuenta
     * para el horizonte real: una colina de esta altura detrás del horizonte
     * geométrico todavía asoma. Las montañas más altas que eso se cortan
     * antes de tiempo, pero sin este margen el radio saldría igual a la vista
     * sobre el mar (unos 5 km a nivel del suelo con la Tierra 1:1).
     */
    public static final double RELIEVE = 32;

    private HorizonteCurvo() {
    }

    /**
     * @param distancia distancia horizontal a la cámara, en bloques
     * @param inicio    distancia desde la que se curva (borde de vanilla)
     * @param radio     radio del planeta, en bloques
     * @return cuánto baja el terreno a esa distancia, en bloques
     */
    public static double bajada(double distancia, double inicio, double radio) {
        double d = distancia - inicio;
        return d <= 0 ? 0 : d * d / (2 * radio);
    }

    /** Coeficiente {@code 1 / 2R} que usa el shader para la misma bajada. */
    public static float coeficiente(double radio) {
        return (float) (1 / (2 * radio));
    }

    /** Distancia al horizonte geométrico desde una altura sobre la superficie: {@code sqrt(2Rh + h²)}. */
    public static double distanciaHorizonte(double altura, double radio) {
        double h = Math.max(0, altura);
        return Math.sqrt(2 * radio * h + h * h);
    }

    /**
     * Hasta dónde se ve: el horizonte desde los ojos más el tramo por detrás
     * del horizonte en el que un relieve de {@link #RELIEVE} bloques todavía asoma.
     *
     * @param alturaOjos altura de los ojos sobre el nivel del mar, en bloques (mínimo 2)
     */
    public static double alcanceVisible(double alturaOjos, double radio) {
        return distanciaHorizonte(Math.max(2, alturaOjos), radio) + distanciaHorizonte(RELIEVE, radio);
    }

    /** {@link #alcanceVisible} en chunks, entre {@code minimo} y {@code maximo}. */
    public static int radioChunks(double alturaOjos, double radio, int minimo, int maximo) {
        return radioChunks(alturaOjos, radio, RELIEVE, minimo, maximo);
    }

    /**
     * Como {@link #radioChunks(double, double, int, int)} con otro relieve
     * lejano (Tierra real: montañas de km detrás del horizonte).
     */
    public static int radioChunks(double alturaOjos, double radio, double relieve, int minimo, int maximo) {
        double alcance = distanciaHorizonte(Math.max(2, alturaOjos), radio) + distanciaHorizonte(relieve, radio);
        double chunks = Math.ceil(alcance / 16);
        return (int) Math.max(minimo, Math.min(maximo, chunks));
    }
}
