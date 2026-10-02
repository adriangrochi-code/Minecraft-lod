package com.example.minecraftlodmod.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColoresHudTest {

    @Test
    void elUsoVaDeVerdeARojoAlAcercarseAlCien() {
        assertEquals(ColoresHud.VERDE, ColoresHud.uso(20));
        assertEquals(ColoresHud.AMARILLO, ColoresHud.uso(75));
        assertEquals(ColoresHud.ROJO, ColoresHud.uso(95));
        assertEquals(ColoresHud.ROJO, ColoresHud.uso(100));
        // En el medio, degradé: más rojo cuanto más alto.
        int a = ColoresHud.uso(80), b = ColoresHud.uso(88);
        assertTrue(((a >> 8) & 0xFF) > ((b >> 8) & 0xFF), "Menos verde a más uso");
        assertEquals(ColoresHud.GRIS, ColoresHud.uso(-1), "Sin dato");
    }

    @Test
    void losFpsSePonenRojosDebajoDeTreinta() {
        assertEquals(ColoresHud.ROJO, ColoresHud.fps(29));
        assertEquals(ColoresHud.AMARILLO, ColoresHud.fps(30));
        assertEquals(ColoresHud.AMARILLO, ColoresHud.fps(59));
        assertEquals(ColoresHud.VERDE, ColoresHud.fps(60));
        assertEquals(ColoresHud.GRIS, ColoresHud.fps(-1));
    }
}
