package com.example.minecraftlodmod.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SuperVoxelLuzTest {

    @Test
    void luzHorneadaPorDefectoEsCero() {
        SuperVoxel v = new SuperVoxel((byte) 1, (byte) 2, (byte) 3, (byte) 64,
                SuperVoxel.Material.SOLIDO, (byte) 0);
        assertEquals(0, v.luzHorneada());
    }

    @Test
    void conLuzHorneadaPreservaElRestoDeCamposYElBitDeHomogeneo() {
        SuperVoxel original = new SuperVoxel((byte) 10, (byte) 20, (byte) 30, (byte) 64,
                SuperVoxel.Material.AGUA, (byte) 0b0000_0001); // homogéneo = true

        SuperVoxel conLuz = original.conLuzHorneada(12);

        assertEquals(12, conLuz.luzHorneada());
        assertTrue(conLuz.esHomogeneo(), "El bit de homogéneo no debería perderse al setear la luz");
        assertEquals(original.r(), conLuz.r());
        assertEquals(original.material(), conLuz.material());
    }

    @Test
    void rechazaValoresDeLuzFueraDeRango() {
        SuperVoxel v = new SuperVoxel((byte) 0, (byte) 0, (byte) 0, (byte) 0,
                SuperVoxel.Material.SOLIDO, (byte) 0);
        assertThrows(IllegalArgumentException.class, () -> v.conLuzHorneada(16));
        assertThrows(IllegalArgumentException.class, () -> v.conLuzHorneada(-1));
    }
}
