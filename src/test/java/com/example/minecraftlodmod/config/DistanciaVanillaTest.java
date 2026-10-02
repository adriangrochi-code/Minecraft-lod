package com.example.minecraftlodmod.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DistanciaVanillaTest {

    @Test
    void elTopeCreceConElRadioDelPreset() {
        assertEquals(5, DistanciaVanilla.topeParaRadio(32));
        assertEquals(6, DistanciaVanilla.topeParaRadio(96));
        assertEquals(8, DistanciaVanilla.topeParaRadio(160));
        assertEquals(10, DistanciaVanilla.topeParaRadio(224));
        assertEquals(12, DistanciaVanilla.topeParaRadio(1024));
    }

    @Test
    void nuncaSubeLaElegidaNiBajaDeDos() {
        assertEquals(6, DistanciaVanilla.acotar(6, 8), "Elegiste menos que el tope: queda la tuya");
        assertEquals(8, DistanciaVanilla.acotar(20, 8));
        assertEquals(2, DistanciaVanilla.acotar(1, 8));
    }
}
