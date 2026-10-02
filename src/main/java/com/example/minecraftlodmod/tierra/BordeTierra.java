package com.example.minecraftlodmod.tierra;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

/**
 * Función de densidad {@code minecraftlodmod:tierra_borde}: envuelve la
 * densidad normal ({@code argument}, {@code superficie - y}) y, pasando el
 * borde del planeta, la reemplaza por las grietas y las farlands congeladas
 * de {@link FarlandsCongeladas}. Adentro devuelve el argumento tal cual (una
 * distancia y una comparación).
 * <ul>
 *   <li><b>Tierra plana</b> ({@code proyeccion} azimutal, por defecto): el
 *       borde del disco, el polo sur de la proyección.</li>
 *   <li><b>Tierra cilíndrica</b>: los dos polos, |z| = C/4 (C, la vuelta al
 *       ecuador de {@link Costura}). El mar del polo norte termina en una
 *       barrera de hielo ({@link #ajustarSuperficie}): sin ella las farlands
 *       salían del fondo del mar a 4 km. El patrón se mezcla con el de una
 *       vuelta antes cerca del antimeridiano (como el detalle y las cuevas).</li>
 * </ul>
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
            Codec.INT.fieldOf("max_y").forGetter(BordeTierra::maxY),
            Codec.STRING.validate(p -> p.equals(SuperficieTierra.CILINDRICA) || p.equals(SuperficieTierra.AZIMUTAL)
                            ? com.mojang.serialization.DataResult.success(p)
                            : com.mojang.serialization.DataResult.error(() -> "Proyección desconocida: " + p))
                    .optionalFieldOf("proyeccion", SuperficieTierra.AZIMUTAL).forGetter(BordeTierra::proyeccion)
    ).apply(i, BordeTierra::new)).stable();
    public static final KeyDispatchDataCodec<BordeTierra> CODEC = KeyDispatchDataCodec.of(CODEC_MAPA);

    private final DensityFunction argumento;
    private final double metrosPorBloque;
    private final int minY, maxY;
    private final double radioDisco;
    private final String proyeccion;
    private final boolean cilindrica;
    /** Cilíndrica: vuelta al ecuador (período en x) y z de los polos (C/4). */
    private final double periodo, zPolo;
    /** Superficie sin cuevas, para la barrera de hielo (se resuelve la primera vez). */
    private volatile AlturaTierra altura;

    /** Ancho de la barrera de hielo antes del polo (cilíndrica): plana, a {@link #CIMA_BARRERA}. */
    public static final double ANCHO_BARRERA = 128;
    /** Frente de la barrera: donde sube del fondo del mar (un acantilado de hielo). */
    public static final double FRENTE_BARRERA = 16;
    /** Desde esta distancia al polo (negativa) la columna ya es de la barrera. */
    public static final double INICIO_BARRERA = -(ANCHO_BARRERA + FRENTE_BARRERA);
    /** Cima de la barrera de hielo: 3 bloques sobre el mar (~24 m a 1:8, como una barrera real). */
    public static final int CIMA_BARRERA = AlturaTierra.NIVEL_MAR + 3;
    /** Desplazamiento del patrón del polo sur (que no repita el del norte). */
    private static final double DESFASE_SUR = 7_777_777;

    public BordeTierra(DensityFunction argumento, double metrosPorBloque, int minY, int maxY) {
        this(argumento, metrosPorBloque, minY, maxY, SuperficieTierra.AZIMUTAL);
    }

    public BordeTierra(DensityFunction argumento, double metrosPorBloque, int minY, int maxY, String proyeccion) {
        this.argumento = argumento;
        this.metrosPorBloque = metrosPorBloque;
        this.minY = minY;
        this.maxY = maxY;
        this.proyeccion = proyeccion;
        this.cilindrica = SuperficieTierra.CILINDRICA.equals(proyeccion);
        this.radioDisco = new ProyeccionAzimutal(metrosPorBloque).radioDisco();
        this.periodo = cilindrica ? Costura.circunferencia(metrosPorBloque) : 0;
        this.zPolo = periodo / 4;
    }

    public String proyeccion() {
        return proyeccion;
    }

    /** Los polos de la cilíndrica (si no, el borde del disco de la plana). */
    public boolean cilindrica() {
        return cilindrica;
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

    /** Bloques más allá del borde del planeta (negativo adentro): el disco, o el polo más cercano. */
    public double distanciaAlBorde(double x, double z) {
        if (cilindrica) return Math.abs(z) - zPolo;
        return Math.sqrt(x * x + z * z) - radioDisco;
    }

    /**
     * Posición a lo largo del borde, en bloques (arco sobre el borde del disco;
     * en la cilíndrica, x envuelto, con otro origen en el polo sur).
     */
    public double arco(double x, double z) {
        if (cilindrica) return Costura.envolver(x, periodo) + (z > 0 ? DESFASE_SUR : 0);
        return Math.atan2(x, z) * radioDisco;
    }

    /**
     * Superficie con la barrera de hielo (cilíndrica): en los últimos
     * {@link #ANCHO_BARRERA} bloques antes del polo, lo que está bajo
     * {@link #CIMA_BARRERA} queda a esa altura, con un frente de
     * {@link #FRENTE_BARRERA} bloques que sube del fondo del mar (el mar del
     * polo norte; la Antártida ya está más alta y no cambia).
     *
     * @param d distancia al borde ({@link #distanciaAlBorde})
     */
    public double ajustarSuperficie(double d, double superficie) {
        if (!cilindrica || d <= INICIO_BARRERA || superficie >= CIMA_BARRERA) return superficie;
        double t = Math.min(1, (d - INICIO_BARRERA) / FRENTE_BARRERA);
        return superficie + (CIMA_BARRERA - superficie) * t * t * (3 - 2 * t);
    }

    /** Desde dónde la columna es del borde (farlands: bioma, hielo): el disco, o la barrera antes del polo. */
    public double inicioBorde() {
        return cilindrica ? INICIO_BARRERA : 0;
    }

    @Override
    public double compute(FunctionContext c) {
        double x = c.blockX() + 0.5, z = c.blockZ() + 0.5;
        double d = distanciaAlBorde(x, z);
        double base = argumento.compute(c);
        if (d < inicioBorde()) return base;
        int y = c.blockY();
        if (!cilindrica) return FarlandsCongeladas.densidad(d, arco(x, z), y, base, base + y, minY, maxY);
        // Cilíndrica: la superficie sin cuevas (el argumento las trae), con la barrera de hielo
        double sup = superficie(x, z);
        double supAjustada = ajustarSuperficie(d, sup);
        base += supAjustada - sup;
        return densidadPolo(d, x, z, y, base, supAjustada);
    }

    /** Farlands del polo, mezcladas cerca del antimeridiano con las de una vuelta antes (sin escalón). */
    double densidadPolo(double d, double x, double z, int y, double base, double superficie) {
        if (d < 0) return base;
        double xw = Costura.envolver(x, periodo), desfase = z > 0 ? DESFASE_SUR : 0;
        double v = FarlandsCongeladas.densidad(d, xw + desfase, y, base, superficie, minY, maxY);
        double w = Costura.peso(xw, periodo, Costura.ANCHO_MEZCLA);
        if (w > 0) v += (FarlandsCongeladas.densidad(d, xw - periodo + desfase, y, base, superficie, minY, maxY) - v) * w;
        return v;
    }

    /** Superficie continua sin cuevas en (x, z) (la de {@code tierra_superficie}). */
    private double superficie(double x, double z) {
        AlturaTierra a = altura;
        if (a == null) {
            a = TierraReal.altura(proyeccion, metrosPorBloque);
            if (a == null) return AlturaTierra.NIVEL_MAR - 4000 / metrosPorBloque; // sin datos: fondo plano
            altura = a;
        }
        return a.alturaExacta(x, z);
    }

    @Override
    public void fillArray(double[] valores, ContextProvider contexto) {
        contexto.fillAllDirectly(valores, this);
    }

    @Override
    public DensityFunction mapAll(Visitor visitante) {
        return visitante.apply(new BordeTierra(argumento.mapAll(visitante), metrosPorBloque, minY, maxY, proyeccion));
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
