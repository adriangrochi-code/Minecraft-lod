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

    /** Promedio por canal de colores 0xRRGGBB. */
    /** Qué parte de la textura no es transparente, de 0 (nada) a 255 (toda). */
    public static int cobertura(int[] pixelesAbgr) {
        if (pixelesAbgr.length == 0) {
            return 0;
        }
        int llenos = 0;
        for (int p : pixelesAbgr) {
            if ((p >>> 24) != 0) {
                llenos++;
            }
        }
        return Math.round(llenos * 255f / pixelesAbgr.length);
    }

    /**
     * Cuánto tiñe una decoración (flor, pasto, cultivo, caña) al bloque de abajo, según
     * la cobertura de su textura (0-255): 1,5 veces lo que cubre vista de frente, porque
     * a lo lejos se ven de costado y tapan más suelo; nunca más de 3/4 (el suelo se ve).
     */
    public static float pesoCobertura(int cobertura) {
        return Math.min(0.75f, cobertura / 255f * 1.5f);
    }

    /** {@code a} hacia {@code b} en la proporción {@code t} (0 = a, 1 = b), canal por canal. */
    public static int mezclar(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return (r << 16) | (g << 8) | bl;
    }

    /**
     * Promedio de una región de una textura de entidad, dada en píxeles de su
     * tamaño de vanilla ({@code anchoBase}×{@code altoBase}): con un paquete de
     * texturas de más resolución se escala. -1 si la región es toda transparente.
     */
    public static int promedioRegion(int[] pixelesAbgr, int ancho, int alto, int anchoBase, int altoBase,
                                     int u0, int v0, int u1, int v1) {
        int x0 = u0 * ancho / anchoBase, x1 = Math.max(x0 + 1, u1 * ancho / anchoBase);
        int y0 = v0 * alto / altoBase, y1 = Math.max(y0 + 1, v1 * alto / altoBase);
        int[] region = new int[(x1 - x0) * (y1 - y0)];
        int n = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                region[n++] = pixelesAbgr[Math.min(alto - 1, y) * ancho + Math.min(ancho - 1, x)];
            }
        }
        return promedio(region);
    }

    public static int promedioRgb(int[] colores, int cantidad) {
        long r = 0, g = 0, b = 0;
        for (int i = 0; i < cantidad; i++) {
            r += (colores[i] >> 16) & 0xFF;
            g += (colores[i] >> 8) & 0xFF;
            b += colores[i] & 0xFF;
        }
        int n = Math.max(1, cantidad);
        return (int) Math.round(r / (double) n) << 16 | (int) Math.round(g / (double) n) << 8
                | (int) Math.round(b / (double) n);
    }

    /** Interpolación bilineal por canal entre 4 colores 0xRRGGBB (fx, fz entre 0 y 1). */
    public static int bilineal(int c00, int c10, int c01, int c11, float fx, float fz) {
        int resultado = 0;
        for (int corrimiento = 16; corrimiento >= 0; corrimiento -= 8) {
            float a = ((c00 >> corrimiento) & 0xFF) * (1 - fx) + ((c10 >> corrimiento) & 0xFF) * fx;
            float b = ((c01 >> corrimiento) & 0xFF) * (1 - fx) + ((c11 >> corrimiento) & 0xFF) * fx;
            resultado |= Math.round(a * (1 - fz) + b * fz) << corrimiento;
        }
        return resultado;
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
