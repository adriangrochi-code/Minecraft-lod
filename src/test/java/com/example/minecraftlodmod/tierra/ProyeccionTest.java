package com.example.minecraftlodmod.tierra;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProyeccionTest {

    @Test
    void circunferenciaYRadioDelDiscoA1en8() {
        ProyeccionCilindrica cil = new ProyeccionCilindrica(8);
        assertEquals(5_003_778, cil.bloquesPorGrado() * 360, 1); // ~5,0 M bloques (01-decisiones.md)
        ProyeccionAzimutal azi = new ProyeccionAzimutal(8);
        assertEquals(2_501_889, azi.radioDisco(), 1);
        assertEquals(6_671_704, new ProyeccionCilindrica(6).bloquesPorGrado() * 360, 1);
    }

    @Test
    void idaYVueltaCilindrica() {
        Proyeccion p = new ProyeccionCilindrica(8);
        for (double lat = -89; lat <= 89; lat += 7.3) {
            for (double lon = -179; lon < 180; lon += 11.7) {
                double x = p.x(lat, lon), z = p.z(lat, lon);
                assertEquals(lat, p.latitud(x, z), 1e-9);
                assertEquals(lon, p.longitud(x, z), 1e-9);
            }
        }
        assertEquals(0, p.x(0, 0), 0);
        assertEquals(0, p.z(0, 0), 0);
        // norte hacia -z, este hacia +x
        assertEquals(-1, Math.signum(p.z(10, 0)));
        assertEquals(1, Math.signum(p.x(0, 10)));
    }

    @Test
    void idaYVueltaAzimutal() {
        Proyeccion p = new ProyeccionAzimutal(8);
        for (double lat = -89.5; lat <= 89.5; lat += 7.3) {
            for (double lon = -179; lon < 180; lon += 11.7) {
                double x = p.x(lat, lon), z = p.z(lat, lon);
                assertEquals(lat, p.latitud(x, z), 1e-9);
                assertEquals(lon, p.longitud(x, z), 1e-9);
            }
        }
        assertEquals(90, p.latitud(0, 0), 0);
        // Cerca de Greenwich: el norte hacia -z y el este hacia +x
        double x = p.x(50, 0), z = p.z(50, 0);
        assertEquals(-1, Math.signum(p.z(51, 0) - z));
        assertEquals(1, Math.signum(p.x(50, 1) - x));
        // Distancias norte-sur reales: 1° de latitud = bloquesPorGrado en cualquier lugar
        assertEquals(p.bloquesPorGrado(), p.z(-40, 0) - p.z(-39, 0), 1e-6);
    }

    @Test
    void azimutalPasandoElPoloSurDaLatitudMenorAMenos90() {
        ProyeccionAzimutal p = new ProyeccionAzimutal(8);
        assertEquals(-90, p.latitud(0, p.radioDisco()), 1e-9);
        assertEquals(-90 - 4000 / p.bloquesPorGrado(), p.latitud(0, p.radioDisco() + 4000), 1e-9);
    }

    @Test
    void normalizarLongitud() {
        assertEquals(-180, Proyeccion.normalizarLongitud(180), 1e-12);
        assertEquals(170, Proyeccion.normalizarLongitud(-190), 1e-12);
        assertEquals(-170, Proyeccion.normalizarLongitud(190 + 720), 1e-9);
        assertEquals(0, Proyeccion.normalizarLongitud(0), 0);
    }
}
