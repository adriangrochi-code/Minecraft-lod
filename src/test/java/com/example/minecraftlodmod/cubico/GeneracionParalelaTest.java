package com.example.minecraftlodmod.cubico;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneracionParalelaTest {

    @Test
    void nueveCandadosDistintosYOrdenados() {
        for (int[] c : new int[][]{{0, 0}, {-1, -1}, {63, 64}, {-1000, 37}}) {
            int[] indices = GeneracionParalela.indicesVecinos(c[0], c[1]);
            assertEquals(9, indices.length);
            for (int i = 1; i < indices.length; i++) {
                assertTrue(indices[i - 1] < indices[i]);
            }
        }
    }

    @Test
    void vecinosQueSePisanCompartenCandado() {
        // Dos features a 2 chunks escriben ambas el chunk del medio: tienen que excluirse.
        Set<Integer> a = new HashSet<>();
        for (int i : GeneracionParalela.indicesVecinos(0, 0)) {
            a.add(i);
        }
        boolean comparten = false;
        for (int i : GeneracionParalela.indicesVecinos(2, 0)) {
            comparten |= a.contains(i);
        }
        assertTrue(comparten);
    }
}
