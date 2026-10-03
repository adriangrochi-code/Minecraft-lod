package com.example.minecraftlodmod.network;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Control server-side de los pedidos de nodos (lógica pura, testeable):
 *
 * - Radio: solo se sirven nodos dentro de {@code radioMaximoChunks} del
 *   jugador, en horizontal. Además de acotar costo, fija cuánta
 *   "información revelada" del relieve lejano puede sacar un cliente
 *   (punto abierto de la sección 14): nunca más que el radio LOD del
 *   preset del servidor.
 * - Ritmo: token bucket por jugador ({@code nodosPorSegundo}, ráfaga de
 *   {@code rafagaMaxima}). Un cliente modificado no puede usar al servidor
 *   para leer el disco a máxima velocidad.
 * - Tamaño de lote: pedidos con más de {@link #MAX_NODOS_POR_PEDIDO} se
 *   rechazan enteros (un cliente legítimo nunca los manda).
 *
 * No es thread-safe: se usa desde el hilo principal del servidor.
 */
public final class LimitadorPedidos {

    public static final int MAX_NODOS_POR_PEDIDO = 64;

    private final int radioMaximoChunks;
    private final double nodosPorSegundo;
    private final double rafagaMaxima;
    private final Map<UUID, Balde> baldes = new HashMap<>();

    private static final class Balde {
        double fichas;
        long ultimoNanos;
    }

    public LimitadorPedidos(int radioMaximoChunks, double nodosPorSegundo, double rafagaMaxima) {
        if (radioMaximoChunks < 0 || nodosPorSegundo <= 0 || rafagaMaxima < 1) {
            throw new IllegalArgumentException("Parámetros de límite inválidos");
        }
        this.radioMaximoChunks = radioMaximoChunks;
        this.nodosPorSegundo = nodosPorSegundo;
        this.rafagaMaxima = rafagaMaxima;
    }

    /** true si el nodo está dentro del radio permitido alrededor del chunk del jugador. */
    public boolean dentroDelRadio(NodoId nodo, int chunkJugadorX, int chunkJugadorZ) {
        return Math.abs((long) nodo.seccionX() - chunkJugadorX) <= radioMaximoChunks
                && Math.abs((long) nodo.seccionZ() - chunkJugadorZ) <= radioMaximoChunks;
    }

    /**
     * Consume fichas para {@code cantidad} nodos. Si no alcanzan, no
     * consume nada y devuelve false: el pedido entero se descarta y el
     * cliente lo reintenta más tarde.
     */
    public boolean consumir(UUID jugador, int cantidad, long ahoraNanos) {
        if (cantidad <= 0) {
            return true;
        }
        if (cantidad > MAX_NODOS_POR_PEDIDO) {
            return false;
        }
        Balde balde = baldes.computeIfAbsent(jugador, id -> {
            Balde nuevo = new Balde();
            nuevo.fichas = rafagaMaxima;
            nuevo.ultimoNanos = ahoraNanos;
            return nuevo;
        });
        double segundos = Math.max(0, ahoraNanos - balde.ultimoNanos) / 1e9;
        balde.fichas = Math.min(rafagaMaxima, balde.fichas + segundos * nodosPorSegundo);
        balde.ultimoNanos = ahoraNanos;
        if (balde.fichas < cantidad) {
            return false;
        }
        balde.fichas -= cantidad;
        return true;
    }

    /** Liberar el estado de un jugador al desconectarse. */
    public void olvidar(UUID jugador) {
        baldes.remove(jugador);
    }
}
