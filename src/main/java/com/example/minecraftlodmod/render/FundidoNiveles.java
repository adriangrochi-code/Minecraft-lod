package com.example.minecraftlodmod.render;

/**
 * Fundido entre niveles de detalle (sección 6 del documento de arquitectura),
 * parte pura: cuando una zona cambia de nivel, la malla vieja y la nueva se
 * dibujan juntas un momento con tramado complementario (shader
 * {@code lod_textura}, uniform {@code Fundido}) y el cambio no salta a la
 * vista. La lógica de qué malla espera a cuál vive en {@link RenderLod}.
 */
public final class FundidoNiveles {

    /** Duración del cruce: lo bastante corto para no ver dos niveles a la vez, lo bastante largo para no ver el salto. */
    public static final long DURACION_NANOS = 400_000_000L;
    /**
     * Cuánto espera dibujada una malla que salió del plan a que estén listas
     * las que la reemplazan: si el armado tarda más, se va igual (mejor un
     * hueco breve que dos niveles superpuestos para siempre).
     */
    public static final long ESPERA_MAXIMA_NANOS = 3_000_000_000L;
    /** Mallas salientes a la vez: cada una es un dibujo más por cuadro (sección 6: 20-30). */
    public static final int MAXIMO_SALIENTES = 32;

    private FundidoNiveles() {
    }

    /**
     * Fracción visible de una malla que entra (de 0 a 1 en {@link #DURACION_NANOS});
     * la que sale usa {@code 1 - fraccion}. {@code inicio} 0 = sin fundido (entera).
     */
    public static float fraccionEntrada(long ahora, long inicio) {
        if (inicio == 0) {
            return 1f;
        }
        return (float) Math.max(0, Math.min(1, (ahora - inicio) / (double) DURACION_NANOS));
    }

    /** ¿Se tocan los cuadrados [x, x+lado) en X y Z de dos mallas? */
    public static boolean solapan(double x1, double z1, double lado1, double x2, double z2, double lado2) {
        return x1 < x2 + lado2 && x2 < x1 + lado1 && z1 < z2 + lado2 && z2 < z1 + lado1;
    }
}
