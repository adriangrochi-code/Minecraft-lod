package com.example.minecraftlodmod.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    void escrituraPorPartesDaLosMismosBytesYSeLeeIgual() throws Exception {
        RegionHeader header = new RegionHeader(-7, 12, (byte) 2, 0x1234_5678_9ABCL);
        // Más de 64 KB de tabla: cruza varias veces el buffer de escritura.
        for (int i = 0; i < 5000; i++) {
            header.registrarNodo(RegionHeader.claveNodo(i % 7, i, -i, i * 3), i * 1000L, 100 + i);
        }
        java.io.ByteArrayOutputStream salida = new java.io.ByteArrayOutputStream();
        header.escribirEn(java.nio.channels.Channels.newChannel(salida));
        assertArrayEquals(header.serializarHeader(), salida.toByteArray());

        RegionHeader leido = RegionHeader.leerDe(new java.io.DataInputStream(
                new java.io.ByteArrayInputStream(salida.toByteArray())));
        assertEquals(5000, leido.cantidadNodos());
        assertEquals(header.bytesVivos(), leido.bytesVivos());
        long[] ubicacion = leido.buscarNodo(RegionHeader.claveNodo(4999 % 7, 4999, -4999, 4999 * 3));
        assertArrayEquals(new long[]{4_999_000L, 5099}, ubicacion);
    }

    @Test
    void offsetsYTamanosGrandesEntranEmpaquetados() {
        RegionHeader header = new RegionHeader(0, 0, (byte) 0, 1);
        long offset = (1L << 38) + 5; // 256 GB: mucho más que cualquier región
        header.registrarNodo(9L, offset, RegionHeader.MAX_TAMANO);
        assertArrayEquals(new long[]{offset, RegionHeader.MAX_TAMANO}, header.buscarNodo(9L));
        assertNull(header.buscarNodo(10L));
        assertThrows(IllegalArgumentException.class, () -> header.registrarNodo(1L, 0, RegionHeader.MAX_TAMANO + 1));
    }
}
