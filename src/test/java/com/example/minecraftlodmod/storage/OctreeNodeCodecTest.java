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

    @org.junit.jupiter.api.Test
    void lasMarcasDelStoreNoSonNodos() {
        // Marca vieja de "chunk extraído" (vacía hasta 0.25.5, {1} después): comparte clave con un
        // nodo aproximado fino; leída como nodo rompía el armado de la celda.
        org.junit.jupiter.api.Assertions.assertFalse(OctreeNodeCodec.esNodo(null));
        org.junit.jupiter.api.Assertions.assertFalse(OctreeNodeCodec.esNodo(new byte[0]));
        org.junit.jupiter.api.Assertions.assertFalse(OctreeNodeCodec.esNodo(new byte[]{1}));
        org.junit.jupiter.api.Assertions.assertTrue(OctreeNodeCodec.esNodo(new byte[4]));
    }

    /** Nodo de nivel 0 variado (muchas corridas cortas): va con paleta. */
    private static SuperVoxel[] variado(int distintos) {
        SuperVoxel[] v = new SuperVoxel[4096];
        for (int i = 0; i < v.length; i++) {
            int k = (i * 7 + i / 13) % distintos;
            v[i] = new SuperVoxel((byte) k, (byte) (k >> 8), (byte) 20, (byte) SuperVoxel.LLENO,
                    SuperVoxel.Material.SOLIDO, (byte) 0, (short) k);
        }
        return v;
    }

    @Test
    void losNodosGrandesYVariadosVanConPaletaYSeLeenIgual() {
        SuperVoxel[] voxeles = variado(40);
        byte[] datos = OctreeNodeCodec.serializar(OctreeNode.mixto(0, 0, 0, 0, 16, voxeles), voxeles.length);
        assertEquals(OctreeNodeCodec.FLAG_PALETA, datos[1] & OctreeNodeCodec.FLAG_PALETA);
        assertEquals(4 + 40 * SuperVoxel.BYTES + 4096, datos.length, "un byte por vóxel con hasta 256 distintos");
        OctreeNodeCodec.NodoDeserializado leido = OctreeNodeCodec.deserializar(datos, 0, voxeles.length);
        assertArrayEquals(voxeles, leido.voxeles());
        org.junit.jupiter.api.Assertions.assertFalse(leido.homogeneo());
        assertEquals(datos.length, OctreeNodeCodec.tamanoSerializado(OctreeNode.mixto(0, 0, 0, 0, 16, voxeles), 4096));
    }

    @Test
    void conMasDe256DistintosLosIndicesSonDeDosBytes() {
        SuperVoxel[] voxeles = variado(700);
        byte[] datos = OctreeNodeCodec.serializar(OctreeNode.mixto(0, 0, 0, 0, 16, voxeles), voxeles.length);
        assertEquals(4 + 700 * SuperVoxel.BYTES + 2 * 4096, datos.length);
        assertArrayEquals(voxeles, OctreeNodeCodec.deserializar(datos, 0, voxeles.length).voxeles());
    }

    @Test
    void losChicosOUniformesSiguenConRle() {
        SuperVoxel[] chico = {voxel(1), voxel(2), voxel(3), voxel(4), voxel(5), voxel(6), voxel(7), voxel(8)};
        byte[] a = OctreeNodeCodec.serializar(OctreeNode.mixto(3, 0, 0, 0, 16, chico), chico.length);
        assertEquals(0, a[1] & OctreeNodeCodec.FLAG_PALETA);
        SuperVoxel[] capas = new SuperVoxel[4096];
        for (int i = 0; i < capas.length; i++) {
            capas[i] = voxel(i / 512); // 8 corridas largas
        }
        byte[] b = OctreeNodeCodec.serializar(OctreeNode.mixto(0, 0, 0, 0, 16, capas), capas.length);
        assertEquals(0, b[1] & OctreeNodeCodec.FLAG_PALETA);
        assertArrayEquals(capas, OctreeNodeCodec.deserializar(b, 0, capas.length).voxeles());
    }
}
