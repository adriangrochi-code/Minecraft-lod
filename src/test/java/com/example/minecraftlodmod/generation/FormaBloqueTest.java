package com.example.minecraftlodmod.generation;

import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Cómo se representa cada bloque en el LOD según su forma (bloques reales del juego). */
class FormaBloqueTest {

    @Test
    void losBloquesMacizosSonCubos() {
        assertEquals(LectorSeccionMinecraft.Forma.NORMAL, LectorSeccionMinecraft.forma(Blocks.STONE.defaultBlockState()));
        assertEquals(LectorSeccionMinecraft.Forma.NORMAL, LectorSeccionMinecraft.forma(Blocks.OAK_LEAVES.defaultBlockState()));
        assertEquals(LectorSeccionMinecraft.Forma.NORMAL, LectorSeccionMinecraft.forma(Blocks.WATER.defaultBlockState()));
    }

    @Test
    void lasPlantasYAntorchasNoSonCubos() {
        assertEquals(LectorSeccionMinecraft.Forma.DECORACION, LectorSeccionMinecraft.forma(Blocks.SHORT_GRASS.defaultBlockState()));
        assertEquals(LectorSeccionMinecraft.Forma.DECORACION, LectorSeccionMinecraft.forma(Blocks.POPPY.defaultBlockState()));
        assertEquals(LectorSeccionMinecraft.Forma.DECORACION, LectorSeccionMinecraft.forma(Blocks.TORCH.defaultBlockState()));
    }

    @Test
    void laNieveFinaYLasAlfombrasSonCubiertas() {
        assertEquals(LectorSeccionMinecraft.Forma.CUBIERTA, LectorSeccionMinecraft.forma(Blocks.SNOW.defaultBlockState()));
        assertEquals(LectorSeccionMinecraft.Forma.CUBIERTA, LectorSeccionMinecraft.forma(Blocks.WHITE_CARPET.defaultBlockState()));
    }

    @Test
    void lasPlantasSumergidasCuentanComoAgua() {
        assertEquals(LectorSeccionMinecraft.Forma.SUMERGIDA, LectorSeccionMinecraft.forma(Blocks.SEAGRASS.defaultBlockState()));
    }
}
