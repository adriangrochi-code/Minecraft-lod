package com.example.minecraftlodmod.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LimitesRedTest {

    private static final ParametrosCalidad MEDIO = ParametrosCalidad.de(QualityPreset.MEDIO);

    @Test
    void ceroUsaElRadioDelPreset() {
        assertEquals(MEDIO.radioLodChunks(), LimitesRed.resolver(0, MEDIO, 1024, 2048).radioMaximoChunks());
    }

    @Test
    void nuncaSirveMasAllaDeLoQueGenera() {
        assertEquals(MEDIO.radioLodChunks(), LimitesRed.resolver(5000, MEDIO, 1024, 2048).radioMaximoChunks());
        assertEquals(64, LimitesRed.resolver(64, MEDIO, 1024, 2048).radioMaximoChunks());
    }

    @Test
    void laRafagaNoQuedaPorDebajoDelRitmo() {
        assertEquals(1024, LimitesRed.resolver(0, MEDIO, 1024, 100).rafagaNodos());
    }
}
