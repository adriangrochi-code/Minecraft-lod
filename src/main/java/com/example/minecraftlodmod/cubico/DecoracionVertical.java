package com.example.minecraftlodmod.cubico;

import com.example.minecraftlodmod.cubico.mixin.AccesoGeneradorFeatures;
import it.unimi.dsi.fastutil.ints.IntArraySet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Features (vegetación de cuevas frondosas, dripstone, líquenes, sculk,
 * mazmorras, manantiales, y árboles y pasto en islas) sobre una banda que
 * {@link CompletadoVertical} acaba de completar.
 *
 * Mismo orden y misma siembra que vanilla ({@code ChunkGenerator#applyBiomeDecoration}:
 * semilla de decoración del chunk y una por feature), así cada feature prueba
 * las mismas posiciones que en la generación completa y la vegetación sale
 * donde habría salido. Corre sobre el mundo vivo (como el comando
 * {@code /place}), en el hilo del servidor, con un filtro
 * ({@code MixinLevelFiltro}) que descarta toda escritura fuera de la banda y
 * del chunk: lo que ya estaba decorado arriba no se duplica.
 *
 * Pasos que se corren: los que dependen de dónde hay huecos. No: menas (ya
 * se pusieron sobre el relleno), lagos, geodas ni estructuras (no dependen
 * de las cuevas y ya están).
 */
public final class DecoracionVertical {

    private DecoracionVertical() {
    }

    private static final Set<GenerationStep.Decoration> PASOS = EnumSet.of(
            GenerationStep.Decoration.LOCAL_MODIFICATIONS,
            GenerationStep.Decoration.UNDERGROUND_STRUCTURES,
            GenerationStep.Decoration.UNDERGROUND_DECORATION,
            GenerationStep.Decoration.FLUID_SPRINGS,
            GenerationStep.Decoration.VEGETAL_DECORATION,
            GenerationStep.Decoration.TOP_LAYER_MODIFICATION);

    /** minX, maxX, minY, maxY, minZ, maxZ de la banda que se está decorando en este hilo (null = no hay). */
    private static final ThreadLocal<int[]> FILTRO = new ThreadLocal<>();
    private static final ThreadLocal<int[]> ESCRITOS = ThreadLocal.withInitial(() -> new int[1]);

    /** Desde el mixin de {@code Level#setBlock}: true si hay que descartar esta escritura. */
    public static boolean descartar(BlockPos pos) {
        int[] f = FILTRO.get();
        if (f == null) {
            return false;
        }
        boolean fuera = pos.getX() < f[0] || pos.getX() > f[1] || pos.getY() < f[2] || pos.getY() > f[3]
                || pos.getZ() < f[4] || pos.getZ() > f[5];
        if (!fuera) {
            ESCRITOS.get()[0]++;
        }
        return fuera;
    }

    /** Sin avisar a los vecinos ni actualizar formas mientras se decora (como un WorldGenRegion). */
    public static int banderas(int banderas) {
        return FILTRO.get() == null ? banderas : (banderas & ~1) | 16;
    }

    /**
     * Decora la banda [minSeccion, maxSeccion] de la columna. Hilo del servidor.
     * Devuelve los bloques que pusieron las features.
     */
    static int decorar(ServerLevel nivel, LevelChunk chunk, int minSeccion, int maxSeccion) {
        ChunkGenerator gen = nivel.getChunkSource().getGenerator();
        AccesoGeneradorFeatures acceso = (AccesoGeneradorFeatures) gen;
        List<FeatureSorter.StepFeatureData> porPaso = acceso.minecraftlodmod$featuresPorPaso().get();
        ChunkPos pos = chunk.getPos();
        BlockPos origen = SectionPos.of(pos, nivel.getMinSection()).origin();

        // Biomas del chunk y sus vecinos cargados, como vanilla.
        Set<Holder<Biome>> biomas = new ObjectArraySet<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                LevelChunk c = nivel.getChunkSource().getChunkNow(pos.x + dx, pos.z + dz);
                if (c != null) {
                    for (LevelChunkSection s : c.getSections()) {
                        s.getBiomes().getAll(biomas::add);
                    }
                }
            }
        }
        biomas.retainAll(gen.getBiomeSource().possibleBiomes());

        var registro = nivel.registryAccess().registryOrThrow(Registries.PLACED_FEATURE);
        WorldgenRandom aleatorio = new WorldgenRandom(new XoroshiroRandomSource(RandomSupport.generateUniqueSeed()));
        long semilla = aleatorio.setDecorationSeed(nivel.getSeed(), origen.getX(), origen.getZ());

        FILTRO.set(new int[]{pos.getMinBlockX(), pos.getMaxBlockX(),
                SectionPos.sectionToBlockCoord(minSeccion), SectionPos.sectionToBlockCoord(maxSeccion) + 15,
                pos.getMinBlockZ(), pos.getMaxBlockZ()});
        ESCRITOS.get()[0] = 0;
        try {
            for (GenerationStep.Decoration paso : PASOS) {
                int k = paso.ordinal();
                if (k >= porPaso.size()) {
                    continue;
                }
                IntSet indices = new IntArraySet();
                FeatureSorter.StepFeatureData datos = porPaso.get(k);
                for (Holder<Biome> bioma : biomas) {
                    List<HolderSet<PlacedFeature>> lista = acceso.minecraftlodmod$ajustesDeBioma().apply(bioma).features();
                    if (k < lista.size()) {
                        lista.get(k).stream().map(Holder::value).forEach(f -> indices.add(datos.indexMapping().applyAsInt(f)));
                    }
                }
                int[] orden = indices.toIntArray();
                Arrays.sort(orden);
                for (int i : orden) {
                    PlacedFeature feature = datos.features().get(i);
                    if (paso == GenerationStep.Decoration.LOCAL_MODIFICATIONS && registro.getResourceKey(feature)
                            .map(c -> c.location().getPath().contains("geode")).orElse(false)) {
                        continue; // las geodas no dependen de las cuevas y ya están
                    }
                    aleatorio.setFeatureSeed(semilla, i, k);
                    try {
                        feature.placeWithBiomeCheck(nivel, gen, aleatorio, origen);
                    } catch (RuntimeException e) {
                        ESTADISTICAS.fallos++; // una feature rota no frena el resto (ni el servidor)
                    }
                }
            }
        } finally {
            FILTRO.remove();
        }
        return ESCRITOS.get()[0];
    }

    public static final Estadisticas ESTADISTICAS = new Estadisticas();

    public static final class Estadisticas {
        int fallos;
    }
}
