package com.example.minecraftlodmod.generation;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EspiralChunksTest {

    @Test
    void recorreCadaChunkDelCirculoUnaSolaVezDelCentroHaciaAfuera() {
        int radio = 12;
        EspiralChunks e = new EspiralChunks(radio);
        Set<Long> vistos = new HashSet<>();
        int anilloAnterior = 0;
        while (e.siguiente()) {
            assertTrue(vistos.add(((long) e.dx() << 32) ^ (e.dz() & 0xFFFFFFFFL)), "Repetido: " + e.dx() + "," + e.dz());
            assertTrue((long) e.dx() * e.dx() + (long) e.dz() * e.dz() <= (long) radio * radio);
            int anillo = Math.max(Math.abs(e.dx()), Math.abs(e.dz()));
            assertEquals(e.anillo(), anillo);
            assertTrue(anillo >= anilloAnterior, "Nunca vuelve hacia adentro");
            anilloAnterior = anillo;
        }
        int esperados = 0;
        for (int x = -radio; x <= radio; x++) {
            for (int z = -radio; z <= radio; z++) {
                if (x * x + z * z <= radio * radio) esperados++;
            }
        }
        assertEquals(esperados, vistos.size(), "Cubre todo el círculo");
        assertFalse(e.siguiente(), "Terminado sigue terminado");
    }

    @Test
    void empiezaPorElCentro() {
        EspiralChunks e = new EspiralChunks(3);
        assertTrue(e.siguiente());
        assertEquals(0, e.dx());
        assertEquals(0, e.dz());
    }

    @Test
    void radioCeroEsSoloElCentro() {
        EspiralChunks e = new EspiralChunks(0);
        assertTrue(e.siguiente());
        assertFalse(e.siguiente());
    }
}
