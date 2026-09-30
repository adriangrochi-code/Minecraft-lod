package com.example.minecraftlodmod.render;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AtlasLodTest {

    private static final int ROJO = 0xFF0000FF, VERDE = 0xFF00FF00, NADA = 0;

    @Test
    void teselaEsPotenciaDeDosAcotada() {
        assertEquals(16, AtlasLod.tamanoTesela(8));
        assertEquals(16, AtlasLod.tamanoTesela(16));
        assertEquals(32, AtlasLod.tamanoTesela(20));
        assertEquals(32, AtlasLod.tamanoTesela(512));
        assertEquals(4, AtlasLod.nivelesMipmap(16));
        assertEquals(4, AtlasLod.teselasPorFila(9));
        assertEquals(1, AtlasLod.teselasPorFila(1));
    }

    @Test
    void escalarPromediaPacksHdYAgrandaLosChicos() {
        int[] grande = new int[4 * 4];
        for (int i = 0; i < grande.length; i++) {
            grande[i] = (i % 2 == 0) ? 0xFF0000C8 : 0xFF000064; // rojo 200 y 100 alternados
        }
        int[] chica = AtlasLod.escalar(grande, 4, 4, 2);
        assertEquals(150, chica[0] & 0xFF);
        int[] agrandada = AtlasLod.escalar(new int[] {ROJO, VERDE, VERDE, ROJO}, 2, 2, 4);
        assertEquals(ROJO, agrandada[0]);
        assertEquals(VERDE, agrandada[2]);
        assertEquals(ROJO, agrandada[15]);
    }

    @Test
    void huecosDelFollajeSeOscurecenYElRestoQuedaPlano() {
        int[] hoja = new int[16];
        java.util.Arrays.fill(hoja, VERDE);
        hoja[0] = NADA;
        hoja[5] = NADA;
        assertTrue(AtlasLod.esFollaje(hoja));
        int[] rellena = AtlasLod.rellenarHuecos(hoja, 0x00C800, true);
        assertEquals(0xFF, rellena[0] >>> 24);
        assertEquals(Math.round(200 * AtlasLod.HUECO_FOLLAJE), (rellena[0] >> 8) & 0xFF);
        assertEquals(VERDE, rellena[1]);
        assertEquals(200, (AtlasLod.rellenarHuecos(hoja, 0x00C800, false)[0] >> 8) & 0xFF);

        int[] vidrio = new int[16];
        vidrio[0] = VERDE;
        assertFalse(AtlasLod.esFollaje(vidrio));
        int[] lleno = new int[16];
        java.util.Arrays.fill(lleno, VERDE);
        assertFalse(AtlasLod.esFollaje(lleno));
    }

    @Test
    void caraCompletaSoloConElCuadradoEntero() {
        float[] arriba = {0, 1, 0, 0, 1, 1, 1, 1, 1, 1, 1, 0};
        assertTrue(AtlasLod.caraCompleta(arriba, 1, true));
        assertFalse(AtlasLod.caraCompleta(arriba, 1, false));
        float[] losa = {0, 0.5f, 0, 0, 0.5f, 1, 1, 0.5f, 1, 1, 0.5f, 0};
        assertFalse(AtlasLod.caraCompleta(losa, 1, true));
        float[] cactus = {0.0625f, 1, 0, 0.0625f, 1, 1, 0.0625f, 0, 1, 0.0625f, 0, 0};
        assertFalse(AtlasLod.caraCompleta(cactus, 0, false));
    }

    private static AtlasLod.QuadModelo quad(float[] pos, int color) {
        return new AtlasLod.QuadModelo(pos, new float[] {0, 0, 0, 1, 1, 1, 1, 0}, new int[] {color}, 1, 1);
    }

    @Test
    void horneaUnaLosaDeAbajo() {
        // Cara de arriba a media altura y cara norte que cubre solo la mitad de abajo.
        List<AtlasLod.QuadModelo> losa = List.of(
                quad(new float[] {0, 0.5f, 0, 0, 0.5f, 1, 1, 0.5f, 1, 1, 0.5f, 0}, ROJO),
                quad(new float[] {1, 0.5f, 0, 1, 0, 0, 0, 0, 0, 0, 0.5f, 0}, VERDE));
        int[] arriba = AtlasLod.hornear(losa, AtlasLod.Vista.ARRIBA, 4);
        for (int p : arriba) {
            assertEquals(ROJO, p);
        }
        int[] costado = AtlasLod.hornear(losa, AtlasLod.Vista.COSTADO, 4);
        assertEquals(NADA, costado[0]);      // fila de arriba: aire
        assertEquals(NADA, costado[4 + 3]);
        assertEquals(VERDE, costado[2 * 4]); // mitad de abajo: la cara norte
        assertEquals(VERDE, costado[15]);
        assertFalse(AtlasLod.vacia(costado));
    }

    @Test
    void laCaraMasCercanaTapaYElAlfaRecorta() {
        float[] lejos = {0, 1, 1, 0, 0, 1, 1, 0, 1, 1, 1, 1};
        float[] cerca = {0, 1, 0.5f, 0, 0, 0.5f, 1, 0, 0.5f, 1, 1, 0.5f};
        int[] costado = AtlasLod.hornear(List.of(quad(cerca, ROJO), quad(lejos, VERDE)), AtlasLod.Vista.COSTADO, 2);
        assertEquals(ROJO, costado[0]);
        int[] conHueco = AtlasLod.hornear(List.of(quad(cerca, NADA), quad(lejos, VERDE)), AtlasLod.Vista.COSTADO, 2);
        assertEquals(VERDE, conHueco[0]);
        // Un quad de canto desde la vista no dibuja nada.
        float[] deCanto = {0, 0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1};
        assertTrue(AtlasLod.vacia(AtlasLod.hornear(List.of(quad(deCanto, ROJO)), AtlasLod.Vista.COSTADO, 2)));
    }
}
