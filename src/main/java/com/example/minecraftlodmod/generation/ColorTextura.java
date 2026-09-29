package com.example.minecraftlodmod.generation;

/**
 * Aritmética de color para el LOD (lógica pura): el color de un supervóxel
 * sale del promedio de la textura real del bloque, teñido como lo tiñe
 * vanilla (pasto, follaje y agua son texturas grises multiplicadas por el
 * color del bioma). A la distancia en que se ve el LOD, el mipmapping de
 * vanilla ya muestra cada bloque como ese promedio: usar el mismo color hace
 * que el paso de terreno vanilla a LOD casi no se note — a diferencia del
 * color de mapa ({@code MapColor}), que es una paleta aproximada.
 */
public final class ColorTextura {

    private ColorTextura() {
    }

    /**
     * Promedio de una textura, ponderado por alfa: los píxeles transparentes
     * (huecos de hojas, flores) no aportan, así el color es el del material
     * visible y no se aclara ni oscurece por el fondo.
     *
     * @param pixelesAbgr píxeles en el formato de {@code NativeImage.getPixelRGBA}
     *                    (byte bajo = rojo, alto = alfa)
     * @return RGB 0xRRGGBB, o -1 si la textura es completamente transparente
     */
    public static int promedio(int[] pixelesAbgr) {
        long sumaR = 0, sumaG = 0, sumaB = 0, sumaAlfa = 0;
        for (int p : pixelesAbgr) {
            int a = (p >>> 24) & 0xFF;
            if (a == 0) {
                continue;
            }
            sumaR += (long) (p & 0xFF) * a;
            sumaG += (long) ((p >> 8) & 0xFF) * a;
            sumaB += (long) ((p >> 16) & 0xFF) * a;
            sumaAlfa += a;
        }
        if (sumaAlfa == 0) {
            return -1;
        }
        int r = (int) Math.round(sumaR / (double) sumaAlfa);
        int g = (int) Math.round(sumaG / (double) sumaAlfa);
        int b = (int) Math.round(sumaB / (double) sumaAlfa);
        return (r << 16) | (g << 8) | b;
    }

    /** Tinte como lo aplica vanilla: multiplicación por canal (0xRRGGBB × 0xRRGGBB). */
    public static int tenir(int rgb, int tinte) {
        int r = ((rgb >> 16) & 0xFF) * ((tinte >> 16) & 0xFF) / 255;
        int g = ((rgb >> 8) & 0xFF) * ((tinte >> 8) & 0xFF) / 255;
        int b = (rgb & 0xFF) * (tinte & 0xFF) / 255;
        return (r << 16) | (g << 8) | b;
    }
}
