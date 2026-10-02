package com.example.minecraftlodmod.generation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunksEnRamTest {

    private static final long GB = 1024L * 1024 * 1024;

    @Test
    void presupuestoEsLaRamDelLodConTopeDeUnCuartoDelHeap() {
        assertEquals(500, ChunksEnRam.presupuestoMb(500, 4 * GB));
        assertEquals(1024, ChunksEnRam.presupuestoMb(4000, 4 * GB));
    }

    @Test
    void masRamDaColchonMasAnchoYEntraEnLaMitad() {
        int chico = ChunksEnRam.margenPara(12, 250);
        int grande = ChunksEnRam.margenPara(12, 1500);
        assertTrue(grande > chico, chico + " vs " + grande);
        for (int mb : new int[]{250, 500, 1500}) {
            int m = ChunksEnRam.margenPara(12, mb);
            assertTrue(m == ChunksEnRam.MARGEN_MIN
                    || ChunksEnRam.chunksColchon(12, m) <= ChunksEnRam.maximoChunks(mb) / 2);
        }
    }

    @Test
    void colchonConMargenMinimoYMaximo() {
        assertEquals(ChunksEnRam.MARGEN_MIN, ChunksEnRam.margenPara(12, 1));
        assertEquals(ChunksEnRam.MARGEN_MAX, ChunksEnRam.margenPara(2, 100_000));
        assertEquals(41 * 41 - 25 * 25, ChunksEnRam.chunksColchon(12, 8));
    }
}
