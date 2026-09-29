package com.example.minecraftlodmod.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamicHorizonRadiusTest {

    @Test
    void alNivelDelTerrenoUsaLaFraccionMinima() {
        int radio = DynamicHorizonRadius.calcular(1000, 0.0);
        assertEquals(350, radio); // 35% de 1000, redondeado
    }

    @Test
    void nuncaSuperaElRadioBase() {
        // Alturas enormes (ej. jugador volando muy alto) no deberían nunca
        // superar el techo del preset — esto es lo que garantiza que el
        // rendimiento nunca se degrade más allá de lo ya calibrado.
        int radio = DynamicHorizonRadius.calcular(1000, 100_000.0);
        assertTrue(radio <= 1000);
    }

    @Test
    void crecemonotonamenteConLaAltura() {
        int r1 = DynamicHorizonRadius.calcular(1000, 10);
        int r2 = DynamicHorizonRadius.calcular(1000, 50);
        int r3 = DynamicHorizonRadius.calcular(1000, 200);

        assertTrue(r1 < r2, "Más altura debería dar más radio");
        assertTrue(r2 < r3, "Más altura debería seguir dando más radio");
    }

    @Test
    void alturaNegativaSeTrataComoCero() {
        int radioNegativo = DynamicHorizonRadius.calcular(1000, -50.0);
        int radioCero = DynamicHorizonRadius.calcular(1000, 0.0);
        assertEquals(radioCero, radioNegativo);
    }

    @Test
    void respetaParametrosCustomDeFraccionYVelocidad() {
        // Fracción mínima más alta: incluso a altura 0, el radio debería ser mayor.
        int radioFraccionAlta = DynamicHorizonRadius.calcular(1000, 0.0, 0.6, 0.02);
        assertEquals(600, radioFraccionAlta);
    }
}
