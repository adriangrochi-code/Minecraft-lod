package com.example.minecraftlodmod.generation;

import com.example.minecraftlodmod.core.SuperVoxel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HierarchicalReducerTest {

    private static SuperVoxel voxel(int r, SuperVoxel.Material material) {
        return new SuperVoxel((byte) r, (byte) 0, (byte) 0, (byte) 100, material, (byte) 0);
    }

    @Test
    void reducirUnCuboUniformeDaElMismoColorPromedio() {
        int lado = 4; // 4^3 = 64 supervóxeles, todos iguales
        SuperVoxel[] entrada = new SuperVoxel[lado * lado * lado];
        SuperVoxel referencia = voxel(80, SuperVoxel.Material.SOLIDO);
        java.util.Arrays.fill(entrada, referencia);

        SuperVoxel[] salida = HierarchicalReducer.reducir(entrada, lado);

        assertEquals(2 * 2 * 2, salida.length, "4^3 reducido a 2x2x2 debería dar 2^3 elementos");
        for (SuperVoxel v : salida) {
            assertEquals(referencia.r(), v.r());
            assertEquals(SuperVoxel.Material.SOLIDO, v.material());
        }
    }

    @Test
    void elMaterialDominanteGanaLaVotacion() {
        // Bloque 2x2x2 con 5 SOLIDO y 3 AGUA: debería ganar SOLIDO.
        SuperVoxel[] entrada = {
                voxel(1, SuperVoxel.Material.SOLIDO), voxel(1, SuperVoxel.Material.SOLIDO),
                voxel(1, SuperVoxel.Material.SOLIDO), voxel(1, SuperVoxel.Material.AGUA),
                voxel(1, SuperVoxel.Material.SOLIDO), voxel(1, SuperVoxel.Material.AGUA),
                voxel(1, SuperVoxel.Material.SOLIDO), voxel(1, SuperVoxel.Material.AGUA),
        };

        SuperVoxel[] salida = HierarchicalReducer.reducir(entrada, 2);

        assertEquals(1, salida.length);
        assertEquals(SuperVoxel.Material.SOLIDO, salida[0].material());
    }

    @Test
    void detectaBloqueHomogeneoCorrectamente() {
        SuperVoxel[] entrada = new SuperVoxel[8];
        java.util.Arrays.fill(entrada, voxel(50, SuperVoxel.Material.SOLIDO));

        assertTrue(HierarchicalReducer.esBloqueHomogeneo(entrada, 2, 0, 0, 0));

        entrada[7] = voxel(99, SuperVoxel.Material.AGUA);
        assertTrue(!HierarchicalReducer.esBloqueHomogeneo(entrada, 2, 0, 0, 0));
    }
}
