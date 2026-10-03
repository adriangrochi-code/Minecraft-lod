package com.example.minecraftlodmod.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HorizonteCurvoTest {

    @Test
    void sinBajadaDentroDeVanillaYParabolicaAfuera() {
        assertEquals(0, HorizonteCurvo.bajada(100, 200, HorizonteCurvo.RADIO_TIERRA));
        assertEquals(0, HorizonteCurvo.bajada(200, 200, HorizonteCurvo.RADIO_TIERRA));
        // 10 km en la Tierra: ~7,85 m, como la esfera exacta.
        double exacta = HorizonteCurvo.RADIO_TIERRA
                - Math.sqrt(HorizonteCurvo.RADIO_TIERRA * HorizonteCurvo.RADIO_TIERRA - 1e8);
        assertEquals(exacta, HorizonteCurvo.bajada(10_000, 0, HorizonteCurvo.RADIO_TIERRA), 0.01);
    }

    @Test
    void coeficienteDaLaMismaBajada() {
        double r = 50_000;
        assertEquals(HorizonteCurvo.bajada(3000, 0, r), HorizonteCurvo.coeficiente(r) * 3000.0 * 3000.0, 1e-3);
    }

    @Test
    void horizonteDesdeDosMetrosEnLaTierraEsDeUnosCincoKm() {
        double d = HorizonteCurvo.distanciaHorizonte(2, HorizonteCurvo.RADIO_TIERRA);
        assertEquals(5048, d, 5);
    }

    @Test
    void masAltoVeMasLejosYSeRespetanLosTopes() {
        double r = 100_000;
        assertTrue(HorizonteCurvo.alcanceVisible(200, r) > HorizonteCurvo.alcanceVisible(10, r));
        assertEquals(2048, HorizonteCurvo.radioChunks(300, HorizonteCurvo.RADIO_TIERRA, 8, 2048));
        assertEquals(8, HorizonteCurvo.radioChunks(0, 10, 8, 2048));
        // Planeta de 100 km, ojos a 2 m: sqrt(2·1e5·2) + sqrt(2·1e5·32) ≈ 632 + 2530 bloques ≈ 198 chunks.
        assertEquals(198, HorizonteCurvo.radioChunks(2, r, 8, 2048));
    }

    @Test
    void alturaNegativaCuentaComoElMinimo() {
        assertEquals(HorizonteCurvo.alcanceVisible(2, 1e6), HorizonteCurvo.alcanceVisible(-50, 1e6));
    }
}
