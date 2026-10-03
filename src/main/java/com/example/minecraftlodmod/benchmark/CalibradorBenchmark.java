package com.example.minecraftlodmod.benchmark;

import com.example.minecraftlodmod.config.ParametrosCalidad;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Máquina de estados de la calibración (sección 9, paso 3), sin Minecraft:
 * quien la maneja le pasa el frame time de cada frame (y una vez por segundo
 * las métricas del monitor) y reacciona a los {@link Evento}s (mover al
 * jugador de punto, aplicar otro escalón).
 *
 * Por escalón se recorren todos los puntos. En cada uno se espera al menos
 * el calentamiento mínimo y, desde ahí, hasta que el LOD esté listo (nada en
 * cola de mallas ni de generación) o se cumpla el máximo: con una espera fija
 * se medía con el LOD a medio armar y el costo salía de menos. El tiempo hasta
 * que estuvo listo queda como "carga". Después se mide durante la medición del
 * punto: promedio, 1% peor (lo que se siente como tirones) y peor cuadro.
 *
 * Búsqueda: desde el escalón del preset elegido, si pasa se sube hasta el
 * primero que falla (resultado: el último que pasó); si falla se baja hasta
 * el primero que pasa. Si ni el más liviano pasa, el resultado es ese con
 * {@link Resultado#enPiso()}. Con un solo escalón ("solo medir") termina al
 * medirlo.
 *
 * Un escalón pasa si, en los puntos que cuentan para calibrar, el promedio
 * de sus frame times es a lo sumo {@link #MARGEN} del objetivo y ningún punto
 * tiene el 1% peor por encima de {@link #FACTOR_TIRONES} × objetivo. El
 * vuelo no cuenta: la primera vez genera terreno y las siguientes lo encuentra
 * hecho, así que compararía escalones con trabajos distintos; se informa igual.
 */
public final class CalibradorBenchmark {

    /** Un escalón pasa si su frame time promedio es a lo sumo este factor del objetivo. */
    public static final double MARGEN = 0.9;
    /** Y si el 1% peor de cada punto no pasa de este factor del objetivo (mismo criterio de tirón que el auto-ajuste). */
    public static final double FACTOR_TIRONES = 2.5;

    public enum Evento {
        /** Seguir midiendo. */
        NADA,
        /** Mover al jugador a {@link #puntoActual()} (mismo escalón). */
        CAMBIAR_PUNTO,
        /** Aplicar {@link #escalonActual()} y mover al jugador al punto 0. */
        CAMBIAR_ESCALON,
        /** Terminó: leer {@link #resultado()}. */
        TERMINADO
    }

    /** Tiempos de un punto: espera mínima y máxima antes de medir, y cuánto se mide. */
    public record Ventana(double calentamientoMinMs, double calentamientoMaxMs, double medicionMs,
                          boolean cuentaParaCalibrar) {
        public Ventana {
            if (calentamientoMinMs < 0 || calentamientoMaxMs < calentamientoMinMs || medicionMs <= 0) {
                throw new IllegalArgumentException("Ventana de medición inválida");
            }
        }
    }

    /**
     * Métricas de un punto en un escalón. Las de segundo en segundo son
     * promedios de la medición; -1 = sin dato (por ejemplo, sin medición de GPU).
     *
     * @param cargaMs       cuánto tardó el LOD en estar listo (el máximo si no llegó)
     * @param lodListo      false si se midió sin que el LOD terminara de armarse
     */
    public record MetricasPunto(int cuadros, double promedioMs, double percentil99Ms, double peorMs,
                                double cargaMs, boolean lodListo, double gpuMs, double cpuJuego, double ramMb,
                                double msServidor, double verticesDibujados, double llamadas, double vramMb) {
        public double fps() {
            return promedioMs <= 0 ? 0 : 1000 / promedioMs;
        }

        public double fpsUnoPorCientoBajo() {
            return percentil99Ms <= 0 ? 0 : 1000 / percentil99Ms;
        }
    }

    /** Lo que se midió en un escalón. {@code msPromedio} = promedio de los puntos que cuentan. */
    public record Medicion(int escalon, double[] msPorPunto, double msPromedio, boolean paso,
                           MetricasPunto[] puntos) {
    }

    public record Resultado(ParametrosCalidad elegido, int indice, boolean enPiso, List<Medicion> mediciones) {
    }

    /** Métricas de segundo en segundo que se promedian por punto (en el orden de {@link MetricasPunto}). */
    static final int EXTRAS = 7;

    private final List<ParametrosCalidad> escalones;
    private final Ventana[] ventanas;
    private final double umbralMs;
    private final double umbralTironMs;

    private int escalon;
    private int punto;
    private double transcurridoMs;
    private boolean midiendo;
    private double cargaMs;
    private boolean llegoListo;
    private double[] cuadros = new double[1024];
    private int cantidadCuadros;
    private final double[] sumaExtras = new double[EXTRAS];
    private final int[] cuentaExtras = new int[EXTRAS];
    private final MetricasPunto[] metricas;

    /** +1 subiendo, -1 bajando, 0 todavía en el primer escalón. */
    private int direccion;
    private final List<Medicion> mediciones = new ArrayList<>();
    private Resultado resultado;

    /** Todos los puntos con la misma espera fija y la misma medición, y todos cuentan. */
    public CalibradorBenchmark(EscalonesCalibracion tabla, int cantidadPuntos,
                               double calentamientoMs, double medicionMs) {
        this(tabla, ventanasIguales(cantidadPuntos, calentamientoMs, medicionMs));
    }

    public CalibradorBenchmark(EscalonesCalibracion tabla, Ventana[] ventanas) {
        if (ventanas.length < 1) {
            throw new IllegalArgumentException("Parámetros de calibración inválidos");
        }
        if (Arrays.stream(ventanas).noneMatch(Ventana::cuentaParaCalibrar)) {
            throw new IllegalArgumentException("Ningún punto cuenta para calibrar");
        }
        this.escalones = tabla.escalones();
        this.escalon = tabla.indiceInicial();
        this.ventanas = ventanas.clone();
        double objetivo = escalones.get(escalon).frameTimeObjetivoMs();
        this.umbralMs = objetivo * MARGEN;
        this.umbralTironMs = objetivo * FACTOR_TIRONES;
        this.metricas = new MetricasPunto[ventanas.length];
    }

    private static Ventana[] ventanasIguales(int cantidad, double calentamientoMs, double medicionMs) {
        if (cantidad < 1 || calentamientoMs < 0 || medicionMs <= 0) {
            throw new IllegalArgumentException("Parámetros de calibración inválidos");
        }
        Ventana[] v = new Ventana[cantidad];
        Arrays.fill(v, new Ventana(calentamientoMs, calentamientoMs, medicionMs, true));
        return v;
    }

    /** Como {@link #registrarFrame(double, boolean)} con el LOD siempre listo (espera fija). */
    public Evento registrarFrame(double frameMs) {
        return registrarFrame(frameMs, true);
    }

    /**
     * Registrar un frame. La primera llamada ya cuenta: quien maneja la
     * calibración tiene que haber aplicado {@link #escalonActual()} y movido
     * al jugador a {@link #puntoActual()} antes de empezar.
     *
     * @param lodListo el LOD terminó de armarse alrededor de la cámara
     */
    public Evento registrarFrame(double frameMs, boolean lodListo) {
        if (resultado != null) {
            return Evento.TERMINADO;
        }
        Ventana v = ventanas[punto];
        transcurridoMs += frameMs;
        if (!midiendo) {
            boolean listo = lodListo && transcurridoMs > v.calentamientoMinMs();
            if (!listo && transcurridoMs <= v.calentamientoMaxMs()) {
                return Evento.NADA;
            }
            // El cuadro que cruza el fin de la espera es el primero de la medición.
            midiendo = true;
            // Con espera fija (el vuelo) no se espera al LOD: no hay nada que marcar.
            llegoListo = listo || v.calentamientoMaxMs() == v.calentamientoMinMs();
            cargaMs = transcurridoMs - frameMs;
            transcurridoMs = frameMs;
        }
        if (cantidadCuadros == cuadros.length) {
            cuadros = Arrays.copyOf(cuadros, cuadros.length * 2);
        }
        cuadros[cantidadCuadros++] = frameMs;
        if (transcurridoMs < v.medicionMs()) {
            return Evento.NADA;
        }

        metricas[punto] = cerrarPunto();
        if (punto + 1 < ventanas.length) {
            punto++;
            return Evento.CAMBIAR_PUNTO;
        }
        return cerrarEscalon();
    }

    /**
     * Métricas de un segundo (del monitor de rendimiento): solo se acumulan
     * mientras se mide. Un valor negativo o NaN es "sin dato" y no cuenta.
     */
    public void registrarSegundo(double gpuMs, double cpuJuego, double ramMb, double msServidor,
                                 double verticesDibujados, double llamadas, double vramMb) {
        if (!midiendo || resultado != null) {
            return;
        }
        double[] v = {gpuMs, cpuJuego, ramMb, msServidor, verticesDibujados, llamadas, vramMb};
        for (int i = 0; i < EXTRAS; i++) {
            if (v[i] >= 0) { // NaN y negativos: sin dato
                sumaExtras[i] += v[i];
                cuentaExtras[i]++;
            }
        }
    }

    private MetricasPunto cerrarPunto() {
        double[] v = Arrays.copyOf(cuadros, cantidadCuadros);
        Arrays.sort(v);
        double suma = 0;
        for (double x : v) {
            suma += x;
        }
        double promedio = v.length == 0 ? 0 : suma / v.length;
        double p99 = v.length == 0 ? 0 : v[Math.max(0, Math.min(v.length - 1, (int) Math.ceil(v.length * 0.99) - 1))];
        double peor = v.length == 0 ? 0 : v[v.length - 1];
        double[] e = new double[EXTRAS];
        for (int i = 0; i < EXTRAS; i++) {
            e[i] = cuentaExtras[i] == 0 ? -1 : sumaExtras[i] / cuentaExtras[i];
        }
        MetricasPunto m = new MetricasPunto(v.length, promedio, p99, peor, cargaMs, llegoListo,
                e[0], e[1], e[2], e[3], e[4], e[5], e[6]);
        reiniciarVentana();
        return m;
    }

    private Evento cerrarEscalon() {
        double suma = 0;
        int cuentan = 0;
        boolean sinTirones = true;
        double[] msPorPunto = new double[ventanas.length];
        for (int i = 0; i < ventanas.length; i++) {
            msPorPunto[i] = metricas[i].promedioMs();
            if (ventanas[i].cuentaParaCalibrar()) {
                suma += metricas[i].promedioMs();
                cuentan++;
                sinTirones &= metricas[i].percentil99Ms() <= umbralTironMs;
            }
        }
        double promedio = suma / cuentan;
        boolean paso = promedio <= umbralMs && sinTirones;
        mediciones.add(new Medicion(escalon, msPorPunto, promedio, paso, metricas.clone()));
        punto = 0;

        if (escalones.size() == 1) {
            return terminar(escalon, !paso);
        }
        if (direccion == 0) {
            direccion = paso ? +1 : -1;
        }
        if (direccion > 0) {
            if (!paso) {
                return terminar(escalon - 1, false);
            }
            if (escalon + 1 >= escalones.size()) {
                return terminar(escalon, false);
            }
        } else {
            if (paso) {
                return terminar(escalon, false);
            }
            if (escalon == 0) {
                return terminar(0, true);
            }
        }
        escalon += direccion;
        return Evento.CAMBIAR_ESCALON;
    }

    private Evento terminar(int indice, boolean enPiso) {
        resultado = new Resultado(escalones.get(indice), indice, enPiso, List.copyOf(mediciones));
        return Evento.TERMINADO;
    }

    private void reiniciarVentana() {
        transcurridoMs = 0;
        midiendo = false;
        cargaMs = 0;
        llegoListo = false;
        cantidadCuadros = 0;
        Arrays.fill(sumaExtras, 0);
        Arrays.fill(cuentaExtras, 0);
    }

    public ParametrosCalidad escalonActual() {
        return escalones.get(escalon);
    }

    public int indiceEscalonActual() {
        return escalon;
    }

    public int puntoActual() {
        return punto;
    }

    /** true durante la medición del punto (pasada la espera): el vuelo solo avanza ahí. */
    public boolean midiendo() {
        return midiendo && resultado == null;
    }

    /** Milisegundos de medición del punto actual (0 en la espera). */
    public double msMedidos() {
        return midiendo ? transcurridoMs : 0;
    }

    /** null hasta que {@link #registrarFrame} devuelva TERMINADO. */
    public Resultado resultado() {
        return resultado;
    }

    public double umbralMs() {
        return umbralMs;
    }

    public double umbralTironMs() {
        return umbralTironMs;
    }

    public List<ParametrosCalidad> escalones() {
        return escalones;
    }
}
