package com.example.minecraftlodmod.generation;

import com.example.minecraftlodmod.core.SuperVoxel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.material.MapColor;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.VoxelShape;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
    /** Estados de la sección de arriba (para cubiertas sobre la fila y=15), o null si es solo aire. */
    private final PalettedContainer<BlockState> estadosArriba;

    /**
     * Cómo se representa un estado de bloque en el LOD, por su FORMA: el
     * LOD dibuja cubos, y dibujar como cubo lo que no lo es (pasto, flores,
     * antorchas, nieve fina) llena el paisaje de "cubitos" que vanilla no
     * tiene.
     */
    enum Forma {
        /** Se dibuja como cubo. */
        NORMAL,
        /** Sin colisión y no es una capa: plantas, flores, antorchas, rieles. Se omite (queda el suelo). */
        DECORACION,
        /** Capa fina que cubre toda la base (nieve, alfombras): pinta la cara de arriba del bloque de abajo. */
        CUBIERTA,
        /** Decoración sumergida (algas, pasto marino): cuenta como agua, sin huecos bajo el agua. */
        SUMERGIDA
    }

    private static final Map<BlockState, Forma> FORMAS = new ConcurrentHashMap<>();
    private static final BlockState AGUA = Blocks.WATER.defaultBlockState();

    static Forma forma(BlockState estado) {
        return FORMAS.computeIfAbsent(estado, LectorSeccionMinecraft::calcularForma);
    }

    private static Forma calcularForma(BlockState estado) {
        if (estado.isAir() || estado.getBlock() instanceof LiquidBlock) {
            return Forma.NORMAL; // el aire y el agua/lava se resuelven por material
        }
        try {
            VoxelShape forma = estado.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            if (!forma.isEmpty() && forma.max(Direction.Axis.Y) <= 0.25
                    && forma.min(Direction.Axis.X) <= 0.01 && forma.max(Direction.Axis.X) >= 0.99
                    && forma.min(Direction.Axis.Z) <= 0.01 && forma.max(Direction.Axis.Z) >= 0.99) {
                return Forma.CUBIERTA;
            }
            if (estado.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()) {
                return estado.getFluidState().getType().isSame(Fluids.WATER) ? Forma.SUMERGIDA : Forma.DECORACION;
            }
        } catch (RuntimeException e) {
            // Bloque de un mod que necesita el mundo para su forma: como cubo.
        }
        return Forma.NORMAL;
    }
    private final boolean soloAire;
    // Luz de la sección y de la de arriba (la capa de arriba ilumina la cara
    // superior de la fila y=15). Null si el motor de luz no la tiene todavía.
    private final DataLayer cielo, bloque, cieloArriba, bloqueArriba;
    /** Biomas de la sección (grilla 4×4×4, índice (x*4+y)*4+z), para el tinte de {@link ColoresBloque}. */
    private final Biome[] biomas;
    private final int origenX, origenZ;

    private LectorSeccionMinecraft(PalettedContainer<BlockState> estados, PalettedContainer<BlockState> estadosArriba,
                                   boolean soloAire,
                                   DataLayer cielo, DataLayer bloque,
                                   DataLayer cieloArriba, DataLayer bloqueArriba,
                                   Biome[] biomas, int origenX, int origenZ) {
        this.biomas = biomas;
        this.origenX = origenX;
        this.origenZ = origenZ;
        this.estados = estados;
        this.estadosArriba = estadosArriba;
        this.soloAire = soloAire;
        this.cielo = cielo;
        this.bloque = bloque;
        this.cieloArriba = cieloArriba;
        this.bloqueArriba = bloqueArriba;
    }

    /** Lector sobre estados ya armados, sin luz ni biomas (cielo abierto): para tests. */
    static LectorSeccionMinecraft deEstados(PalettedContainer<BlockState> estados) {
        return new LectorSeccionMinecraft(estados, null, false, null, null, null, null, new Biome[64], 0, 0);
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
            LevelChunkSection deArriba = i + 1 < secciones.length ? secciones[i + 1] : null;
            capturas.add(new Captura(chunkX, seccionY, chunkZ, new LectorSeccionMinecraft(
                    seccion.getStates().copy(),
                    deArriba == null || deArriba.hasOnlyAir() ? null : deArriba.getStates().copy(), false,
                    copiar(luzCielo.getDataLayerData(pos)),
                    copiar(luzBloque.getDataLayerData(pos)),
                    copiar(luzCielo.getDataLayerData(arriba)),
                    copiar(luzBloque.getDataLayerData(arriba)),
                    copiarBiomas(seccion), chunkX * 16, chunkZ * 16)));
        }
        return capturas;
    }

    /** 64 referencias: más barato que copiar el contenedor, y {@code recreate()} lo crea vacío. */
    private static Biome[] copiarBiomas(LevelChunkSection seccion) {
        Biome[] biomas = new Biome[64];
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 4; z++) {
                    biomas[(x * 4 + y) * 4 + z] = seccion.getNoiseBiome(x, y, z).value();
                }
            }
        }
        return biomas;
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
        if (distintos[0] != 1) {
            return false;
        }
        // Una cubierta encima cambia la cara de arriba: ya no es uniforme.
        if (estadosArriba != null) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    if (forma(estadosArriba.get(x, 0, z)) == Forma.CUBIERTA) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    @Override
    public SuperVoxel voxel(int x, int y, int z) {
        BlockState estado = estados.get(x, y, z);
        switch (forma(estado)) {
            case DECORACION, CUBIERTA -> estado = Blocks.AIR.defaultBlockState();
            case SUMERGIDA -> estado = AGUA;
            case NORMAL -> { }
        }
        SuperVoxel.Material material = material(estado);
        if (material == SuperVoxel.Material.AIRE) {
            return new SuperVoxel((byte) 0, (byte) 0, (byte) 0, (byte) y, material, (byte) 0);
        }
        // Alfombra encima: el bloque toma la cubierta. Nieve fina: el bloque queda como es
        // (hojas, pasto) y solo su cara de arriba se dibuja nevada (SuperVoxel.nevado).
        BlockState encima = y < SectionExtractor.LADO - 1 ? estados.get(x, y + 1, z)
                : estadosArriba != null ? estadosArriba.get(x, 0, z) : null;
        boolean nevado = false;
        if (encima != null && forma(encima) == Forma.CUBIERTA) {
            if (encima.is(Blocks.SNOW)) {
                nevado = true;
            } else {
                estado = encima;
            }
        }
        Biome bioma = biomas[((x >> 2) * 4 + (y >> 2)) * 4 + (z >> 2)];
        int rgb = ColoresBloque.rgb(estado, bioma, origenX + x, origenZ + z);
        return new SuperVoxel((byte) (rgb >> 16), (byte) (rgb >> 8), (byte) rgb, (byte) y, material, (byte) 0)
                .conLuzHorneada(luz(x, y, z))
                .conEstado(Block.getId(estado)) // para dibujar su textura; ids > 65535 quedan sin estado
                .conNevado(nevado);
    }

    /**
     * Luz horneada del bloque: la MÁXIMA de sus vecinos (arriba, costados y
     * abajo), es decir la luz del aire que toca alguna de sus caras. Antes
     * era solo la del bloque de arriba, y la cara de un acantilado quedaba
     * "a oscuras" aunque le dé el sol de costado. Un bloque enterrado (todos
     * sus vecinos sólidos) queda con luz 0: eso es lo que permite descartar
     * cuevas y caras enterradas en el render.
     *
     * Vecinos fuera de la sección: arriba se lee la sección de arriba; a los
     * costados no hay dato y se asume luz plena (no descartar caras de un
     * acantilado justo en el borde de un chunk); abajo, sin luz.
     */
    private int luz(int x, int y, int z) {
        int max = luzEn(x, y + 1, z);
        if (max == 15) {
            return 15;
        }
        max = Math.max(max, luzEn(x - 1, y, z));
        max = Math.max(max, luzEn(x + 1, y, z));
        max = Math.max(max, luzEn(x, y, z - 1));
        max = Math.max(max, luzEn(x, y, z + 1));
        return Math.max(max, luzEn(x, y - 1, z));
    }

    private int luzEn(int x, int y, int z) {
        if (x < 0 || x > 15 || z < 0 || z > 15) {
            return 15;
        }
        if (y < 0) {
            return 0;
        }
        DataLayer capaCielo = y < SectionExtractor.LADO ? cielo : cieloArriba;
        DataLayer capaBloque = y < SectionExtractor.LADO ? bloque : bloqueArriba;
        int ly = y & 15;
        // Sin datos de luz del cielo (motor de luz todavía no corrió, o
        // sección por encima de todo): asumir cielo abierto.
        int deCielo = capaCielo == null ? 15 : capaCielo.get(x, ly, z);
        int deBloque = capaBloque == null ? 0 : capaBloque.get(x, ly, z);
        return Math.max(deCielo, deBloque);
    }

    /**
     * Material por estado de bloque, calculado una vez: son hasta cinco
     * consultas de tags por bloque y se pregunta por cada bloque de cada
     * sección. Los tags cambian al recargar datapacks: ahí se vacía
     * ({@link #olvidarMateriales}).
     */
    private static final Map<BlockState, SuperVoxel.Material> MATERIALES = new ConcurrentHashMap<>();

    /** Tags recargados (/reload): los materiales cacheados pueden haber cambiado. */
    public static void olvidarMateriales() {
        MATERIALES.clear();
    }

    private static SuperVoxel.Material material(BlockState estado) {
        return MATERIALES.computeIfAbsent(estado, LectorSeccionMinecraft::calcularMaterial);
    }

    private static SuperVoxel.Material calcularMaterial(BlockState estado) {
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
