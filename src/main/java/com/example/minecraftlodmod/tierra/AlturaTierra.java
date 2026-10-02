package com.example.minecraftlodmod.tierra;

/**
 * Altura de la superficie de una columna (x, z) en bloques: la única fuente de
 * verdad de la superficie para el generador y para el LOD
 * ({@code docs/tierra-real/03-generador.md}).
 *
 * y = 63 + elevación × exageración / metros por bloque, con el nivel del mar
 * en 63 como vanilla ({@code 01-decisiones.md}). La elevación es la bicúbica
 * de los datos con el detalle a escala de bloque de {@link DetalleTierra} (H8,
 * se puede apagar), recortada al rango de las muestras (Catmull-Rom puede
 * pasarse un poco en los extremos; así
 * nada queda más hondo que la fosa ni más alto que el pico de los datos).
 *
 * <b>El fondo de la fosa más honda apoya en el lecho de roca</b> (pedido del
 * usuario): el piso de la dimensión ({@link #minYDimension}) es el múltiplo de
 * 16 en o bajo el bloque más hondo de los datos ({@link #yFondoFosa}), y el
 * lecho de roca es macizo desde ese piso hasta el fondo de la fosa
 * ({@link #esLechoDeRoca}), sin el degradé de vanilla. Con ETOPO 30″ el mínimo de
 * la grilla está en la fosa de las Marianas (abismo Sirena, -10 775 m en
 * 11,971° N, 144,371° E; el Challenger Deep queda más arriba porque la grilla
 * promedia ~1 km).
 *
 * En la cilíndrica todo es periódico en x con la vuelta al ecuador
 * ({@link Costura}, H10): x se envuelve antes de consultar.
 */
public final class AlturaTierra {

    public static final int NIVEL_MAR = 63;
    /** Ancho de celda del ruido ({@code size_horizontal} 1 = 4 bloques) de los {@code noise_settings}. */
    public static final int ANCHO_CELDA = 4;

    private final FuenteTierra fuente;
    private final Proyeccion proyeccion;
    private final double factor;
    private final double elevMinima, elevMaxima;
    private final boolean detalle;
    /** Período en x (vuelta al ecuador en la cilíndrica, H10), 0 si no hay. */
    private final double periodoX;
    private final ThreadLocal<double[]> rango = ThreadLocal.withInitial(() -> new double[1]);

    public AlturaTierra(FuenteTierra fuente, Proyeccion proyeccion, double exageracionVertical) {
        this(fuente, proyeccion, exageracionVertical, true);
    }

    /** @param detalle sumar el detalle a escala de bloque ({@link DetalleTierra}); sin él, la bicúbica de los datos */
    public AlturaTierra(FuenteTierra fuente, Proyeccion proyeccion, double exageracionVertical, boolean detalle) {
        if (!(exageracionVertical > 0)) throw new IllegalArgumentException("Exageración inválida: " + exageracionVertical);
        this.detalle = detalle;
        this.fuente = fuente;
        this.proyeccion = proyeccion;
        this.periodoX = proyeccion.periodoX();
        this.factor = exageracionVertical / proyeccion.metrosPorBloque();
        this.elevMinima = fuente.cabecera().elevMinima();
        this.elevMaxima = fuente.cabecera().elevMaxima();
    }

    /** Período en x del mundo (la vuelta al ecuador), 0 si no es periódico ({@link Costura}). */
    public double periodoX() {
        return periodoX;
    }

    public Proyeccion proyeccion() {
        return proyeccion;
    }

    /** Elevación en metros bajo el punto (x, z) del mundo, dentro del rango de los datos. */
    public double elevacionMetros(double x, double z) {
        x = Costura.envolver(x, periodoX);
        double lat = proyeccion.latitud(x, z), lon = Proyeccion.normalizarLongitud(proyeccion.longitud(x, z));
        double e;
        if (detalle) {
            double[] r = rango.get();
            e = DetalleTierra.conDetalle(x, z, fuente.elevacion(lat, lon, r), r[0], periodoX);
        } else {
            e = fuente.elevacion(lat, lon);
        }
        // Lagos sin batimetría en los datos (Victoria, Titicaca: ETOPO da la superficie, no el fondo):
        // el suelo del lago se baja a 2 bloques bajo su nivel (y la orilla de hasta 1 bloque sobre él).
        int agua = fuente.nivelAgua(lat, lon);
        if (agua != AguaContinental.SECO && agua != 0) {
            double piso = agua - 2 * proyeccion.metrosPorBloque();
            if (e > piso && e < agua + proyeccion.metrosPorBloque()) e = piso;
        }
        return Math.clamp(e, elevMinima, elevMaxima);
    }

    public double latitud(double x, double z) {
        return proyeccion.latitud(Costura.envolver(x, periodoX), z);
    }

    /** Clase de clima (Köppen 1..30, 0 = sin dato/mar) de la muestra más cercana a (x, z). */
    public int claseClima(double x, double z) {
        x = Costura.envolver(x, periodoX);
        return fuente.claseBioma(proyeccion.latitud(x, z), Proyeccion.normalizarLongitud(proyeccion.longitud(x, z)));
    }

    /**
     * Hasta dónde llega el agua en la columna: el agua llena y &lt; este valor
     * (como el {@code FluidStatus} de vanilla). 63 en el mar; el nivel de su
     * lago en un lago; {@link Integer#MIN_VALUE} si la columna es seca
     * ({@link AguaContinental}).
     */
    public int nivelAguaY(double x, double z) {
        x = Costura.envolver(x, periodoX);
        int n = fuente.nivelAgua(proyeccion.latitud(x, z), Proyeccion.normalizarLongitud(proyeccion.longitud(x, z)));
        if (n == AguaContinental.SECO) return Integer.MIN_VALUE;
        return (int) Math.ceil(NIVEL_MAR + n * factor);
    }

    /** Metros reales por bloque de este mundo. */
    public double metrosPorBloque() {
        return proyeccion.metrosPorBloque();
    }

    /**
     * Altura continua de la superficie en (x, z): la densidad del generador es
     * positiva (sólido) donde y &lt; alturaExacta.
     */
    public double alturaExacta(double x, double z) {
        return NIVEL_MAR + elevacionMetros(x, z) * factor;
    }

    /**
     * y del bloque sólido más alto de la columna (x, z), igual que la genera
     * el generador: {@code interpolated(flat_cache(...))} toma la superficie en
     * las esquinas de celda (cada {@link #ANCHO_CELDA} bloques, en el centro
     * del bloque de la esquina) e interpola lineal entre ellas. En un pico eso
     * puede dar un bloque menos que {@link #alturaExacta} en ese punto.
     */
    public int altura(int x, int z) {
        int x0 = Math.floorDiv(x, ANCHO_CELDA) * ANCHO_CELDA, z0 = Math.floorDiv(z, ANCHO_CELDA) * ANCHO_CELDA;
        double fx = (x - x0) / (double) ANCHO_CELDA, fz = (z - z0) / (double) ANCHO_CELDA;
        double h00 = alturaExacta(x0 + 0.5, z0 + 0.5), h10 = alturaExacta(x0 + ANCHO_CELDA + 0.5, z0 + 0.5);
        double h01 = alturaExacta(x0 + 0.5, z0 + ANCHO_CELDA + 0.5);
        double h11 = alturaExacta(x0 + ANCHO_CELDA + 0.5, z0 + ANCHO_CELDA + 0.5);
        double h0 = h00 + (h10 - h00) * fx, h1 = h01 + (h11 - h01) * fx;
        return yBloqueSuperior(h0 + (h1 - h0) * fz);
    }

    /** y del bloque más hondo de todo el mundo: el fondo de la fosa más honda de los datos. */
    public int yFondoFosa() {
        return yBloque(elevMinima, factor);
    }

    /** y del bloque más alto de todo el mundo (el pico más alto de los datos). */
    public int yCima() {
        return yBloque(elevMaxima, factor);
    }

    /** {@code min_y} de la dimensión: múltiplo de 16 en o bajo el fondo de la fosa. */
    public int minYDimension() {
        return minYDimension(yFondoFosa());
    }

    /** Lecho de roca macizo desde {@link #minYDimension} hasta el fondo de la fosa, inclusive. */
    public boolean esLechoDeRoca(int y) {
        return y <= yFondoFosa();
    }

    /**
     * y del bloque superior de una columna de {@code metros} de elevación, sin
     * datos cargados (para fijar las alturas de la dimensión en los presets).
     */
    public static int yBloque(double metros, double exageracionSobreMetrosPorBloque) {
        return yBloqueSuperior(NIVEL_MAR + metros * exageracionSobreMetrosPorBloque);
    }

    public static int minYDimension(int yFondoFosa) {
        return Math.floorDiv(yFondoFosa, 16) * 16;
    }

    private static int yBloqueSuperior(double alturaExacta) {
        return (int) Math.ceil(alturaExacta) - 1;
    }
}
