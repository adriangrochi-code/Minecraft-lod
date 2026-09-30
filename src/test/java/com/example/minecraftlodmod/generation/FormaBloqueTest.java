package com.example.minecraftlodmod.generation;

import com.example.minecraftlodmod.core.SuperVoxel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void laNieveFinaDejaLasHojasComoSonYLasMarcaNevadas() {
        PalettedContainer<BlockState> estados = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY,
                Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES);
        BlockState hojas = Blocks.SPRUCE_LEAVES.defaultBlockState();
        estados.set(0, 0, 0, hojas);
        estados.set(0, 1, 0, Blocks.SNOW.defaultBlockState());
        estados.set(1, 0, 0, Blocks.STONE.defaultBlockState());
        estados.set(1, 1, 0, Blocks.WHITE_CARPET.defaultBlockState());
        LectorSeccionMinecraft lector = LectorSeccionMinecraft.deEstados(estados);

        SuperVoxel conNieve = lector.voxel(0, 0, 0);
        assertTrue(conNieve.nevado());
        assertEquals(Block.getId(hojas), conNieve.idEstado(), "los costados siguen siendo hojas");
        assertEquals(SuperVoxel.Material.AIRE, lector.voxel(0, 1, 0).material(), "la capa no es un cubo");

        SuperVoxel conAlfombra = lector.voxel(1, 0, 0);
        assertFalse(conAlfombra.nevado());
        assertEquals(Block.getId(Blocks.WHITE_CARPET.defaultBlockState()), conAlfombra.idEstado());
    }
}
