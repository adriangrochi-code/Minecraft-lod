package com.example.minecraftlodmod.render;

import java.util.Arrays;

/**
 * Tiempos de cuadro de una ventana (lógica pura, para el HUD de rendimiento
 * y el log de depuración): promedio, el peor cuadro y el "1% bajo" (el
 * percentil 99 del tiempo de cuadro, lo que se siente como tirones aunque el
 * promedio sea bueno).
 */
public final class EstadisticaFrames {

    /** Cuadros de más en una ventana se ignoran (1 s a más de 4096 FPS). */
    static final int CAPACIDAD = 4096;

    private final double[] ms = new double[CAPACIDAD];
    private int cantidad;

    public record Resumen(int cuadros, double segundos, double promedioMs, double peorMs, double percentil99Ms) {
        public double fps() {
            return segundos <= 0 ? 0 : cuadros / segundos;
        }

        /** FPS equivalentes al 1% de cuadros más lentos. */
        public double fpsUnoPorCientoBajo() {
            return percentil99Ms <= 0 ? 0 : 1000.0 / percentil99Ms;
        }
    }

    public void agregar(double frameMs) {
        if (cantidad < CAPACIDAD) {
            ms[cantidad++] = frameMs;
        }
    }

    /** Resume y vacía la ventana. */
    public Resumen cerrar(double segundos) {
        if (cantidad == 0) {
            return new Resumen(0, segundos, 0, 0, 0);
        }
        double[] v = Arrays.copyOf(ms, cantidad);
        Arrays.sort(v);
        double suma = 0;
        for (double x : v) {
            suma += x;
        }
        int indice99 = Math.min(v.length - 1, (int) Math.ceil(v.length * 0.99) - 1);
        Resumen r = new Resumen(v.length, segundos, suma / v.length, v[v.length - 1], v[Math.max(0, indice99)]);
        cantidad = 0;
        return r;
    }
}
