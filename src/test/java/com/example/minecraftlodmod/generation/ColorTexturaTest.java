package com.example.minecraftlodmod.generation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ColorTexturaTest {

    /** Píxel en el formato de NativeImage: byte bajo = rojo, alto = alfa. */
    private static int abgr(int r, int g, int b, int a) {
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    @Test
    void promediaSoloLosPixelesVisibles() {
        int[] hoja = {abgr(0, 200, 0, 255), abgr(0, 100, 0, 255), abgr(255, 255, 255, 0), abgr(255, 0, 0, 0)};
        assertEquals(0x009600, ColorTextura.promedio(hoja), "Los huecos transparentes no aclaran la hoja");
    }

    @Test
    void ponderaPorAlfa() {
        int[] p = {abgr(200, 0, 0, 255), abgr(0, 0, 0, 85)};
        assertEquals(150 << 16, ColorTextura.promedio(p));
    }

    @Test
    void unaTexturaTransparenteNoTieneColor() {
        assertEquals(-1, ColorTextura.promedio(new int[]{abgr(10, 10, 10, 0)}));
    }

    @Test
    void elTinteMultiplicaPorCanalComoVanilla() {
        int grisDelPasto = 0x949494;
        int verdeLlanura = 0x91BD59;
        assertEquals(0x546D33, ColorTextura.tenir(grisDelPasto, verdeLlanura));
        assertEquals(0x123456, ColorTextura.tenir(0x123456, 0xFFFFFF), "Tinte blanco no cambia nada");
    }

    /** Textura de 4×8: las filas de arriba de un color y el resto de otro. */
    private static int[] costado(int filasArriba, int arriba, int resto) {
        int[] p = new int[4 * 8];
        for (int i = 0; i < p.length; i++) {
            int c = i / 4 < filasArriba ? arriba : resto;
            p[i] = abgr((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF, 255);
        }
        return p;
    }

    @Test
    void elCostadoDelPastoTieneFranja() {
        int pasto = 0x5A8C32, tierra = 0x866043;
        assertEquals(true, ColorTextura.tieneFranja(costado(2, pasto, tierra), 4, 8, tierra));
    }

    @Test
    void unTroncoOUnaPiedraNoTienenFranja() {
        int corteza = 0x6B5132;
        assertEquals(false, ColorTextura.tieneFranja(costado(0, corteza, corteza), 4, 8, 0xA08050),
                "Sin diferencia entre arriba y abajo");
        assertEquals(false, ColorTextura.tieneFranja(costado(2, 0xFFFFFF, corteza), 4, 8, 0x303030),
                "El resto no se parece a la cara de abajo");
    }

    @Test
    void promedioYBilinealPorCanal() {
        assertEquals(0x806040, ColorTextura.promedioRgb(new int[] {0xFF8040, 0x004040, 0}, 2));
        int a = 0x000000, b = 0xFF0000, c = 0x00FF00, d = 0x0000FF;
        assertEquals(a, ColorTextura.bilineal(a, b, c, d, 0, 0));
        assertEquals(d, ColorTextura.bilineal(a, b, c, d, 1, 1));
        assertEquals(0x404040, ColorTextura.bilineal(a, b, c, d, 0.5f, 0.5f));
        assertEquals(0x800000, ColorTextura.bilineal(a, b, c, d, 0.5f, 0f));
    }

    @org.junit.jupiter.api.Test
    void laCoberturaCuentaLosPixelesNoTransparentes() {
        int lleno = 0xFF00FF00, vacio = 0x00000000;
        org.junit.jupiter.api.Assertions.assertEquals(255, ColorTextura.cobertura(new int[]{lleno, lleno}));
        org.junit.jupiter.api.Assertions.assertEquals(64, ColorTextura.cobertura(new int[]{lleno, vacio, vacio, vacio}));
        org.junit.jupiter.api.Assertions.assertEquals(0, ColorTextura.cobertura(new int[]{vacio}));
    }

    @org.junit.jupiter.api.Test
    void mezclarVaDeUnColorAlOtro() {
        org.junit.jupiter.api.Assertions.assertEquals(0x102030, ColorTextura.mezclar(0x102030, 0xF0E0D0, 0));
        org.junit.jupiter.api.Assertions.assertEquals(0xF0E0D0, ColorTextura.mezclar(0x102030, 0xF0E0D0, 1));
        org.junit.jupiter.api.Assertions.assertEquals(0x808080, ColorTextura.mezclar(0x000000, 0xFFFFFF, 0.5f),
                "mitad de camino (127,5 redondea a 128)");
    }

    @org.junit.jupiter.api.Test
    void elPromedioDeUnaRegionEscalaConLaResolucion() {
        // Textura 4×4 "de 2×2 base": la región base (1,0)-(2,1) es el cuadrante de arriba a la derecha.
        int rojo = 0xFF0000FF, azul = 0xFFFF0000; // ABGR
        int[] p = new int[16];
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                p[y * 4 + x] = x >= 2 && y < 2 ? rojo : azul;
            }
        }
        org.junit.jupiter.api.Assertions.assertEquals(0xFF0000, ColorTextura.promedioRegion(p, 4, 4, 2, 2, 1, 0, 2, 1));
        org.junit.jupiter.api.Assertions.assertEquals(0x0000FF, ColorTextura.promedioRegion(p, 4, 4, 2, 2, 0, 1, 1, 2));
    }
}
