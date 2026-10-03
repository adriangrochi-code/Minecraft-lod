package com.example.minecraftlodmod.benchmark;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PuntosBenchmarkTest {

    @Test
    void elVueloAvanzaHaciaDondeMira() {
        PuntosBenchmark.Punto haciaEste = new PuntosBenchmark.Punto("p", 0, 0, PuntosBenchmark.Tipo.VUELO, 150, -90f, 0f);
        double[] p = PuntosBenchmark.posicionVuelo(haciaEste, 10);
        assertEquals(0.5 + 10 * PuntosBenchmark.VELOCIDAD_VUELO, p[0], 1e-9, "yaw -90 = +X");
        assertEquals(0.5, p[1], 1e-9);
        PuntosBenchmark.Punto haciaSur = new PuntosBenchmark.Punto("p", 0, 0, PuntosBenchmark.Tipo.VUELO, 150, 0f, 0f);
        assertEquals(0.5 + PuntosBenchmark.VELOCIDAD_VUELO, PuntosBenchmark.posicionVuelo(haciaSur, 1)[1], 1e-9,
                "yaw 0 = +Z");
    }

    @Test
    void elVueloEsElUltimoPunto() {
        // Así no carga chunks que después afecten a los puntos quietos del mismo escalón.
        var puntos = PuntosBenchmark.PUNTOS;
        assertEquals(PuntosBenchmark.Tipo.VUELO, puntos.get(puntos.size() - 1).tipo());
        assertTrue(puntos.stream().filter(p -> p.tipo() == PuntosBenchmark.Tipo.VUELO).count() == 1);
    }
}
