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

    /** Diferencia mínima (suma por canal) entre la franja de arriba y el resto para considerarla franja. */
    static final int DIFERENCIA_FRANJA = 40;
    /** Diferencia máxima entre el resto del costado y la textura de abajo para que sean "lo mismo". */
    static final int PARECIDO_ABAJO = 36;

    /**
     * true si el costado de un bloque es "franja arriba + lo de abajo", como
     * el pasto, la nieve sobre pasto, el micelio o el podzol: el cuarto de
     * arriba de la textura es de otro color que la mitad de abajo, y esa
     * mitad se parece a la cara de abajo del bloque (la tierra). En un vóxel
     * grande el LOD pone la franja solo en su fila de arriba y la textura de
     * abajo en el resto; repetir el costado en cada bloque dibujaba una línea
     * de pasto por bloque.
     *
     * @param pixelesAbgr   costado, fila por fila (formato de NativeImage)
     * @param promedioAbajo promedio 0xRRGGBB de la cara de abajo
     */
    public static boolean tieneFranja(int[] pixelesAbgr, int ancho, int alto, int promedioAbajo) {
        if (ancho <= 0 || alto < 4 || promedioAbajo < 0 || pixelesAbgr.length < ancho * alto) {
            return false;
        }
        int arriba = promedio(java.util.Arrays.copyOfRange(pixelesAbgr, 0, ancho * (alto / 4)));
        int resto = promedio(java.util.Arrays.copyOfRange(pixelesAbgr, ancho * (alto / 2), ancho * alto));
        return arriba >= 0 && resto >= 0 && diferencia(arriba, resto) > DIFERENCIA_FRANJA
                && diferencia(resto, promedioAbajo) < PARECIDO_ABAJO;
    }

    /** Suma de las diferencias por canal entre dos 0xRRGGBB. */
    static int diferencia(int a, int b) {
        return Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF)) + Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF))
                + Math.abs((a & 0xFF) - (b & 0xFF));
    }
}
