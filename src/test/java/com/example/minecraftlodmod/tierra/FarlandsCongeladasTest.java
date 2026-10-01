package com.example.minecraftlodmod.tierra;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FarlandsCongeladasTest {

    // Tierra plana 1:8
    private static final int MIN_Y = -1296, MAX_Y = 1376;
    private static final double MESETA = 413.4;

    private static double dens(double d, double s, int y) {
        return FarlandsCongeladas.densidad(d, s, y, MESETA - y, MESETA, MIN_Y, MAX_Y);
    }

    @Test
    void dentroDelDiscoNoCambiaNada() {
        assertEquals(12.5, FarlandsCongeladas.densidad(-0.01, 3, 100, 12.5, 112.5, MIN_Y, MAX_Y));
        assertEquals(-7, FarlandsCongeladas.densidad(-5000, 3, 100, -7, 93, MIN_Y, MAX_Y));
    }

    @Test
    void transicionConGrietasCadaVezMasHondas() {
        int cercaHuecos = 0, lejosHuecos = 0;
        for (double s = 0; s < 3000; s += 1) {
            if (dens(50, s, (int) MESETA - 5) <= 0) cercaHuecos++;
            if (dens(900, s, (int) MESETA - 5) <= 0) lejosHuecos++;
        }
        assertTrue(cercaHuecos > 0, "hay grietas cerca del borde");
        assertTrue(lejosHuecos > 3 * cercaHuecos, "más anchas lejos: " + cercaHuecos + " vs " + lejosHuecos);
        // Lejos, alguna grieta llega a más de 150 bloques bajo la meseta
        boolean honda = false;
        for (double s = 0; s < 3000 && !honda; s += 1) honda = dens(990, s, (int) MESETA - 150) <= 0;
        assertTrue(honda);
        // Sobre la meseta, aire (antes de las farlands)
        assertTrue(dens(500, 10, (int) MESETA + 2) <= 0);
    }

    @Test
    void farlandsHastaCercaDelTechoConTunelesYPasillos() {
        int alto = MIN_Y, solidos = 0, huecosBajoLaCima = 0;
        for (double d = FarlandsCongeladas.TRANSICION + FarlandsCongeladas.RAMPA; d < 5000; d += 16) {
            int h = FarlandsCongeladas.alturaColumna(d, 777, MESETA, MIN_Y, MAX_Y);
            alto = Math.max(alto, h);
            for (int y = (int) MESETA + 10; y < h; y += 1) {
                if (dens(d, 777, y) > 0) solidos++; else huecosBajoLaCima++;
            }
        }
        assertTrue(alto > MAX_Y - 200 && alto < MAX_Y - FarlandsCongeladas.MARGEN_TECHO, "cima " + alto);
        assertTrue(solidos > 0 && huecosBajoLaCima > 0, "paredes con túneles");
        assertTrue(dens(3000, 777, MAX_Y - 10) <= 0, "libre bajo el techo");
    }

    @Test
    void alturaColumnaEsElSolidoMasAlto() {
        for (double d : new double[]{-100, 0, 300, 999, 1500, 2345, 4999, 7000}) {
            for (double s = 0; s < 2000; s += 137) {
                int h = FarlandsCongeladas.alturaColumna(d, s, MESETA, MIN_Y, MAX_Y);
                assertTrue(dens(d, s, h) > 0, d + "," + s);
                for (int y = h + 1; y < MAX_Y; y++) assertTrue(dens(d, s, y) <= 0, d + "," + s + " y " + y);
            }
        }
    }

    @Test
    void esDeterministica() {
        assertEquals(dens(2222, 31.5, 700), dens(2222, 31.5, 700));
        assertEquals(FarlandsCongeladas.ruido3(1.3, 2.7, -5.1, 1), FarlandsCongeladas.ruido3(1.3, 2.7, -5.1, 1));
    }

    @Test
    void precisionA2y5MillonesDeBloques() {
        // Borde del disco a 1:8: ~2,50 M bloques. La ida y vuelta lat/lon ↔ bloque no pierde ni 1/100 de bloque.
        ProyeccionAzimutal p = new ProyeccionAzimutal(8);
        double k = p.bloquesPorGrado();
        for (double lon = -179.9; lon < 180; lon += 17.3) {
            for (double lat : new double[]{-89.99, -89.5, -80}) {
                double x = p.x(lat, lon), z = p.z(lat, lon);
                assertEquals(lat, p.latitud(x, z), 0.01 / k);
                // Error angular por la distancia al centro: en bloques sobre el arco
                double errorArco = Math.abs(Proyeccion.normalizarLongitud(p.longitud(x, z) - lon)) * Math.PI / 180 * Math.hypot(x, z);
                assertTrue(errorArco < 0.01, "error " + errorArco + " bloques");
            }
        }
        // Un bloque de diferencia a 2,5 M se distingue en latitud y en longitud
        double r = p.radioDisco() - 1;
        assertTrue(p.latitud(0, r) != p.latitud(0, r + 1));
        assertTrue(p.longitud(1, r) != p.longitud(0, r));
    }
}
