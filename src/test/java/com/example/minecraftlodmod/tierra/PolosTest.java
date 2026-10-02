package com.example.minecraftlodmod.tierra;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Los polos de la Tierra cilíndrica: borde, barrera de hielo y farlands sin escalón en el antimeridiano. */
class PolosTest {

    private static final double C = Costura.circunferencia(8);
    private static final BordeTierra CIL = new BordeTierra(null, 8, -1296, 1376, SuperficieTierra.CILINDRICA);

    @Test
    void distanciaAlPolo() {
        assertEquals(1_250_944, C / 4);
        assertEquals(10, CIL.distanciaAlBorde(123, -C / 4 - 10), 1e-9, "polo norte (-z)");
        assertEquals(3, CIL.distanciaAlBorde(-5, C / 4 + 3), 1e-9, "polo sur (+z)");
        assertEquals(-C / 4, CIL.distanciaAlBorde(1e6, 0), 1e-9, "ecuador");
        assertTrue(CIL.cilindrica());
        // La plana sigue con el disco
        BordeTierra plana = new BordeTierra(null, 8, -1296, 1376);
        assertEquals(10, plana.distanciaAlBorde(0, plana.radioDisco() + 10), 1e-6);
        assertEquals(0, plana.inicioBorde());
    }

    @Test
    void barreraDeHielo() {
        double fondo = 63 - 4200 / 8.0; // mar Ártico
        assertEquals(fondo, CIL.ajustarSuperficie(BordeTierra.INICIO_BARRERA - 1, fondo), "antes del frente, el mar");
        assertEquals(BordeTierra.CIMA_BARRERA, CIL.ajustarSuperficie(-BordeTierra.ANCHO_BARRERA, fondo), 1e-9, "la barrera, plana");
        assertEquals(BordeTierra.CIMA_BARRERA, CIL.ajustarSuperficie(0, fondo), 1e-9, "en el polo, la barrera");
        assertEquals(BordeTierra.CIMA_BARRERA, CIL.ajustarSuperficie(500, fondo), 1e-9);
        double medio = CIL.ajustarSuperficie(BordeTierra.INICIO_BARRERA + BordeTierra.FRENTE_BARRERA / 2, fondo);
        assertTrue(medio > fondo && medio < BordeTierra.CIMA_BARRERA);
        assertEquals(400, CIL.ajustarSuperficie(0, 400), "la Antártida (alta) no cambia");
        // Sube sin bajar nunca, todo en el frente
        double antes = fondo;
        for (double d = BordeTierra.INICIO_BARRERA - 10; d <= 0; d++) {
            double h = CIL.ajustarSuperficie(d, fondo);
            assertTrue(h >= antes, "d " + d);
            antes = h;
        }
        BordeTierra plana = new BordeTierra(null, 8, -1296, 1376);
        assertEquals(fondo, plana.ajustarSuperficie(0, fondo), "la plana no tiene barrera");
    }

    @Test
    void farlandsPeriodicasYSinEscalon() {
        for (double d = 50; d < 6000; d += 137) {
            for (int y = 0; y < 1300; y += 97) {
                for (double z : new double[]{-C / 4 - d, C / 4 + d}) {
                    double base = 66 - y;
                    // Periódico
                    assertEquals(CIL.densidadPolo(d, 1234.5, z, y, base, 66), CIL.densidadPolo(d, 1234.5 + C, z, y, base, 66));
                    // En la costura los dos lados coinciden
                    assertEquals(CIL.densidadPolo(d, -C / 2, z, y, base, 66),
                            CIL.densidadPolo(d, Math.nextDown(C / 2), z, y, base, 66), 1e-6);
                }
            }
        }
        // Norte y sur no son el mismo patrón
        int distintos = 0;
        for (int y = 100; y < 1300; y += 50) {
            for (double x = 0; x < 3000; x += 211) {
                if (CIL.densidadPolo(2000, x, -C / 4 - 2000, y, -y, 66) != CIL.densidadPolo(2000, x, C / 4 + 2000, y, -y, 66)) distintos++;
            }
        }
        assertTrue(distintos > 20, "distintos " + distintos);
    }

    @Test
    void presetsConBordeEnLosPolos() throws java.io.IOException {
        for (int escala : new int[]{8, 6}) {
            String json;
            try (var entrada = PolosTest.class.getResourceAsStream(
                    "/data/minecraftlodmod/worldgen/noise_settings/tierra_real_" + escala + ".json")) {
                json = new String(entrada.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            assertTrue(json.contains("minecraftlodmod:tierra_borde") && json.contains("\"proyeccion\": \"cilindrica\"")
                    && json.contains("minecraftlodmod:farlands_congeladas"), "tierra_real_" + escala);
        }
    }
}
