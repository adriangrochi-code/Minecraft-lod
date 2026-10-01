package com.example.minecraftlodmod.tierra;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

/**
 * Función de densidad {@code minecraftlodmod:tierra_borde}: envuelve la
 * densidad normal de la Tierra plana ({@code argument}, {@code superficie - y})
 * y, pasando el borde del disco (el polo sur de la proyección azimutal), la
 * reemplaza por las grietas y las farlands congeladas de
 * {@link FarlandsCongeladas}. Dentro del disco devuelve el argumento tal cual
 * (una distancia al centro y una comparación).
 *
 * <pre>
 *   final_density = interpolated(tierra_borde(add(flat_cache(tierra_superficie), -y)))
 * </pre>
 */
public final class BordeTierra implements DensityFunction {

    public static final MapCodec<BordeTierra> CODEC_MAPA = RecordCodecBuilder.<BordeTierra>mapCodec(i -> i.group(
            DensityFunction.HOLDER_HELPER_CODEC.fieldOf("argument").forGetter(BordeTierra::argumento),
            Codec.doubleRange(1, 64).fieldOf("metros_por_bloque").forGetter(BordeTierra::metrosPorBloque),
            Codec.INT.fieldOf("min_y").forGetter(BordeTierra::minY),
            Codec.INT.fieldOf("max_y").forGetter(BordeTierra::maxY)
    ).apply(i, BordeTierra::new)).stable();
    public static final KeyDispatchDataCodec<BordeTierra> CODEC = KeyDispatchDataCodec.of(CODEC_MAPA);

    private final DensityFunction argumento;
    private final double metrosPorBloque;
    private final int minY, maxY;
    private final double radioDisco;

    public BordeTierra(DensityFunction argumento, double metrosPorBloque, int minY, int maxY) {
        this.argumento = argumento;
        this.metrosPorBloque = metrosPorBloque;
        this.minY = minY;
        this.maxY = maxY;
        this.radioDisco = new ProyeccionAzimutal(metrosPorBloque).radioDisco();
    }

    public DensityFunction argumento() {
        return argumento;
    }

    public double metrosPorBloque() {
        return metrosPorBloque;
    }

    public int minY() {
        return minY;
    }

    public int maxY() {
        return maxY;
    }

    public double radioDisco() {
        return radioDisco;
    }

    /** Bloques más allá del borde del disco (negativo adentro). */
    public double distanciaAlBorde(double x, double z) {
        return Math.sqrt(x * x + z * z) - radioDisco;
    }

    /** Posición a lo largo del borde, en bloques (arco medido sobre el borde del disco). */
    public double arco(double x, double z) {
        return Math.atan2(x, z) * radioDisco;
    }

    @Override
    public double compute(FunctionContext c) {
        double x = c.blockX() + 0.5, z = c.blockZ() + 0.5;
        double d = distanciaAlBorde(x, z);
        double base = argumento.compute(c);
        if (d < 0) return base;
        return FarlandsCongeladas.densidad(d, arco(x, z), c.blockY(), base, base + c.blockY(), minY, maxY);
    }

    @Override
    public void fillArray(double[] valores, ContextProvider contexto) {
        contexto.fillAllDirectly(valores, this);
    }

    @Override
    public DensityFunction mapAll(Visitor visitante) {
        return visitante.apply(new BordeTierra(argumento.mapAll(visitante), metrosPorBloque, minY, maxY));
    }

    @Override
    public double minValue() {
        return Math.min(argumento.minValue(), -4064);
    }

    @Override
    public double maxValue() {
        return Math.max(argumento.maxValue(), 4064);
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return CODEC;
    }

    /** El borde que usan estos ajustes, o {@code null} si no tienen (Tierra cilíndrica u otro mundo). */
    public static BordeTierra de(NoiseGeneratorSettings ajustes) {
        BordeTierra[] encontrado = new BordeTierra[1];
        ajustes.noiseRouter().finalDensity().mapAll(f -> {
            if (f instanceof BordeTierra b) encontrado[0] = b;
            return f;
        });
        return encontrado[0];
    }
}
