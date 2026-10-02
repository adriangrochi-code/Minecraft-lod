package com.example.minecraftlodmod.cubico;

import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class SuperficieExactaTest {

    @Test
    void extremosEnLasEsquinasDeCeldaDelChunk() {
        // Superficie con el máximo en la esquina (x0+12, z0+4) y el mínimo en (x0+16, z0+16)
        ChunkPos pos = new ChunkPos(-3, 7);
        int x0 = pos.getMinBlockX(), z0 = pos.getMinBlockZ();
        int[] sup = GeneracionVertical.superficieExacta(pos, (x, z) -> {
            if (x == x0 + 12 && z == z0 + 4) return 900;
            if (x == x0 + 16 && z == z0 + 16) return -1200;
            return 100 + (x - x0) - (z - z0);
        });
        assertArrayEquals(new int[]{-1200, 900}, sup);
    }
}
