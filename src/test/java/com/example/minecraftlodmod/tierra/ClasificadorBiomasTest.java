package com.example.minecraftlodmod.tierra;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Las clases de Köppen de los puntos son las del mapa 1991-2020 de Beck et al.
 * (leídas del GeoTIFF de 1 km al escribir esto): Sahara 4 (BWh), Amazonia 1
 * (Af), Groenlandia 30 (EF), Madrid 7 (BSk), Siberia 27 (Dfc), Londres 15 (Cfb).
 */
class ClasificadorBiomasTest {

    private static String clave(double elev, double lat, int clase) {
        return ClasificadorBiomas.clave(elev, lat, clase, false, 8);
    }

    @Test
    void puntosConocidos() {
        assertEquals("BWh", clave(400, 23, 4), "Sahara");
        assertEquals("Af", clave(80, -3, 1), "Amazonia");
        assertEquals("EF", clave(2500, 72, 30), "Groenlandia: hielo aunque esté sobre la línea de nieve");
        assertEquals("BSk", clave(650, 40.4, 7), "Madrid");
        assertEquals("Dfc", clave(300, 60, 27), "Siberia");
        assertEquals("Cfb", clave(20, 51.5, 15), "Londres");
    }

    @Test
    void oceanosPorLatitudYProfundidad() {
        assertEquals("oceano_calido", clave(-50, 5, 0));
        assertEquals("oceano_calido_profundo", clave(-4000, -10, 0));
        assertEquals("oceano_templado", clave(-100, 30, 0));
        assertEquals("oceano_normal_profundo", clave(-3000, 45, 0));
        assertEquals("oceano_frio", clave(-150, -55, 0));
        assertEquals("oceano_helado_profundo", clave(-3000, 80, 0));
    }

    @Test
    void tierraBajoElNivelDelMarConClimaNoEsMar() {
        assertEquals("BWh", clave(-430, 31.5, 4), "Mar Muerto (orilla)");
    }

    @Test
    void playasJuntoAlMar() {
        assertEquals("playa", ClasificadorBiomas.clave(5, 20, 3, true, 8));
        assertEquals("playa_fria", ClasificadorBiomas.clave(5, 65, 29, true, 8));
        assertEquals("playa_fria", ClasificadorBiomas.clave(3, 50, 26, true, 8), "clima D");
        assertEquals("Aw", ClasificadorBiomas.clave(20, 20, 3, true, 8), "a más de 2 bloques no es playa");
        assertEquals("Aw", ClasificadorBiomas.clave(5, 20, 3, false, 8), "lejos del mar no es playa");
    }

    @Test
    void pisosDeAltura() {
        // Himalaya, 28°: nieve desde 4280 m
        assertEquals(4280, ClasificadorBiomas.lineaDeNieve(28), 1e-9);
        assertEquals("cumbre", clave(8350, 28, 12));
        assertEquals("nieve", clave(5000, 28, 12));
        assertEquals("roca_alta", clave(3800, 28, 12));
        assertEquals("Cwb", clave(3000, 28, 12));
        // Los climas polares no pasan a pisos (la tundra del Tíbet sigue siendo tundra)
        assertEquals("ET", clave(5000, 32, 29));
        // Tierra sin dato de clima
        assertEquals("sin_clima", clave(10, 0, 0));
    }

    @Test
    void clavesCompletas() {
        assertEquals(30 + 10 + 7, ClasificadorBiomas.CLAVES.size());
        assertTrue(ClasificadorBiomas.CLAVES.contains("Dfd"));
        assertTrue(ClasificadorBiomas.CLAVES.contains("oceano_helado_profundo"));
        for (int c = 1; c <= 30; c++) {
            for (double lat = -89; lat < 90; lat += 7) {
                for (double e = -9000; e < 9000; e += 333) {
                    assertTrue(ClasificadorBiomas.CLAVES.contains(clave(e, lat, c)));
                    assertTrue(ClasificadorBiomas.CLAVES.contains(clave(e, lat, 0)));
                }
            }
        }
    }
}
