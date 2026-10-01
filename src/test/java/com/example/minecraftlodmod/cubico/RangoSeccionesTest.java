package com.example.minecraftlodmod.cubico;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RangoSeccionesTest {

    @Test
    void alrededorRespetaLaAlturaDelMundo() {
        assertEquals(new RangoSecciones(-4, 12), RangoSecciones.alrededor(4, 8, -4, 19));
        assertEquals(new RangoSecciones(7, 19), RangoSecciones.alrededor(15, 8, -4, 19));
        assertEquals(new RangoSecciones(2, 6), RangoSecciones.alrededor(4, 2, -4, 19));
    }

    @Test
    void empaquetaConNegativos() {
        RangoSecciones r = new RangoSecciones(-128, -3);
        assertEquals(r, RangoSecciones.desempaquetar(r.empaquetado()));
        assertTrue(r.contiene(-128) && r.contiene(-3) && !r.contiene(-2));
    }

    @Test
    void moverCargaLoNuevoYConservaUnaSeccionDeMas() {
        RangoSecciones actual = new RangoSecciones(0, 16);
        // Subió una sección: se agrega arriba, abajo queda la de más (histéresis).
        assertEquals(new RangoSecciones(0, 17), RangoSecciones.mover(actual, new RangoSecciones(1, 17)));
        // Subió dos: la de más abajo ya está a dos de distancia y se va.
        assertEquals(new RangoSecciones(2, 18), RangoSecciones.mover(actual, new RangoSecciones(2, 18)));
        // Bajó una: abajo se agrega, arriba queda la de más.
        assertEquals(new RangoSecciones(-1, 16), RangoSecciones.mover(actual, new RangoSecciones(-1, 15)));
    }

    @Test
    void teletransporteEsDirectamenteElObjetivo() {
        assertEquals(new RangoSecciones(40, 56),
                RangoSecciones.mover(new RangoSecciones(0, 16), new RangoSecciones(40, 56)));
    }
}
