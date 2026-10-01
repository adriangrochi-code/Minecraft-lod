package com.example.minecraftlodmod.generation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GeneradorAproximadoTest {

    @Test
    @Timeout(5)
    void laBusquedaDeLaSuperficieTerminaTambienConAlturasNegativas() {
        // Superficie en -58: antes, (bajo + alto + 1) >>> 1 daba ~2 mil millones y no terminaba nunca.
        assertEquals(-58, GeneradorAproximado.solidoMasAlto(-60, -53, y -> y <= -58));
        assertEquals(-60, GeneradorAproximado.solidoMasAlto(-60, -53, y -> y <= -60));
        assertEquals(-53, GeneradorAproximado.solidoMasAlto(-60, -53, y -> true));
        assertEquals(-2, GeneradorAproximado.solidoMasAlto(-4, 3, y -> y <= -2));
        assertEquals(70, GeneradorAproximado.solidoMasAlto(64, 71, y -> y <= 70));
    }
}
