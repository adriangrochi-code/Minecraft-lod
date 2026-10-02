package com.example.minecraftlodmod.tierra;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CuevasTierraTest {

    /** Fracción de huecos a esa profundidad en un plano de 600×600 bloques. */
    private static double huecos(double profundidad) {
        int n = 0, h = 0;
        for (int x = 0; x < 600; x += 3) {
            for (int z = 0; z < 600; z += 3) {
                n++;
                if (CuevasTierra.densidad(x, 100 - profundidad, z, profundidad) <= 0) h++;
            }
        }
        return (double) h / n;
    }

    @Test
    void sobreLaSuperficieNoCambiaNada() {
        assertEquals(-3.0, CuevasTierra.densidad(10, 50, 10, -3.0));
        assertEquals(0.0, CuevasTierra.densidad(10, 50, 10, 0.0));
    }

    @Test
    void cuevasEntreLaFranjaYLoMuyHondo() {
        double a40 = huecos(40), a250 = huecos(250);
        assertTrue(a40 > 0.01 && a40 < 0.25, "a 40 bloques: " + a40);
        assertTrue(a250 > a40, "más cavernas hondo: " + a40 + " → " + a250);
        assertEquals(0.0, huecos(CuevasTierra.PROFUNDIDAD_MAXIMA + 60), "muy hondo: roca");
    }

    @Test
    void cercaDelSueloCasiNadaSalvoEntradas() {
        double a3 = huecos(3);
        assertTrue(a3 < 0.02, "a 3 bloques: " + a3);
    }

    @Test
    void determinista() {
        assertEquals(CuevasTierra.densidad(123, -40, 77, 150), CuevasTierra.densidad(123, -40, 77, 150));
    }
}
