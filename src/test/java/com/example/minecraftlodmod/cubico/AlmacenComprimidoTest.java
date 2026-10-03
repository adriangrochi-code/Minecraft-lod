package com.example.minecraftlodmod.cubico;

import net.minecraft.util.SimpleBitStorage;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlmacenComprimidoTest {

    private static SimpleBitStorage aleatorio(int bits, long semilla) {
        SimpleBitStorage s = new SimpleBitStorage(bits, 4096);
        Random r = new Random(semilla);
        for (int i = 0; i < 4096; i++) {
            // Como una sección de roca: casi todo el índice 0, con algo de menas.
            s.set(i, r.nextInt(20) == 0 ? r.nextInt(1 << bits) : 0);
        }
        return s;
    }

    @Test
    void comprimirYLeerDaLoMismo() {
        SimpleBitStorage original = aleatorio(4, 1);
        long[] copia = original.getRaw().clone();
        AlmacenComprimido a = new AlmacenComprimido(original);
        int bytes = a.comprimir();
        assertTrue(a.estaComprimido());
        assertTrue(bytes < copia.length * 8 / 2, "debería comprimir a menos de la mitad: " + bytes);
        assertArrayEquals(copia, a.getRaw());
        assertTrue(a.estaComprimido(), "getRaw (guardar/paquete) no lo deja descomprimido");
        SimpleBitStorage referencia = new SimpleBitStorage(4, 4096, copia);
        for (int i = 0; i < 4096; i++) {
            assertEquals(referencia.get(i), a.get(i));
        }
        assertFalse(a.estaComprimido());
    }

    @Test
    void escribirDespuesDeComprimirSeConserva() {
        SimpleBitStorage original = aleatorio(5, 2);
        AlmacenComprimido a = new AlmacenComprimido(original);
        a.comprimir();
        a.set(100, 17);
        assertEquals(17, a.getAndSet(200, 3) == 0 ? a.get(100) : a.get(100));
        a.comprimir();
        assertEquals(17, a.get(100));
        assertEquals(3, a.get(200));
        int[] todo = new int[4096];
        a.unpack(todo);
        assertEquals(17, todo[100]);
        assertEquals(3, a.copy().get(200));
    }
}
