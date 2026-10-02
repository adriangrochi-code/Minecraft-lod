package com.example.minecraftlodmod.render;

import java.util.Arrays;
import java.util.List;

/**
 * Lógica pura del atlas propio del LOD (sin Minecraft; lo arma
 * {@link PaletaTexturas}): teselas cuadradas del mismo tamaño, potencia de 2,
 * en una grilla, así cada nivel de mipmap de una tesela queda dentro de su
 * propio cuadrado y la textura repetida por bloque no se mezcla con la vecina
 * al alejarse.
 *
 * Dos ideas de Voxy (sección 25 del documento de arquitectura, solo la idea,
 * sin su código):
 * <ul>
 *   <li><b>Modelos horneados:</b> lo que no es un cubo simple (escaleras,
 *       losas, cercos, muros, cactus, faroles) se dibuja por software desde
 *       arriba y desde un costado con sus quads reales ({@link #hornear}), y
 *       el LOD usa esa imagen en vez de la textura suelta de una cara.</li>
 *   <li><b>Follaje con profundidad:</b> los huecos de las hojas no quedan del
 *       color plano sino oscurecidos ({@link #rellenarHuecos}), como el
 *       interior de un árbol visto de cerca: el bosque lejano tiene textura y
 *       volumen en vez de ser un bloque verde liso.</li>
 * </ul>
 * Los píxeles se manejan como enteros ABGR (R en el byte bajo), el orden de
 * {@code NativeImage#getPixelRGBA}.
 */
public final class AtlasLod {

    /** Tamaño mínimo y máximo de tesela: el LOD se ve de lejos, más de 32 píxeles no aporta. */
    static final int TESELA_MIN = 16, TESELA_MAX = 32;

    /** Brillo de los huecos del follaje respecto del promedio de la hoja. */
    static final float HUECO_FOLLAJE = 0.45f;

    /**
     * Fracción de píxeles transparentes para considerar una textura "follaje":
     * con pocos es un borde suelto; con muchos es vidrio o rejas, donde el
     * hueco oscuro se vería como vidrio sucio.
     */
    static final float FOLLAJE_MIN = 0.04f, FOLLAJE_MAX = 0.6f;

    private AtlasLod() {
    }

    /** Desde dónde se mira el modelo al hornearlo. */
    public enum Vista {
        /** Desde +Y: columnas = x, filas = z (como el shader repite la cara de arriba). */
        ARRIBA,
        /** Desde -Z (norte): columnas = x, filas = 1 - y (arriba de la imagen = arriba del bloque). */
        COSTADO
    }

    /**
     * Un quad del modelo: 4 vértices (x, y, z en bloques, 0..1 el cubo), sus
     * UV relativas al sprite (0..1) y los píxeles del sprite (primer cuadro).
     */
    public record QuadModelo(float[] posiciones, float[] uv, int[] pixeles, int ancho, int alto) {
    }

    /** Tamaño de tesela para el sprite más ancho en uso: potencia de 2 entre {@link #TESELA_MIN} y {@link #TESELA_MAX}. */
    public static int tamanoTesela(int anchoMaximo) {
        int t = TESELA_MIN;
        while (t < anchoMaximo && t < TESELA_MAX) {
            t <<= 1;
        }
        return t;
    }

    /** Niveles de mipmap posibles sin que una tesela se mezcle con la vecina (hasta 1 píxel por tesela). */
    public static int nivelesMipmap(int tesela) {
        return Integer.numberOfTrailingZeros(tesela);
    }

    /** Teselas por fila para que entren {@code cantidad}: atlas lo más cuadrado posible. */
    public static int teselasPorFila(int cantidad) {
        int porFila = 1;
        while ((long) porFila * porFila < cantidad) {
            porFila <<= 1;
        }
        return porFila;
    }

    /**
     * Lleva un sprite a una tesela de {@code t}×{@code t}: promedio por
     * bloques si es más grande (packs HD), vecino más cercano si es más chico.
     * Solo mira el primer cuadro (arriba) si el sprite es una tira animada.
     */
    public static int[] escalar(int[] pixeles, int ancho, int alto, int t) {
        int[] tesela = new int[t * t];
        int lado = Math.min(ancho, alto);
        for (int fila = 0; fila < t; fila++) {
            for (int col = 0; col < t; col++) {
                if (lado <= t) {
                    tesela[fila * t + col] = pixeles[(fila * lado / t) * ancho + col * lado / t];
                    continue;
                }
                int x0 = col * lado / t, x1 = (col + 1) * lado / t;
                int y0 = fila * lado / t, y1 = (fila + 1) * lado / t;
                long r = 0, g = 0, b = 0, a = 0, n = 0;
                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        int p = pixeles[y * ancho + x];
                        int alfa = (p >>> 24) & 0xFF;
                        r += (long) (p & 0xFF) * alfa;
                        g += (long) ((p >> 8) & 0xFF) * alfa;
                        b += (long) ((p >> 16) & 0xFF) * alfa;
                        a += alfa;
                        n++;
                    }
                }
                tesela[fila * t + col] = a == 0 ? 0 : abgr((int) (r / a), (int) (g / a), (int) (b / a), (int) (a / n));
            }
        }
        return tesela;
    }

    /** ¿Tiene huecos de follaje (hojas)? Ver {@link #FOLLAJE_MIN}. */
    public static boolean esFollaje(int[] tesela) {
        int huecos = 0;
        for (int p : tesela) {
            if (((p >>> 24) & 0xFF) < 128) {
                huecos++;
            }
        }
        float fraccion = huecos / (float) tesela.length;
        return fraccion >= FOLLAJE_MIN && fraccion <= FOLLAJE_MAX;
    }

    /**
     * Tesela opaca lista para el atlas: los huecos (alfa &lt; 128) toman el
     * promedio de la textura, oscurecido si es follaje. Opaca entera, así los
     * mipmaps no oscurecen los bordes ni el shader tiene que mirar el alfa.
     */
    public static int[] rellenarHuecos(int[] tesela, int promedioRgb, boolean follaje) {
        float f = follaje ? HUECO_FOLLAJE : 1f;
        int hueco = abgr(Math.round(((promedioRgb >> 16) & 0xFF) * f), Math.round(((promedioRgb >> 8) & 0xFF) * f),
                Math.round((promedioRgb & 0xFF) * f), 255);
        int[] opaca = new int[tesela.length];
        for (int i = 0; i < tesela.length; i++) {
            int p = tesela[i];
            opaca[i] = ((p >>> 24) & 0xFF) < 128 ? hueco : p | 0xFF000000;
        }
        return opaca;
    }

    /**
     * Tesela de silueta recortable (plantas en cruz): lo opaco queda opaco y los huecos
     * con el promedio y alfa 1 (casi transparente, el shader los descarta). Alfa 1 y no 0:
     * los mipmaps de vanilla ignoran el color de lo que tiene alfa 0 y los bordes se
     * oscurecerían.
     */
    public static int[] recortable(int[] tesela, int promedioRgb) {
        int hueco = abgr((promedioRgb >> 16) & 0xFF, (promedioRgb >> 8) & 0xFF, promedioRgb & 0xFF, 1);
        int[] r = new int[tesela.length];
        for (int i = 0; i < tesela.length; i++) {
            int p = tesela[i];
            r[i] = ((p >>> 24) & 0xFF) < 128 ? hueco : p | 0xFF000000;
        }
        return r;
    }

    /** Promedio 0xRRGGBB de una tesela ya opaca (el que usa el shader para sacar el detalle). */
    public static int promedioTesela(int[] tesela) {
        long r = 0, g = 0, b = 0;
        for (int p : tesela) {
            r += p & 0xFF;
            g += (p >> 8) & 0xFF;
            b += (p >> 16) & 0xFF;
        }
        int n = Math.max(1, tesela.length);
        return (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
    }

    /**
     * ¿El quad cubre entera la cara del cubo en ese eje (0 X, 1 Y, 2 Z) y
     * sentido? Un modelo con las 6 caras así (y sin quads sueltos) es un cubo
     * simple: se usa su textura tal cual, sin hornear.
     */
    public static boolean caraCompleta(float[] posiciones, int eje, boolean positivo) {
        float plano = positivo ? 1f : 0f;
        int a = (eje + 1) % 3, b = (eje + 2) % 3;
        float minA = Float.MAX_VALUE, maxA = -Float.MAX_VALUE, minB = Float.MAX_VALUE, maxB = -Float.MAX_VALUE;
        for (int v = 0; v < 4; v++) {
            if (Math.abs(posiciones[v * 3 + eje] - plano) > 1e-3f) {
                return false;
            }
            minA = Math.min(minA, posiciones[v * 3 + a]);
            maxA = Math.max(maxA, posiciones[v * 3 + a]);
            minB = Math.min(minB, posiciones[v * 3 + b]);
            maxB = Math.max(maxB, posiciones[v * 3 + b]);
        }
        return minA < 1e-3f && maxA > 1 - 1e-3f && minB < 1e-3f && maxB > 1 - 1e-3f;
    }

    /**
     * Dibuja por software el modelo visto desde {@code vista}, en proyección
     * ortográfica sobre el cubo 0..1, con prueba de profundidad y recorte por
     * alfa (como el render cutout de vanilla). Sin sombras por cara: el
     * shader del LOD las aplica por la cara del vóxel.
     *
     * @return la tesela de {@code t}×{@code t} (alfa 0 donde no hay nada)
     */
    public static int[] hornear(List<QuadModelo> quads, Vista vista, int t) {
        int[] tesela = new int[t * t];
        float[] profundidad = new float[t * t];
        Arrays.fill(profundidad, Float.NEGATIVE_INFINITY);
        float[] a = new float[4], b = new float[4], p = new float[4];
        for (QuadModelo q : quads) {
            float[] pos = q.posiciones();
            for (int v = 0; v < 4; v++) {
                float x = pos[v * 3], y = pos[v * 3 + 1], z = pos[v * 3 + 2];
                a[v] = x;
                if (vista == Vista.ARRIBA) {
                    b[v] = z;
                    p[v] = y;
                } else {
                    b[v] = 1 - y;
                    p[v] = -z;
                }
            }
            triangulo(q, 0, 1, 2, a, b, p, tesela, profundidad, t);
            triangulo(q, 0, 2, 3, a, b, p, tesela, profundidad, t);
        }
        return tesela;
    }

    private static void triangulo(QuadModelo q, int i0, int i1, int i2, float[] a, float[] b, float[] p,
                                  int[] tesela, float[] profundidad, int t) {
        float area = (a[i1] - a[i0]) * (b[i2] - b[i0]) - (b[i1] - b[i0]) * (a[i2] - a[i0]);
        if (Math.abs(area) < 1e-6f) {
            return; // de canto desde esta vista
        }
        float minA = Math.min(a[i0], Math.min(a[i1], a[i2])), maxA = Math.max(a[i0], Math.max(a[i1], a[i2]));
        float minB = Math.min(b[i0], Math.min(b[i1], b[i2])), maxB = Math.max(b[i0], Math.max(b[i1], b[i2]));
        int c0 = Math.max(0, (int) Math.floor(minA * t)), c1 = Math.min(t - 1, (int) Math.ceil(maxA * t));
        int f0 = Math.max(0, (int) Math.floor(minB * t)), f1 = Math.min(t - 1, (int) Math.ceil(maxB * t));
        float[] uv = q.uv();
        float eps = -1e-4f;
        for (int fila = f0; fila <= f1; fila++) {
            float pb = (fila + 0.5f) / t;
            for (int col = c0; col <= c1; col++) {
                float pa = (col + 0.5f) / t;
                float w0 = ((a[i1] - pa) * (b[i2] - pb) - (b[i1] - pb) * (a[i2] - pa)) / area;
                float w1 = ((a[i2] - pa) * (b[i0] - pb) - (b[i2] - pb) * (a[i0] - pa)) / area;
                float w2 = 1 - w0 - w1;
                if (w0 < eps || w1 < eps || w2 < eps) {
                    continue;
                }
                float prof = w0 * p[i0] + w1 * p[i1] + w2 * p[i2];
                int i = fila * t + col;
                if (prof <= profundidad[i]) {
                    continue;
                }
                float u = w0 * uv[i0 * 2] + w1 * uv[i1 * 2] + w2 * uv[i2 * 2];
                float v = w0 * uv[i0 * 2 + 1] + w1 * uv[i1 * 2 + 1] + w2 * uv[i2 * 2 + 1];
                int lado = Math.min(q.ancho(), q.alto());
                int sx = Math.min(q.ancho() - 1, Math.max(0, (int) Math.floor(u * q.ancho())));
                int sy = Math.min(lado - 1, Math.max(0, (int) Math.floor(v * lado)));
                int pixel = q.pixeles()[sy * q.ancho() + sx];
                if (((pixel >>> 24) & 0xFF) < 128) {
                    continue; // recorte por alfa: se ve lo de atrás
                }
                tesela[i] = pixel;
                profundidad[i] = prof;
            }
        }
    }

    /** ¿Quedó algo dibujado? */
    public static boolean vacia(int[] tesela) {
        for (int p : tesela) {
            if (((p >>> 24) & 0xFF) >= 128) {
                return false;
            }
        }
        return true;
    }

    private static int abgr(int r, int g, int b, int a) {
        return (a & 0xFF) << 24 | (b & 0xFF) << 16 | (g & 0xFF) << 8 | (r & 0xFF);
    }
}
