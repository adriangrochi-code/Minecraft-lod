package com.example.minecraftlodmod.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class RegionHeaderTest {

    @Test
    void laCopiaTieneLosMismosNodosYEsIndependiente() {
        RegionHeader original = new RegionHeader(3, -7, (byte) 0, 42L);
        original.registrarNodo(1L, 0, 10);
        original.registrarNodo(2L, 10, 30);

        RegionHeader copia = original.copia();
        copia.registrarNodo(3L, 40, 5);

        assertArrayEquals(new long[]{10, 30}, copia.buscarNodo(2L));
        assertEquals(3, copia.cantidadNodos());
        assertEquals(2, original.cantidadNodos(), "registrar en la copia no toca el original");
        assertNull(original.buscarNodo(3L));
        assertFalse(original.tieneNodo(3L));
        assertEquals(42L, copia.hashFuente);
    }

    @Test
    void bytesVivosSumaLosTamanos() {
        RegionHeader header = new RegionHeader(0, 0, (byte) 0, 0L);
        assertEquals(0, header.bytesVivos());
        header.registrarNodo(1L, 0, 10);
        header.registrarNodo(2L, 10, 30);
        header.registrarNodo(1L, 40, 7); // reemplaza: cuenta la versión nueva
        assertEquals(37, header.bytesVivos());
    }
}
