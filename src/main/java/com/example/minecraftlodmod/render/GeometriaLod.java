package com.example.minecraftlodmod.render;

import com.example.minecraftlodmod.core.SuperVoxel;
import com.example.minecraftlodmod.generation.GreedyMesher;
import com.example.minecraftlodmod.generation.Quad;
import com.example.minecraftlodmod.generation.VertexLightSampler;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Vértices de LOD listos para subir (lógica pura, sin Minecraft): recibe
 * grillas de supervóxeles por sección, las pasa por {@link GreedyMesher} y
 * acumula 4 vértices por quad — posición (float x3, relativa al origen de
 * la celda) y color ARGB ya sombreado.
 *
 * Sombreado, para que el terreno lejano no se vea plano:
 *  - por cara, los mismos factores que usa vanilla para bloques (arriba 1.0,
 *    abajo 0.5, norte/sur 0.8, este/oeste 0.6);
 *  - por vértice, la luz horneada de {@link VertexLightSampler}.
 * La perspectiva atmosférica (sección 17) todavía no se aplica acá: queda
 * para Pista B, cuando se pueda juzgar viéndola.
 *
 * Formato compacto de subida ({@link #escribirCompacto}, idea tomada de
 * Voxy — sección 25 del documento de arquitectura, sin su código): 12
 * bytes por vértice en vez de 36. Las posiciones son bloques enteros
 * relativos a la celda (shorts), y la textura no viaja por vértice: solo
 * el índice del sprite, que el shader resuelve con una tabla (ver
 * {@link #texelesSprite}); la normal sale del índice de cara.
 *
 * Por dirección (sección 25, punto 3): los vértices se escriben agrupados
 * por cara ({@link #escribirCompacto(ByteBuffer, int)}) con el plano
 * mínimo y máximo de cada grupo, para no dibujar los grupos que miran en
 * sentido contrario a la cámara ({@link #caraVisible}). Dentro de un grupo
 * visible, el orden de vértices es antihorario visto desde afuera, así el
 * culling de caras traseras de Minecraft descarta el resto.
 */
public final class GeometriaLod {

    /** Bits de {@code carasOmitidas}: caras laterales en el borde de la sección que no se dibujan. */
    public static final int OMITIR_X_NEG = 1, OMITIR_X_POS = 2, OMITIR_Z_NEG = 4, OMITIR_Z_POS = 8;

    /** Caras (direcciones) posibles: 0 -X, 1 +X, 2 -Y, 3 +Y, 4 -Z, 5 +Z. */
    public static final int CARAS = 6;

    /** Bytes por vértice del formato compacto: 3 shorts de posición + short de sprite + RGBA. */
    public static final int BYTES_COMPACTO = 12;
    /** Texeles RGBA8 por sprite en la tabla que lee el shader. */
    public static final int TEXELES_POR_SPRITE = 3;
    /** Sprites por fila de la tabla (textura de 768 texeles de ancho). */
    public static final int SPRITES_POR_FILA = 256;

    /** Brillo mínimo con luz horneada 0: el terreno a oscuras no queda negro puro. */
    static final float LUZ_MINIMA = 0.25f;

    /**
     * De dónde sale la textura de una cara (la implementa el cliente con
     * el atlas de bloques activo, ver {@code PaletaTexturas}). Mantiene
     * esta clase pura: no conoce sprites ni atlas.
     */
    public interface Texturas {
        /** @return la textura de esa cara del estado de bloque, o null para dibujarla con color plano */
        Cara cara(int idEstado, Quad.Eje eje, boolean positivo);
    }

    /**
     * Textura de una cara: índice del sprite en la tabla del shader (1..65535;
     * su rectángulo en el atlas y su promedio viven ahí), su color promedio,
     * y si el color del vóxel (que ya trae el tinte del bioma) aplica a esta
     * cara. No aplica, por ejemplo, al costado de un bloque de pasto: la
     * tierra no se tiñe, así que ahí manda el promedio de su textura.
     */
    public record Cara(int sprite, int promedioRgb, boolean usaColorDelVoxel) {
    }

    private float[] posiciones = new float[3 * 1024];
    private int[] colores = new int[1024];
    /** Por cara: cantidad de vértices y planos extremos (coordenada sobre su eje, en bloques de la celda). */
    private final int[] verticesPorCara = new int[CARAS];
    private final float[] planoMin = new float[CARAS];
    private final float[] planoMax = new float[CARAS];

    {
        Arrays.fill(planoMin, Float.POSITIVE_INFINITY);
        Arrays.fill(planoMax, Float.NEGATIVE_INFINITY);
    }

    /** Índice de sprite por vértice (0: sin textura). */
    private int[] sprites = new int[1024];
    /** Cara por vértice: 0 -X, 1 +X, 2 -Y, 3 +Y, 4 -Z, 5 +Z. */
    private byte[] caras = new byte[1024];
    private int vertices;
    private Texturas fuenteTexturas;
    private boolean descartarSinLuz;

    /**
     * Descartar caras sin ninguna luz (cielo ni bloque) en sus cuatro
     * esquinas: interiores de cuevas y caras enterradas, invisibles desde
     * afuera. Es el "cave culling" de Distant Horizons; sin esto el LOD de
     * un radio grande dibuja decenas de millones de vértices ocultos.
     */
    public void descartarCarasSinLuz(boolean descartar) {
        this.descartarSinLuz = descartar;
    }

    /** Fuente de texturas para lo que se agregue después; null = todo con color plano. */
    public void usarTexturas(Texturas fuente) {
        this.fuenteTexturas = fuente;
    }

    /**
     * @param grid   grilla de la sección en el nivel elegido, indexada (x*lado+y)*lado+z
     * @param lado   supervóxeles por arista (16 en nivel 0, 1 en nivel 4)
     * @param ox     origen de la sección relativo a la celda, en bloques
     * @param escala bloques por supervóxel (16 / lado)
     * @return quads agregados
     */
    public int agregarSeccion(SuperVoxel[] grid, int lado, float ox, float oy, float oz, float escala) {
        return agregarSeccion(grid, lado, ox, oy, oz, escala, 0);
    }

    /**
     * @param carasOmitidas bits {@code OMITIR_*}: caras en el borde de la
     *                      sección tapadas por el vecino (vanilla u otro chunk
     *                      de LOD). Sin esto cada chunk es una caja cerrada y,
     *                      donde el vecino lo dibuja vanilla, queda a la vista
     *                      su pared subterránea sin luz.
     */
    public int agregarSeccion(SuperVoxel[] grid, int lado, float ox, float oy, float oz, float escala,
                              int carasOmitidas) {
        return agregarSeccion(grid, lado, ox, oy, oz, escala, carasOmitidas, null);
    }

    /**
     * @param vecinos vóxeles de las grillas de al lado del mismo nivel (ver
     *                {@link GreedyMesher.Vecinos}): las caras del borde que
     *                tapan no se generan. null = bordes expuestos.
     */
    public int agregarSeccion(SuperVoxel[] grid, int lado, float ox, float oy, float oz, float escala,
                              int carasOmitidas, GreedyMesher.Vecinos vecinos) {
        int agregados = 0;
        for (Quad q : GreedyMesher.mallar(grid, lado, vecinos)) {
            if (omitida(q, lado, carasOmitidas)) {
                continue;
            }
            if (agregarQuad(grid, lado, q, ox, oy, oz, escala)) {
                agregados++;
            }
        }
        return agregados;
    }

    static boolean omitida(Quad q, int lado, int carasOmitidas) {
        if (carasOmitidas == 0) {
            return false;
        }
        return switch (q.eje()) {
            case X -> q.positivo() ? q.x() == lado - 1 && (carasOmitidas & OMITIR_X_POS) != 0
                    : q.x() == 0 && (carasOmitidas & OMITIR_X_NEG) != 0;
            case Z -> q.positivo() ? q.z() == lado - 1 && (carasOmitidas & OMITIR_Z_POS) != 0
                    : q.z() == 0 && (carasOmitidas & OMITIR_Z_NEG) != 0;
            case Y -> false;
        };
    }

    /** @return false si la cara se descartó (sin luz) */
    private boolean agregarQuad(SuperVoxel[] grid, int lado, Quad q, float ox, float oy, float oz, float escala) {
        // Descomposición (capa, u, v) inversa a GreedyMesher#construirQuad:
        // X -> u=y (alto), v=z (ancho) | Y -> u=x (ancho), v=z (alto) | Z -> u=x (ancho), v=y (alto)
        int capa, u0, v0, largoU, largoV;
        switch (q.eje()) {
            case X -> { capa = q.x(); u0 = q.y(); v0 = q.z(); largoU = q.alto(); largoV = q.ancho(); }
            case Y -> { capa = q.y(); u0 = q.x(); v0 = q.z(); largoU = q.ancho(); largoV = q.alto(); }
            default -> { capa = q.z(); u0 = q.x(); v0 = q.y(); largoU = q.ancho(); largoV = q.alto(); }
        }
        float plano = capa + (q.positivo() ? 1 : 0);
        VertexLightSampler.LuzEsquinas luz = VertexLightSampler.calcular(grid, lado, q);
        if (descartarSinLuz && luz.minMin() == 0 && luz.maxMin() == 0 && luz.minMax() == 0 && luz.maxMax() == 0) {
            return false;
        }
        float sombra = sombraDeCara(q.eje(), q.positivo());
        SuperVoxel v = q.voxelRepresentativo();
        Cara cara = fuenteTexturas == null || v.idEstado() == SuperVoxel.SIN_ESTADO ? null
                : fuenteTexturas.cara(v.idEstado(), q.eje(), q.positivo());
        int rgbBase = cara == null || cara.usaColorDelVoxel()
                ? ((v.r() & 0xFF) << 16) | ((v.g() & 0xFF) << 8) | (v.b() & 0xFF)
                : cara.promedioRgb();

        // Contorno (u0,v0) (u1,v0) (u1,v1) (u0,v1): su normal por la regla de la
        // mano derecha es +X, -Y o +Z según el eje; para las otras tres caras se
        // recorre al revés, así todas quedan antihorarias vistas desde afuera.
        int u1 = u0 + largoU, v1 = v0 + largoV;
        if (antihorarioDirecto(q.eje(), q.positivo())) {
            vertice(q, plano, u0, v0, luz.minMin(), rgbBase, cara, sombra, ox, oy, oz, escala);
            vertice(q, plano, u1, v0, luz.maxMin(), rgbBase, cara, sombra, ox, oy, oz, escala);
            vertice(q, plano, u1, v1, luz.maxMax(), rgbBase, cara, sombra, ox, oy, oz, escala);
            vertice(q, plano, u0, v1, luz.minMax(), rgbBase, cara, sombra, ox, oy, oz, escala);
        } else {
            vertice(q, plano, u0, v1, luz.minMax(), rgbBase, cara, sombra, ox, oy, oz, escala);
            vertice(q, plano, u1, v1, luz.maxMax(), rgbBase, cara, sombra, ox, oy, oz, escala);
            vertice(q, plano, u1, v0, luz.maxMin(), rgbBase, cara, sombra, ox, oy, oz, escala);
            vertice(q, plano, u0, v0, luz.minMin(), rgbBase, cara, sombra, ox, oy, oz, escala);
        }
        return true;
    }

    /** true si el contorno (u0,v0) (u1,v0) (u1,v1) (u0,v1) ya es antihorario visto desde afuera. */
    static boolean antihorarioDirecto(Quad.Eje eje, boolean positivo) {
        return eje == Quad.Eje.Y ? !positivo : positivo;
    }

    private void vertice(Quad q, float plano, int u, int v, int luz, int rgbBase, Cara cara, float sombra,
                         float ox, float oy, float oz, float escala) {
        Quad.Eje eje = q.eje();
        float x, y, z;
        switch (eje) {
            case X -> { x = plano; y = u; z = v; }
            case Y -> { x = u; y = plano; z = v; }
            default -> { x = u; y = v; z = plano; }
        }
        asegurarCapacidad();
        int i = vertices * 3;
        posiciones[i] = ox + x * escala;
        posiciones[i + 1] = oy + y * escala;
        posiciones[i + 2] = oz + z * escala;
        colores[vertices] = color(rgbBase, sombra, luz);
        sprites[vertices] = cara == null ? 0 : cara.sprite();
        int indiceCara = eje.ordinal() * 2 + (q.positivo() ? 1 : 0);
        caras[vertices] = (byte) indiceCara;
        float coordenadaPlano = posiciones[i + eje.ordinal()];
        verticesPorCara[indiceCara]++;
        planoMin[indiceCara] = Math.min(planoMin[indiceCara], coordenadaPlano);
        planoMax[indiceCara] = Math.max(planoMax[indiceCara], coordenadaPlano);
        vertices++;
    }

    static float sombraDeCara(Quad.Eje eje, boolean positivo) {
        return switch (eje) {
            case Y -> positivo ? 1.0f : 0.5f;
            case Z -> 0.8f;
            case X -> 0.6f;
        };
    }

    static int color(SuperVoxel v, float sombra, int luz) {
        return color(((v.r() & 0xFF) << 16) | ((v.g() & 0xFF) << 8) | (v.b() & 0xFF), sombra, luz);
    }

    static int color(int rgb, float sombra, int luz) {
        float factor = sombra * (LUZ_MINIMA + (1 - LUZ_MINIMA) * luz / 15f);
        int r = Math.round(((rgb >> 16) & 0xFF) * factor);
        int g = Math.round(((rgb >> 8) & 0xFF) * factor);
        int b = Math.round((rgb & 0xFF) * factor);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private void asegurarCapacidad() {
        if (vertices == colores.length) {
            colores = Arrays.copyOf(colores, colores.length * 2);
            posiciones = Arrays.copyOf(posiciones, posiciones.length * 2);
            sprites = Arrays.copyOf(sprites, sprites.length * 2);
            caras = Arrays.copyOf(caras, caras.length * 2);
        }
    }

    public int vertices() {
        return vertices;
    }

    public float x(int i) {
        return posiciones[i * 3];
    }

    public float y(int i) {
        return posiciones[i * 3 + 1];
    }

    public float z(int i) {
        return posiciones[i * 3 + 2];
    }

    /** ARGB. */
    public int color(int i) {
        return colores[i];
    }

    /** true si el vértice i lleva textura. */
    public boolean texturizado(int i) {
        return sprites[i] != 0;
    }

    public int sprite(int i) {
        return sprites[i];
    }

    /** 0 -X, 1 +X, 2 -Y, 3 +Y, 4 -Z, 5 +Z. */
    public int cara(int i) {
        return caras[i];
    }

    /**
     * Escribe todos los vértices en el formato compacto
     * ({@link #BYTES_COMPACTO} bytes c/u, little-endian como espera la GPU):
     * x, y, z (short, bloques relativos a la celda), sprite (short sin signo),
     * R, G, B (ya sombreados) y la cara en el byte de alfa.
     *
     * @throws IllegalStateException si una posición no es entera o no entra en un short
     */
    public void escribirCompacto(ByteBuffer destino) {
        escribirCompacto(destino, -1);
    }

    /**
     * Como {@link #escribirCompacto(ByteBuffer)}, solo los vértices de una
     * cara (0-5); -1 = todos.
     */
    public void escribirCompacto(ByteBuffer destino, int soloCara) {
        ByteBuffer b = destino.order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < vertices; i++) {
            if (soloCara >= 0 && caras[i] != soloCara) {
                continue;
            }
            b.putShort(aShort(posiciones[i * 3]));
            b.putShort(aShort(posiciones[i * 3 + 1]));
            b.putShort(aShort(posiciones[i * 3 + 2]));
            b.putShort((short) sprites[i]);
            int c = colores[i];
            b.put((byte) (c >> 16)).put((byte) (c >> 8)).put((byte) c).put(caras[i]);
        }
    }

    public int verticesDeCara(int cara) {
        return verticesPorCara[cara];
    }

    /** Plano más bajo de las caras de ese índice, sobre su eje (bloques de la celda). */
    public float planoMin(int cara) {
        return planoMin[cara];
    }

    public float planoMax(int cara) {
        return planoMax[cara];
    }

    /**
     * true si alguna cara del grupo puede mirar hacia la cámara: una cara +X
     * en el plano p solo se ve con la cámara en x > p (y al revés las -X).
     * Si ni el plano más favorable cumple, el grupo entero se saltea sin
     * mandarlo a la GPU.
     *
     * @param camara coordenada de la cámara sobre el eje de la cara, relativa a la celda
     */
    public static boolean caraVisible(int cara, double camara, float planoMin, float planoMax) {
        return (cara & 1) == 1 ? camara > planoMin : camara < planoMax;
    }

    private static short aShort(float f) {
        int v = (int) f;
        if (v != f || v < Short.MIN_VALUE || v > Short.MAX_VALUE) {
            throw new IllegalStateException("posición fuera del formato compacto: " + f);
        }
        return (short) v;
    }

    /**
     * Los {@link #TEXELES_POR_SPRITE} texeles RGBA8 de un sprite en la tabla
     * del shader, como enteros con R en el byte bajo (el orden de
     * {@code NativeImage#setPixelRGBA}): (x, y) y (ancho, alto) en píxeles
     * del atlas, 16 bits cada uno, y el color promedio de la textura.
     */
    public static int[] texelesSprite(int x, int y, int ancho, int alto, int promedioRgb) {
        return new int[] {
                (x & 0xFFFF) | (y & 0xFFFF) << 16,
                (ancho & 0xFFFF) | (alto & 0xFFFF) << 16,
                ((promedioRgb >> 16) & 0xFF) | (promedioRgb & 0xFF00) | (promedioRgb & 0xFF) << 16 | 0xFF000000
        };
    }
}
