package com.example.minecraftlodmod.generation;

import com.example.minecraftlodmod.core.SuperVoxel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.material.MapColor;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link SectionExtractor.LectorSeccion} sobre una sección real del mundo.
 *
 * Se construye con {@link #capturar} en el hilo que es dueño del chunk
 * (hilo del servidor), copiando la paleta de bloques y las capas de luz:
 * la copia es barata (paleta + arrays empaquetados) y deja al lector
 * independiente del chunk, así la extracción corre en el pool de
 * {@code GenerationTaskScheduler} sin carreras contra el juego.
 */
public final class LectorSeccionMinecraft implements SectionExtractor.LectorSeccion {

    private final PalettedContainer<BlockState> estados;
    private final boolean soloAire;
    // Luz de la sección y de la de arriba (la capa de arriba ilumina la cara
    // superior de la fila y=15). Null si el motor de luz no la tiene todavía.
    private final DataLayer cielo, bloque, cieloArriba, bloqueArriba;

    private LectorSeccionMinecraft(PalettedContainer<BlockState> estados, boolean soloAire,
                                   DataLayer cielo, DataLayer bloque,
                                   DataLayer cieloArriba, DataLayer bloqueArriba) {
        this.estados = estados;
        this.soloAire = soloAire;
        this.cielo = cielo;
        this.bloque = bloque;
        this.cieloArriba = cieloArriba;
        this.bloqueArriba = bloqueArriba;
    }

    /** Una sección capturada, con su posición en coordenadas de sección. */
    public record Captura(int seccionX, int seccionY, int seccionZ, LectorSeccionMinecraft lector) {
        public SectionExtractor.SeccionExtraida extraer() {
            return SectionExtractor.extraer(lector, seccionX, seccionY, seccionZ);
        }
    }

    /**
     * Captura todas las secciones no vacías del chunk. Llamar desde el hilo
     * dueño del chunk; el resultado se puede procesar en cualquier hilo.
     * Recorre el rango vertical real del chunk (no asume -64..320).
     */
    public static List<Captura> capturar(Level level, ChunkAccess chunk) {
        LevelChunkSection[] secciones = chunk.getSections();
        List<Captura> capturas = new ArrayList<>(secciones.length);
        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;
        var luzCielo = level.getLightEngine().getLayerListener(LightLayer.SKY);
        var luzBloque = level.getLightEngine().getLayerListener(LightLayer.BLOCK);

        for (int i = 0; i < secciones.length; i++) {
            LevelChunkSection seccion = secciones[i];
            if (seccion == null || seccion.hasOnlyAir()) {
                continue;
            }
            int seccionY = chunk.getSectionYFromSectionIndex(i);
            SectionPos pos = SectionPos.of(chunkX, seccionY, chunkZ);
            SectionPos arriba = SectionPos.of(chunkX, seccionY + 1, chunkZ);
            capturas.add(new Captura(chunkX, seccionY, chunkZ, new LectorSeccionMinecraft(
                    seccion.getStates().copy(), false,
                    copiar(luzCielo.getDataLayerData(pos)),
                    copiar(luzBloque.getDataLayerData(pos)),
                    copiar(luzCielo.getDataLayerData(arriba)),
                    copiar(luzBloque.getDataLayerData(arriba)))));
        }
        return capturas;
    }

    private static DataLayer copiar(DataLayer capa) {
        return capa == null ? null : capa.copy();
    }

    @Override
    public boolean soloAire() {
        return soloAire;
    }

    @Override
    public boolean homogenea() {
        // count() recorre la paleta, no los 4096 bloques uno por uno contra
        // el mundo: con un único estado en la paleta la sección es homogénea.
        int[] distintos = {0};
        estados.count((estado, cantidad) -> distintos[0]++);
        return distintos[0] == 1;
    }

    @Override
    public SuperVoxel voxel(int x, int y, int z) {
        BlockState estado = estados.get(x, y, z);
        SuperVoxel.Material material = material(estado);
        if (material == SuperVoxel.Material.AIRE) {
            return new SuperVoxel((byte) 0, (byte) 0, (byte) 0, (byte) y, material, (byte) 0);
        }
        // EmptyBlockGetter: getMapColor no puede tocar el mundo desde otro
        // hilo; los bloques que dependen de la posición caen a su color base.
        int rgb = estado.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col;
        return new SuperVoxel((byte) (rgb >> 16), (byte) (rgb >> 8), (byte) rgb, (byte) y, material, (byte) 0)
                .conLuzHorneada(luz(x, y, z));
    }

    /**
     * Luz que recibe la cara expuesta del bloque: la del bloque de arriba,
     * que es la que ve la superficie del terreno desde el cielo.
     */
    private int luz(int x, int y, int z) {
        DataLayer capaCielo = y < SectionExtractor.LADO - 1 ? cielo : cieloArriba;
        DataLayer capaBloque = y < SectionExtractor.LADO - 1 ? bloque : bloqueArriba;
        int ly = (y + 1) & 15;
        // Sin datos de luz del cielo (motor de luz todavía no corrió, o
        // sección por encima de todo): asumir cielo abierto.
        int deCielo = capaCielo == null ? 15 : capaCielo.get(x, ly, z);
        int deBloque = capaBloque == null ? 0 : capaBloque.get(x, ly, z);
        return Math.max(deCielo, deBloque);
    }

    private static SuperVoxel.Material material(BlockState estado) {
        if (estado.isAir()) {
            return SuperVoxel.Material.AIRE;
        }
        if (estado.getBlock() instanceof LiquidBlock && estado.getFluidState().is(FluidTags.WATER)) {
            return SuperVoxel.Material.AGUA;
        }
        if (estado.is(BlockTags.LEAVES) || estado.is(BlockTags.REPLACEABLE_BY_TREES)
                || estado.is(BlockTags.FLOWERS) || estado.is(BlockTags.SAPLINGS)
                || estado.is(BlockTags.CROPS)) {
            return SuperVoxel.Material.VEGETACION;
        }
        // Invisible en el mapa vanilla (vidrio, barreras, luz): invisible en el LOD.
        if (estado.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) == MapColor.NONE) {
            return SuperVoxel.Material.AIRE;
        }
        return SuperVoxel.Material.SOLIDO;
    }
}
