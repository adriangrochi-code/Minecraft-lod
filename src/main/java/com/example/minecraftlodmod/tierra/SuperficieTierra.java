package com.example.minecraftlodmod.tierra;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

/**
 * Función de densidad {@code minecraftlodmod:tierra_superficie}: la altura
 * continua de la superficie en bloques ({@link AlturaTierra#alturaExacta}),
 * solo de (x, z). Los {@code noise_settings} de Tierra real arman con ella,
 * con piezas de vanilla, la densidad {@code superficie - y} (positiva bajo la
 * superficie):
 *
 * <pre>
 *   final_density = interpolated(add(flat_cache(tierra_superficie), y_clamped_gradient(-y)))
 * </pre>
 *
 * {@code flat_cache} la evalúa una vez por columna de cada 4 bloques del
 * chunk (las esquinas de celda) e {@code interpolated} interpola entre ellas;
 * como la densidad es lineal en y, la superficie queda exacta en las esquinas.
 * Fuera de un {@code NoiseChunk} (el LOD aproximado, la superficie preliminar
 * de un punto suelto) se evalúa directo en cada bloque.
 *
 * Si faltan los datos se usa un fondo de mar plano a -4000 m (y se avisa una
 * vez en el log, {@link TierraReal}): el mundo se genera igual, sin colgar al
 * servidor.
 */
public final class SuperficieTierra implements DensityFunction.SimpleFunction {

    public static final String CILINDRICA = "cilindrica", AZIMUTAL = "azimutal";
    private static final double ELEVACION_SIN_DATOS = -4000;

    public static final MapCodec<SuperficieTierra> CODEC_MAPA = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.STRING.validate(p -> p.equals(CILINDRICA) || p.equals(AZIMUTAL)
                            ? com.mojang.serialization.DataResult.success(p)
                            : com.mojang.serialization.DataResult.error(() -> "Proyección desconocida: " + p))
                    .fieldOf("proyeccion").forGetter(SuperficieTierra::proyeccion),
            Codec.doubleRange(1, 64).fieldOf("metros_por_bloque").forGetter(SuperficieTierra::metrosPorBloque)
    ).apply(i, SuperficieTierra::new));
    public static final KeyDispatchDataCodec<SuperficieTierra> CODEC = KeyDispatchDataCodec.of(CODEC_MAPA);

    private final String proyeccion;
    private final double metrosPorBloque;
    /** Resuelta la primera vez que se usa (los datos se cargan recién al generar). */
    private volatile AlturaTierra altura;
    private volatile boolean sinDatos;

    public SuperficieTierra(String proyeccion, double metrosPorBloque) {
        this.proyeccion = proyeccion;
        this.metrosPorBloque = metrosPorBloque;
    }

    public String proyeccion() {
        return proyeccion;
    }

    public double metrosPorBloque() {
        return metrosPorBloque;
    }

    /** La superficie de este mundo, o {@code null} si no hay datos. */
    public AlturaTierra altura() {
        AlturaTierra a = altura;
        if (a == null && !sinDatos) {
            a = TierraReal.altura(proyeccion, metrosPorBloque);
            if (a == null) sinDatos = true;
            else altura = a;
        }
        return a;
    }

    @Override
    public double compute(FunctionContext contexto) {
        AlturaTierra a = altura();
        if (a == null) return AlturaTierra.NIVEL_MAR + ELEVACION_SIN_DATOS / metrosPorBloque;
        return a.alturaExacta(contexto.blockX() + 0.5, contexto.blockZ() + 0.5);
    }

    @Override
    public double minValue() {
        return -4064;
    }

    @Override
    public double maxValue() {
        return 4064;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return CODEC;
    }

    /** La superficie de Tierra real que usan estos ajustes, o {@code null} si no son de Tierra real. */
    public static SuperficieTierra de(NoiseGeneratorSettings ajustes) {
        SuperficieTierra[] encontrada = new SuperficieTierra[1];
        ajustes.noiseRouter().finalDensity().mapAll(f -> {
            if (f instanceof SuperficieTierra s) encontrada[0] = s;
            return f;
        });
        return encontrada[0];
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SuperficieTierra s && s.proyeccion.equals(proyeccion) && s.metrosPorBloque == metrosPorBloque;
    }

    @Override
    public int hashCode() {
        return proyeccion.hashCode() * 31 + Double.hashCode(metrosPorBloque);
    }
}
