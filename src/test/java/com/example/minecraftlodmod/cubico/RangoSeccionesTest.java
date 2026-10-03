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

    @Test
    void laFranjaVerticalQueDibujaVanillaSeAchicaUnaSeccionDeCadaLado() {
        // Cámara a y=260 con 8 chunks: vanilla dibuja orígenes de sección entre 132 y 388
        // (secciones 9 a 24); el LOD le deja 10 a 23.
        assertEquals(new RangoSecciones(10, 23), RangoSecciones.verticalVisible(260, 8));
        // Cámara en y=-70: orígenes entre -198 y 58 → secciones -12 a 3, achicado -11 a 2.
        assertEquals(new RangoSecciones(-11, 2), RangoSecciones.verticalVisible(-70, 8));
    }

    @Test
    void laInterseccionEsNullSiNoSeTocan() {
        assertEquals(new RangoSecciones(5, 8), new RangoSecciones(0, 8).interseccion(new RangoSecciones(5, 20)));
        assertNull(new RangoSecciones(0, 4).interseccion(new RangoSecciones(5, 20)));
    }
}
