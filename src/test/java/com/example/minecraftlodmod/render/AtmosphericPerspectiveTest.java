package com.example.minecraftlodmod.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AtmosphericPerspectiveTest {

    @Test
    void sinLlegarAlInicioElFactorEsCero() {
        assertEquals(0.0, AtmosphericPerspective.factorDeNiebla(50, 100, 500));
    }

    @Test
    void masAlaaDelFinElFactorEsUno() {
        assertEquals(1.0, AtmosphericPerspective.factorDeNiebla(1000, 100, 500));
    }

    @Test
    void elFactorCreceMonotonamenteEntreInicioYFin() {
        double f1 = AtmosphericPerspective.factorDeNiebla(150, 100, 500);
        double f2 = AtmosphericPerspective.factorDeNiebla(300, 100, 500);
        double f3 = AtmosphericPerspective.factorDeNiebla(450, 100, 500);

        assertTrue(f1 < f2, "El factor debería aumentar con la distancia");
        assertTrue(f2 < f3, "El factor debería seguir aumentando con la distancia");
    }

    @Test
    void mezclarConCieloFactorCeroDaElColorOriginal() {
        AtmosphericPerspective.ColorMezclado resultado =
                AtmosphericPerspective.mezclarConCielo(10, 20, 30, 200, 210, 220, 0.0);

        assertEquals(10, resultado.r());
        assertEquals(20, resultado.g());
        assertEquals(30, resultado.b());
    }

    @Test
    void mezclarConCieloFactorUnoDaElColorDeCieloCompleto() {
        AtmosphericPerspective.ColorMezclado resultado =
                AtmosphericPerspective.mezclarConCielo(10, 20, 30, 200, 210, 220, 1.0);

        assertEquals(200, resultado.r());
        assertEquals(210, resultado.g());
        assertEquals(220, resultado.b());
    }

    @Test
    void colorFinalCombinaFactorYMezclaCorrectamente() {
        // Muy lejos: debería estar prácticamente en el color de cielo.
        AtmosphericPerspective.ColorMezclado resultado = AtmosphericPerspective.colorFinal(
                50, 100, 50, 10000, 500, 5000, 180, 200, 230);

        assertEquals(180, resultado.r());
        assertEquals(200, resultado.g());
        assertEquals(230, resultado.b());
    }
}
