package com.example.minecraftlodmod.tierra;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import java.util.Map;
import java.util.stream.Stream;

/**
 * Fuente de biomas {@code minecraftlodmod:tierra}: el bioma de cada columna
 * sale del clima real (Köppen), la elevación y la latitud
 * ({@link ClasificadorBiomas}); qué bioma de Minecraft va en cada caso lo dice
 * el campo {@code biomas} del {@code world_preset} (clave → bioma), así se
 * cambia con un datapack sin tocar código.
 *
 * <pre>
 *   "biome_source": {"type": "minecraftlodmod:tierra",
 *                    "superficie": {"proyeccion": "cilindrica", "metros_por_bloque": 8.0},
 *                    "biomas": {"Af": "minecraft:jungle", ..., "oceano_calido": "minecraft:warm_ocean", ...}}
 * </pre>
 *
 * El bioma no depende de y (Minecraft lo pide por cada celda de 4×4×4): se
 * guarda el último resultado por columna y por hilo.
 */
public final class FuenteBiomasTierra extends BiomeSource {

    public static final MapCodec<FuenteBiomasTierra> CODEC = RecordCodecBuilder.<FuenteBiomasTierra>mapCodec(i -> i.group(
            SuperficieTierra.CODEC_MAPA.codec().fieldOf("superficie").forGetter(f -> f.superficie),
            Codec.unboundedMap(Codec.STRING, Biome.CODEC).fieldOf("biomas").forGetter(f -> f.biomas)
    ).apply(i, FuenteBiomasTierra::new)).validate(f -> {
        var faltan = ClasificadorBiomas.CLAVES.stream().filter(k -> !f.biomas.containsKey(k)).toList();
        return faltan.isEmpty() ? DataResult.success(f) : DataResult.error(() -> "Faltan biomas para: " + faltan);
    }).stable(); // como los de vanilla: sin esto el mundo sale como "ajustes experimentales"

    /** A cuántos bloques se mira si hay mar, para las playas. */
    private static final int DISTANCIA_COSTA = 8;

    private final SuperficieTierra superficie;
    private final Map<String, Holder<Biome>> biomas;
    private final ThreadLocal<long[]> ultimaColumna = ThreadLocal.withInitial(() -> new long[]{Long.MIN_VALUE});
    private final ThreadLocal<Object[]> ultimoBioma = ThreadLocal.withInitial(() -> new Object[1]);

    public FuenteBiomasTierra(SuperficieTierra superficie, Map<String, Holder<Biome>> biomas) {
        this.superficie = superficie;
        this.biomas = Map.copyOf(biomas);
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return biomas.values().stream().distinct();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Holder<Biome> getNoiseBiome(int qx, int qy, int qz, Climate.Sampler muestreador) {
        long clave = (long) qx << 32 | (qz & 0xFFFFFFFFL);
        long[] ultima = ultimaColumna.get();
        Object[] bioma = ultimoBioma.get();
        if (ultima[0] == clave && bioma[0] != null) return (Holder<Biome>) bioma[0];
        Holder<Biome> b = biomas.get(claveDe(QuartPos.toBlock(qx) + 2, QuartPos.toBlock(qz) + 2));
        ultima[0] = clave;
        bioma[0] = b;
        return b;
    }

    /** Clave de bioma de la columna (x, z); pública para {@code /tierra medir}. */
    public String claveDe(int x, int z) {
        AlturaTierra a = superficie.altura();
        if (a == null) return "oceano_normal_profundo";
        double cx = x + 0.5, cz = z + 0.5;
        if (a.proyeccion() instanceof ProyeccionAzimutal azimutal
                && Math.sqrt(cx * cx + cz * cz) >= azimutal.radioDisco()) {
            return ClasificadorBiomas.FARLANDS;
        }
        double elev = a.elevacionMetros(cx, cz);
        int clase = a.claseClima(cx, cz);
        boolean juntoAlMar = false;
        if (elev >= 0 && elev < 2 * a.metrosPorBloque()) {
            juntoAlMar = esMar(a, cx + DISTANCIA_COSTA, cz) || esMar(a, cx - DISTANCIA_COSTA, cz)
                    || esMar(a, cx, cz + DISTANCIA_COSTA) || esMar(a, cx, cz - DISTANCIA_COSTA);
        }
        return ClasificadorBiomas.clave(elev, a.latitud(cx, cz), clase, juntoAlMar, a.metrosPorBloque());
    }

    private static boolean esMar(AlturaTierra a, double x, double z) {
        return a.elevacionMetros(x, z) < 0 && a.claseClima(x, z) == 0;
    }
}
