package com.example.minecraftlodmod.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FundidoNivelesTest {

    @Test
    void laEntradaVaDeCeroAUnoEnLaDuracion() {
        long t0 = 1_000_000_000L;
        assertEquals(1f, FundidoNiveles.fraccionEntrada(t0, 0), "Sin fundido: entera");
        assertEquals(0f, FundidoNiveles.fraccionEntrada(t0, t0));
        assertEquals(0.5f, FundidoNiveles.fraccionEntrada(t0 + FundidoNiveles.DURACION_NANOS / 2, t0), 1e-6);
        assertEquals(1f, FundidoNiveles.fraccionEntrada(t0 + 2 * FundidoNiveles.DURACION_NANOS, t0));
    }

    @Test
    void solapamientoDeCuadrados() {
        assertTrue(FundidoNiveles.solapan(0, 0, 64, 32, 32, 64));
        assertTrue(FundidoNiveles.solapan(0, 0, 256, 64, 64, 64), "Una celda chica dentro de una tesela");
        assertFalse(FundidoNiveles.solapan(0, 0, 64, 64, 0, 64), "Lado con lado no es solaparse");
        assertFalse(FundidoNiveles.solapan(0, 0, 64, 0, -64, 64));
    }
}
