package com.example.minecraftlodmod.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NieblaSectoresTest {

    @Test
    void laNieblaTerminaEnLaPrimeraZonaSinDatos() {
        NieblaSectores n = new NieblaSectores();
        n.reiniciar();
        n.dibujada(1000, 0, 45);   // hacia +X hay LOD hasta 1045
        n.faltante(600, 0, 45);    // pero a 555 falta una zona
        n.dibujada(0, 2000, 45);   // hacia +Z todo cargado hasta 2045
        float[] a = n.alcances(200);
        assertEquals(555, a[NieblaSectores.sector(1, 0)], 1e-3);
        assertEquals(200, a[NieblaSectores.sector(-1, 0)], 1e-3, "Sin nada dibujado: el mínimo");
        assertEquals(2045, a[NieblaSectores.sector(0, 1)], 1e-3);
    }

    @Test
    void unaCeldaCercanaCubreVariosSectores() {
        NieblaSectores n = new NieblaSectores();
        n.reiniciar();
        n.dibujada(150, 0, 45); // a 150 bloques, una celda de 64 abarca unos 18°: varios sectores
        float[] a = n.alcances(0);
        int centro = NieblaSectores.sector(1, 0);
        assertEquals(195, a[centro], 1e-3);
        assertEquals(195, a[(centro + 1) % NieblaSectores.SECTORES], 1e-3);
        assertEquals(195, a[(centro + NieblaSectores.SECTORES - 1) % NieblaSectores.SECTORES], 1e-3);
        assertEquals(0, a[(centro + NieblaSectores.SECTORES / 2) % NieblaSectores.SECTORES], 1e-3);
    }

    @Test
    void losSectoresCubrenTodaLaVuelta() {
        assertEquals(NieblaSectores.sector(1, 0), NieblaSectores.sector(5, 0.0001));
        assertNotEquals(NieblaSectores.sector(1, 0), NieblaSectores.sector(-1, 0));
        for (int i = 0; i < 360; i++) {
            double a = Math.toRadians(i);
            int s = NieblaSectores.sector(Math.cos(a), Math.sin(a));
            assertTrue(s >= 0 && s < NieblaSectores.SECTORES);
        }
    }
}
