package com.example.minecraftlodmod.tierra;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Función de densidad {@code minecraftlodmod:tierra_cuevas}: envuelve la
 * densidad de la superficie ({@code argument}, {@code superficie - y}, que es
 * la profundidad bajo el suelo) y le resta las cuevas de {@link CuevasTierra}.
 * Sobre la superficie devuelve el argumento sin calcular nada.
 *
 * <pre>
 *   final_density = interpolated([tierra_borde(] tierra_cuevas(add(flat_cache(tierra_superficie), -y)) [)])
 * </pre>
 */
public record CuevasTierraFuncion(DensityFunction argumento) implements DensityFunction {

    public static final MapCodec<CuevasTierraFuncion> CODEC_MAPA = RecordCodecBuilder.<CuevasTierraFuncion>mapCodec(i -> i.group(
            DensityFunction.HOLDER_HELPER_CODEC.fieldOf("argument").forGetter(CuevasTierraFuncion::argumento)
    ).apply(i, CuevasTierraFuncion::new)).stable();
    public static final KeyDispatchDataCodec<CuevasTierraFuncion> CODEC = KeyDispatchDataCodec.of(CODEC_MAPA);

    @Override
    public double compute(FunctionContext c) {
        return CuevasTierra.densidad(c.blockX(), c.blockY(), c.blockZ(), argumento.compute(c));
    }

    @Override
    public void fillArray(double[] valores, ContextProvider contexto) {
        contexto.fillAllDirectly(valores, this);
    }

    @Override
    public DensityFunction mapAll(Visitor visitante) {
        return visitante.apply(new CuevasTierraFuncion(argumento.mapAll(visitante)));
    }

    @Override
    public double minValue() {
        return Math.min(argumento.minValue(), -4064);
    }

    @Override
    public double maxValue() {
        return argumento.maxValue();
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return CODEC;
    }
}
