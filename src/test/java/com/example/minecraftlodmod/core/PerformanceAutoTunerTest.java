package com.example.minecraftlodmod.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PerformanceAutoTunerTest {

    private PerformanceAutoTuner tunerTipico() {
        return new PerformanceAutoTuner(
                2.5, 160,      // umbralPx inicial, radio inicial
                0.5, 8.0,      // umbralPx min/max
                0, 320,        // radio min/max
                0.5, 16);      // pasos
    }

    @Test
    void conFrameTimeAltoPrimeroSubeElUmbralAntesDeTocarElRadio() {
        PerformanceAutoTuner tuner = tunerTipico();
        int radioAntes = tuner.radioActivo();

        tuner.ajustar(30.0, 16.6); // muy por encima del objetivo (lento)

        assertTrue(tuner.umbralPx() > 2.5, "Debería subir el umbral primero");
        assertEquals(radioAntes, tuner.radioActivo(), "El radio no debería tocarse todavía");
    }

    @Test
    void conFrameTimeAltoYUmbralYaEnElMaximoBajaElRadio() {
        PerformanceAutoTuner tuner = new PerformanceAutoTuner(
                8.0, 160, 0.5, 8.0, 0, 320, 0.5, 16); // umbral ya en su máximo

        tuner.ajustar(30.0, 16.6);

        assertEquals(8.0, tuner.umbralPx(), "El umbral no puede subir más allá del máximo");
        assertTrue(tuner.radioActivo() < 160, "Con el umbral saturado, debería bajar el radio");
    }

    @Test
    void puedeLlegarARadioCeroEnHardwareMuyDebil() {
        PerformanceAutoTuner tuner = new PerformanceAutoTuner(
                8.0, 16, 0.5, 8.0, 0, 320, 0.5, 16);

        // Simular muchos ciclos de rendimiento insuficiente.
        for (int i = 0; i < 5; i++) {
            tuner.ajustar(50.0, 16.6);
        }

        assertEquals(0, tuner.radioActivo());
        assertTrue(tuner.enPisoAbsoluto());
    }

    @Test
    void conMargenDeSobraPrimeroRecuperaRadioAntesDeMejorarDetalle() {
        PerformanceAutoTuner tuner = new PerformanceAutoTuner(
                5.0, 100, 0.5, 8.0, 0, 320, 0.5, 16);

        tuner.ajustar(5.0, 16.6); // muy por debajo del objetivo (sobra margen)

        assertEquals(5.0, tuner.umbralPx(), "El umbral no debería mejorar todavía");
        assertTrue(tuner.radioActivo() > 100, "Debería recuperar radio primero");
    }

    @Test
    void conRadioYaEnElTechoMejoraElUmbralEnHardwarePotente() {
        PerformanceAutoTuner tuner = new PerformanceAutoTuner(
                5.0, 320, 0.5, 8.0, 0, 320, 0.5, 16); // radio ya en su techo

        tuner.ajustar(5.0, 16.6);

        assertEquals(320, tuner.radioActivo());
        assertTrue(tuner.umbralPx() < 5.0, "Con el radio en el techo, debería mejorar detalle");
    }
}
