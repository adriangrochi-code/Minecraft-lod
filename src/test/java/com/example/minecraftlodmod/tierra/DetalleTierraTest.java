package com.example.minecraftlodmod.tierra;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DetalleTierraTest {

    @Test
    void llanuraQuedaLlana() {
        // Rango de 3 m entre las 16 muestras: el detalle no llega a un metro (ni a un bloque)
        for (int x = 0; x < 2000; x += 37) {
            assertEquals(150, DetalleTierra.conDetalle(x, -x * 3, 150, 3), 1.0);
        }
    }

    @Test
    void montanaGanaRelieveAcotado() {
        double suma2 = 0, max = 0;
        int n = 0;
        for (int x = 0; x < 4000; x += 3) {
            for (int z = 0; z < 400; z += 7) {
                double d = DetalleTierra.conDetalle(x, z, 4000, 3000) - 4000;
                suma2 += d * d;
                max = Math.max(max, Math.abs(d));
                n++;
            }
        }
        double desvio = Math.sqrt(suma2 / n);
        assertTrue(desvio > 40 && desvio < 450, "desvío " + desvio);
        assertTrue(max <= DetalleTierra.AMPLITUD_MAXIMA_M + 1e-9, "máximo " + max);
    }

    @Test
    void nuncaCambiaDeLadoDelNivelDelMar() {
        for (int x = 0; x < 3000; x += 5) {
            for (double e : new double[]{0.5, 5, 80, 300, -0.5, -5, -80, -300}) {
                double r = DetalleTierra.conDetalle(x, x * 0.7, e, 5000);
                assertTrue(Math.signum(r) == Math.signum(e), e + " → " + r);
            }
        }
    }

    @Test
    void cercaDeLaCostaSeApaga() {
        // A 2 m sobre el mar casi no hay detalle aunque el lugar sea rugoso (acantilado)
        double maxCambio = 0;
        for (int x = 0; x < 3000; x += 3) {
            maxCambio = Math.max(maxCambio, Math.abs(DetalleTierra.conDetalle(x, 11, 2, 2000) - 2));
        }
        assertTrue(maxCambio < 2, "cambio " + maxCambio);
    }

    @Test
    void continuoYDeterministico() {
        double a = DetalleTierra.conDetalle(1000, 2000, 3000, 2500);
        assertEquals(a, DetalleTierra.conDetalle(1000, 2000, 3000, 2500));
        // Un bloque de distancia cambia poco (es ruido suave, ondas de 20 a 160 bloques)
        double b = DetalleTierra.conDetalle(1001, 2000, 3000, 2500);
        assertTrue(Math.abs(a - b) < 60, a + " vs " + b);
    }
}
