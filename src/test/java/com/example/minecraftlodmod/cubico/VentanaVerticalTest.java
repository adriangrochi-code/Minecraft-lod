package com.example.minecraftlodmod.cubico;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class VentanaVerticalTest {

    // Mundo alto de prueba: -1024..1023 = secciones -64..63.
    private static final int MIN = -64;
    private static final int MAX = 63;

    @Test
    void sinJugadoresSoloRecortaDebajoDeLaSuperficie() {
        RangoSecciones r = VentanaVertical.calcular(64, 100, new int[0], 4, false, 8, 8, MIN, MAX);
        assertEquals(new RangoSecciones(0, MAX), r);
    }

    @Test
    void recortarArribaUsaLaSuperficieMasAlta() {
        RangoSecciones r = VentanaVertical.calcular(64, 100, new int[0], 4, true, 8, 8, MIN, MAX);
        assertEquals(new RangoSecciones(0, 6 + 8), r);
    }

    @Test
    void unJugadorEnLaProfundidadEstiraLaFranja() {
        RangoSecciones r = VentanaVertical.calcular(64, 100, new int[]{-800}, 4, true, 8, 8, MIN, MAX);
        assertEquals(new RangoSecciones(-50 - 8, 14), r);
    }

    @Test
    void unJugadorVolandoAltoEstiraHaciaArriba() {
        RangoSecciones r = VentanaVertical.calcular(64, 100, new int[]{900}, 4, true, 8, 8, MIN, MAX);
        assertEquals(new RangoSecciones(0, MAX), r);
    }

    @Test
    void sinSuperficieNoSeRecorta() {
        assertNull(VentanaVertical.calcular(Integer.MAX_VALUE, 0, new int[0], 4, true, 8, 8, MIN, MAX));
    }

    @Test
    void siCubreTodoElMundoNoHayVentana() {
        // Mundo vanilla (-4..19): superficie en 64, margen 4 → desde la sección 0... pero un jugador abajo lo cubre todo.
        assertNull(VentanaVertical.calcular(64, 100, new int[]{-60}, 4, false, 8, 8, -4, 19));
        assertEquals(new RangoSecciones(0, 19), VentanaVertical.calcular(64, 100, new int[0], 4, false, 8, 8, -4, 19));
    }
}
