package com.example.minecraftlodmod.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PackedVoxelBufferTest {

    private static SuperVoxel voxel(int r, int luz) {
        return new SuperVoxel((byte) r, (byte) 5, (byte) 9, (byte) 64,
                SuperVoxel.Material.SOLIDO, (byte) 0).conLuzHorneada(luz);
    }

    @Test
    void setYGetSonRoundTrip() {
        PackedVoxelBuffer buf = new PackedVoxelBuffer(4);
        SuperVoxel v = voxel(42, 10);

        buf.set(2, v);
        SuperVoxel leido = buf.get(2);

        assertEquals(v, leido);
    }

    @Test
    void accesoDirectoAMaterialYLuzCoincideConElObjetoCompleto() {
        PackedVoxelBuffer buf = new PackedVoxelBuffer(1);
        SuperVoxel v = voxel(1, 7);
        buf.set(0, v);

        assertEquals(v.material(), buf.materialEn(0));
        assertEquals(v.luzHorneada(), buf.luzHorneadaEn(0));
    }

    @Test
    void conversionDesdeYHaciaArrayDeObjetosEsEquivalente() {
        SuperVoxel[] original = {voxel(1, 1), voxel(2, 5), voxel(3, 15)};

        PackedVoxelBuffer buf = PackedVoxelBuffer.desde(original);
        SuperVoxel[] reconstruido = buf.aArrayDeObjetos();

        assertArrayEquals(original, reconstruido);
    }

    @Test
    void indiceFueraDeRangoLanzaExcepcion() {
        PackedVoxelBuffer buf = new PackedVoxelBuffer(2);
        org.junit.jupiter.api.Assertions.assertThrows(IndexOutOfBoundsException.class,
                () -> buf.get(5));
    }
}
