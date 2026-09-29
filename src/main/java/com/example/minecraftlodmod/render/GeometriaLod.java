package com.example.minecraftlodmod.render;

import com.example.minecraftlodmod.core.SuperVoxel;
import com.example.minecraftlodmod.generation.GreedyMesher;
import com.example.minecraftlodmod.generation.Quad;
import com.example.minecraftlodmod.generation.VertexLightSampler;

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
 */
public final class GeometriaLod {

    /** Bits de {@code carasOmitidas}: caras laterales en el borde de la sección que no se dibujan. */
    public static final int OMITIR_X_NEG = 1, OMITIR_X_POS = 2, OMITIR_Z_NEG = 4, OMITIR_Z_POS = 8;

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
     * Textura de una cara: su rectángulo en el atlas, su color promedio, y
     * si el color del vóxel (que ya trae el tinte del bioma) aplica a esta
     * cara. No aplica, por ejemplo, al costado de un bloque de pasto: la
     * tierra no se tiñe, así que ahí manda el promedio de su textura.
     */
    public record Cara(float u0, float v0, float du, float dv, int promedioRgb, boolean usaColorDelVoxel) {
    }

    private float[] posiciones = new float[3 * 1024];
    private int[] colores = new int[1024];
    /** Por vértice: u0, v0, du, dv de la textura (du = 0: sin textura). */
    private float[] texturas = new float[4 * 1024];
    private int[] promedios = new int[1024];
    private byte[] normales = new byte[3 * 1024];
    private int vertices;
    private Texturas fuenteTexturas;

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
        int agregados = 0;
        for (Quad q : GreedyMesher.mallar(grid, lado)) {
            if (omitida(q, lado, carasOmitidas)) {
                continue;
            }
            agregarQuad(grid, lado, q, ox, oy, oz, escala);
            agregados++;
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

    private void agregarQuad(SuperVoxel[] grid, int lado, Quad q, float ox, float oy, float oz, float escala) {
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
        float sombra = sombraDeCara(q.eje(), q.positivo());
        SuperVoxel v = q.voxelRepresentativo();
        Cara cara = fuenteTexturas == null || v.idEstado() == SuperVoxel.SIN_ESTADO ? null
                : fuenteTexturas.cara(v.idEstado(), q.eje(), q.positivo());
        int rgbBase = cara == null || cara.usaColorDelVoxel()
                ? ((v.r() & 0xFF) << 16) | ((v.g() & 0xFF) << 8) | (v.b() & 0xFF)
                : cara.promedioRgb();

        // Recorrido del contorno: (u0,v0) (u1,v0) (u1,v1) (u0,v1).
        vertice(q, plano, u0, v0, luz.minMin(), rgbBase, cara, sombra, ox, oy, oz, escala);
        vertice(q, plano, u0 + largoU, v0, luz.maxMin(), rgbBase, cara, sombra, ox, oy, oz, escala);
        vertice(q, plano, u0 + largoU, v0 + largoV, luz.maxMax(), rgbBase, cara, sombra, ox, oy, oz, escala);
        vertice(q, plano, u0, v0 + largoV, luz.minMax(), rgbBase, cara, sombra, ox, oy, oz, escala);
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
        int t = vertices * 4;
        if (cara != null) {
            texturas[t] = cara.u0();
            texturas[t + 1] = cara.v0();
            texturas[t + 2] = cara.du();
            texturas[t + 3] = cara.dv();
            promedios[vertices] = cara.promedioRgb();
        } else {
            texturas[t] = texturas[t + 1] = texturas[t + 2] = texturas[t + 3] = 0;
            promedios[vertices] = 0;
        }
        int n = vertices * 3;
        byte signo = (byte) (q.positivo() ? 127 : -127);
        normales[n] = eje == Quad.Eje.X ? signo : 0;
        normales[n + 1] = eje == Quad.Eje.Y ? signo : 0;
        normales[n + 2] = eje == Quad.Eje.Z ? signo : 0;
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
            texturas = Arrays.copyOf(texturas, texturas.length * 2);
            promedios = Arrays.copyOf(promedios, promedios.length * 2);
            normales = Arrays.copyOf(normales, normales.length * 2);
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
        return texturas[i * 4 + 2] > 0;
    }

    public float u0(int i) {
        return texturas[i * 4];
    }

    public float v0(int i) {
        return texturas[i * 4 + 1];
    }

    public float du(int i) {
        return texturas[i * 4 + 2];
    }

    public float dv(int i) {
        return texturas[i * 4 + 3];
    }

    /** Color promedio (RGB) de la textura del vértice i; el shader lo usa para extraer solo el detalle. */
    public int promedioTextura(int i) {
        return promedios[i];
    }

    public byte normalX(int i) {
        return normales[i * 3];
    }

    public byte normalY(int i) {
        return normales[i * 3 + 1];
    }

    public byte normalZ(int i) {
        return normales[i * 3 + 2];
    }
}
