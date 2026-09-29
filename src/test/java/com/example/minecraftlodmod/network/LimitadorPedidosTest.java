package com.example.minecraftlodmod.network;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LimitadorPedidosTest {

    private static final long SEGUNDO = 1_000_000_000L;

    @Test
    void soloSirveNodosDentroDelRadio() {
        LimitadorPedidos limitador = new LimitadorPedidos(10, 100, 100);
        assertTrue(limitador.dentroDelRadio(new NodoId(0, 110, 4, -90), 100, -100));
        assertFalse(limitador.dentroDelRadio(new NodoId(0, 111, 4, -100), 100, -100));
        assertFalse(limitador.dentroDelRadio(new NodoId(0, 100, 4, -111), 100, -100));
    }

    @Test
    void laRafagaSeAgotaYSeRecuperaConElTiempo() {
        LimitadorPedidos limitador = new LimitadorPedidos(10, 10, 20);
        UUID jugador = UUID.randomUUID();

        assertTrue(limitador.consumir(jugador, 20, 0));
        assertFalse(limitador.consumir(jugador, 1, 0), "Ráfaga agotada");
        assertFalse(limitador.consumir(jugador, 10, SEGUNDO / 2), "Medio segundo recupera 5, no 10");
        assertTrue(limitador.consumir(jugador, 10, SEGUNDO), "Un segundo recupera 10");
    }

    @Test
    void laRecuperacionNoSuperaLaRafaga() {
        LimitadorPedidos limitador = new LimitadorPedidos(10, 10, 20);
        UUID jugador = UUID.randomUUID();
        assertTrue(limitador.consumir(jugador, 1, 0));
        assertFalse(limitador.consumir(jugador, 21, 1000 * SEGUNDO));
        assertTrue(limitador.consumir(jugador, 20, 1000 * SEGUNDO));
    }

    @Test
    void unPedidoRechazadoNoConsumeFichas() {
        LimitadorPedidos limitador = new LimitadorPedidos(10, 10, 20);
        UUID jugador = UUID.randomUUID();
        assertFalse(limitador.consumir(jugador, LimitadorPedidos.MAX_NODOS_POR_PEDIDO + 1, 0));
        assertTrue(limitador.consumir(jugador, 20, 0));
    }

    @Test
    void cadaJugadorTieneSuPropioBalde() {
        LimitadorPedidos limitador = new LimitadorPedidos(10, 10, 20);
        assertTrue(limitador.consumir(UUID.randomUUID(), 20, 0));
        assertTrue(limitador.consumir(UUID.randomUUID(), 20, 0));
    }

    @Test
    void nodoIdRechazaNivelesFueraDeRango() {
        assertThrows(IllegalArgumentException.class, () -> new NodoId(5, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new NodoId(-1, 0, 0, 0));
    }
}
