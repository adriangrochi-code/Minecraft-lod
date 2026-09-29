package com.example.minecraftlodmod.storage;

import com.example.minecraftlodmod.core.OctreeNode;
import com.example.minecraftlodmod.core.SuperVoxel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OctreeNodeCodecTest {

    private static SuperVoxel voxel(int r) {
        return new SuperVoxel((byte) r, (byte) 10, (byte) 20, (byte) 64,
                SuperVoxel.Material.SOLIDO, (byte) 0);
    }

    @Test
    void nodoHomogeneoRoundTrip() {
        SuperVoxel v = voxel(42);
        OctreeNode nodo = OctreeNode.homogeneo(3, 0, 0, 0, 64, v);

        byte[] datos = OctreeNodeCodec.serializar(nodo, 8); // 8 = tamaño lógico (ej. 2x2x2)
        OctreeNodeCodec.NodoDeserializado leido = OctreeNodeCodec.deserializar(datos, 0, 8);

        assertTrue(leido.homogeneo());
        assertEquals(3, leido.nivelLod());
        assertEquals(8, leido.voxeles().length);
        for (SuperVoxel sv : leido.voxeles()) {
            assertEquals(v, sv);
        }
    }

    @Test
    void nodoMixtoRoundTrip() {
        SuperVoxel[] voxeles = {voxel(1), voxel(1), voxel(2), voxel(3), voxel(3), voxel(3)};
        OctreeNode nodo = OctreeNode.mixto(0, 0, 0, 0, 16, voxeles);

        byte[] datos = OctreeNodeCodec.serializar(nodo, voxeles.length);
        OctreeNodeCodec.NodoDeserializado leido = OctreeNodeCodec.deserializar(datos, 0, voxeles.length);

        assertEquals(0, leido.nivelLod());
        assertArrayEquals(voxeles, leido.voxeles());
    }
}
