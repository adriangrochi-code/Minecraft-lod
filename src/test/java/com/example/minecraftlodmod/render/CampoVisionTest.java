package com.example.minecraftlodmod.render;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CampoVisionTest {

    /** Cámara mirando hacia -Z (sin rotar), FOV 70°, 16:9. */
    private static CampoVision camara(boolean ceroAUno) {
        Matrix4f proyeccion = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, 0.05f, 1000f, ceroAUno);
        return new CampoVision(proyeccion, new Matrix4f());
    }

    @Test
    void loDeAdelanteEntraYLoDeAtrasNo() {
        for (boolean ceroAUno : new boolean[]{false, true}) {
            CampoVision v = camara(ceroAUno);
            assertTrue(v.tocaCaja(-32, -100, -500, 32, 100, -436), "Adelante");
            assertFalse(v.tocaCaja(-32, -100, 436, 32, 100, 500), "Atrás");
            assertFalse(v.tocaCaja(800, -100, -100, 864, 100, -36), "Muy al costado");
            assertTrue(v.tocaCaja(-10, -10, -10, 10, 10, 10), "La cámara adentro de la caja");
        }
    }

    @Test
    void sinFarLoMuyLejanoDeAdelanteSigueEntrando() {
        // El far de la proyección es 1000: el LOD a 100 000 bloques igual tiene que dibujarse.
        assertTrue(camara(false).tocaCaja(-64, -64, -100_064, 64, 64, -100_000));
    }

    @Test
    void unaCeldaGrandeQueCruzaElBordeEntra() {
        assertTrue(camara(false).tocaCaja(-2000, -64, -2000, 0, 320, 0));
    }
}
