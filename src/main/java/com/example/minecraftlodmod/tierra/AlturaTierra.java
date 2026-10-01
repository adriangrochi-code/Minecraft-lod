package com.example.minecraftlodmod.tierra;

/**
 * Altura de la superficie de una columna (x, z) en bloques: la única fuente de
 * verdad de la superficie para el generador y para el LOD
 * ({@code docs/tierra-real/03-generador.md}).
 *
 * y = 63 + elevación × exageración / metros por bloque, con el nivel del mar
 * en 63 como vanilla ({@code 01-decisiones.md}). En H2 todavía sin ruido de
 * detalle (H8): la superficie es la bicúbica de los datos, recortada al rango
 * de las muestras (Catmull-Rom puede pasarse un poco en los extremos; así
 * nada queda más hondo que la fosa ni más alto que el pico de los datos).
 *
 * <b>El fondo de la fosa más honda apoya en el lecho de roca</b> (pedido del
 * usuario): el piso de la dimensión ({@link #minYDimension}) es el múltiplo de
 * 16 en o bajo el bloque más hondo de los datos ({@link #yFondoFosa}), y el
 * lecho de roca es macizo desde ese piso hasta el fondo de la fosa
 * ({@link #esLechoDeRoca}), sin el degradé de vanilla. Con ETOPO 30″ la fosa
 * de las Marianas es el mínimo de la grilla (-10 571 m en 11,354° N,
 * 142,429° E).
 */
public final class AlturaTierra {

    public static final int NIVEL_MAR = 63;

    private final FuenteTierra fuente;
    private final Proyeccion proyeccion;
    private final double factor;
    private final double elevMinima, elevMaxima;

    public AlturaTierra(FuenteTierra fuente, Proyeccion proyeccion, double exageracionVertical) {
        if (!(exageracionVertical > 0)) throw new IllegalArgumentException("Exageración inválida: " + exageracionVertical);
        this.fuente = fuente;
        this.proyeccion = proyeccion;
        this.factor = exageracionVertical / proyeccion.metrosPorBloque();
        this.elevMinima = fuente.cabecera().elevMinima();
        this.elevMaxima = fuente.cabecera().elevMaxima();
    }

    public Proyeccion proyeccion() {
        return proyeccion;
    }

    /** Elevación en metros bajo el punto (x, z) del mundo, dentro del rango de los datos. */
    public double elevacionMetros(double x, double z) {
        double e = fuente.elevacion(proyeccion.latitud(x, z), Proyeccion.normalizarLongitud(proyeccion.longitud(x, z)));
        return Math.clamp(e, elevMinima, elevMaxima);
    }

    /**
     * Altura continua de la superficie en (x, z): la densidad del generador es
     * positiva (sólido) donde y &lt; alturaExacta.
     */
    public double alturaExacta(double x, double z) {
        return NIVEL_MAR + elevacionMetros(x, z) * factor;
    }

    /** y del bloque sólido más alto de la columna (x, z), evaluada en el centro del bloque. */
    public int altura(int x, int z) {
        return yBloqueSuperior(alturaExacta(x + 0.5, z + 0.5));
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
