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
}
