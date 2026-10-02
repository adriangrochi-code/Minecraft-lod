package com.example.minecraftlodmod.benchmark;

import java.util.ArrayList;
import java.util.List;

/**
 * Además del escalón (radio, umbral, hilos, RAM), qué otras opciones
 * conviene cambiar según cómo le fue a la máquina en la calibración. Lógica pura.
 *
 * - Escalado FSR 1: si ni el escalón más liviano alcanza el objetivo y la GPU
 *   está ocupada casi todo el cuadro (límite de GPU: bajar la resolución gana).
 *   Es el caso de las iGPU viejas como la de la A275.
 * - Oclusión en costados: si el escalón más alto pasa con mucho margen y la GPU
 *   no es el límite (sobra para el +18% de GPU que cuesta). Es el caso de una
 *   GPU dedicada como la GTX 1060.
 *
 * Se mira la medición del escalón elegido (o el más liviano, en el piso) y
 * solo los puntos que cuentan para calibrar.
 */
public final class RecomendacionesBenchmark {

    /** GPU ocupada al menos esta fracción del cuadro = el límite es la GPU (como el auto-ajuste). */
    static final double FRACCION_GPU = 0.85;
    /** El escalón más alto "sobra" si su promedio es a lo sumo esto del objetivo. */
    static final double MARGEN_SOBRADO = 0.6;

    public record Recomendaciones(boolean escalado, boolean oclusionCostados, List<String> motivos) {
    }

    private RecomendacionesBenchmark() {
    }

    /**
     * @param cuentan     si cada punto cuenta para calibrar
     * @param objetivoMs  frame time objetivo
     * @param ultimoIndice índice del escalón más pesado de la tabla
     */
    public static Recomendaciones de(CalibradorBenchmark.Resultado r, boolean[] cuentan, double objetivoMs,
                                     int ultimoIndice) {
        CalibradorBenchmark.Medicion m = null;
        for (CalibradorBenchmark.Medicion x : r.mediciones()) {
            if (x.escalon() == r.indice()) {
                m = x;
            }
        }
        List<String> motivos = new ArrayList<>();
        if (m == null) {
            return new Recomendaciones(false, false, motivos);
        }
        double fraccionGpu = fraccionGpu(m, cuentan);
        boolean limiteGpu = fraccionGpu >= FRACCION_GPU;
        boolean escalado = r.enPiso() && limiteGpu;
        if (escalado) {
            motivos.add(String.format(java.util.Locale.ROOT,
                    "escalado FSR 1: ni el escalón más liviano alcanza y la GPU trabaja el %.0f%% del cuadro",
                    fraccionGpu * 100));
        }
        boolean sobrado = !r.enPiso() && r.indice() == ultimoIndice && m.paso()
                && m.msPromedio() <= objetivoMs * MARGEN_SOBRADO && fraccionGpu >= 0 && !limiteGpu;
        if (sobrado) {
            motivos.add(String.format(java.util.Locale.ROOT,
                    "oclusión en costados: el escalón más alto usa el %.0f%% del tiempo objetivo",
                    m.msPromedio() / objetivoMs * 100));
        }
        return new Recomendaciones(escalado, sobrado, motivos);
    }

    /** Fracción del cuadro con la GPU ocupada en los puntos que cuentan; -1 sin medición de GPU. */
    static double fraccionGpu(CalibradorBenchmark.Medicion m, boolean[] cuentan) {
        double suma = 0;
        int n = 0;
        for (int i = 0; i < m.puntos().length; i++) {
            CalibradorBenchmark.MetricasPunto p = m.puntos()[i];
            if (cuentan[i] && p.gpuMs() >= 0 && p.promedioMs() > 0) {
                suma += Math.min(1, p.gpuMs() / p.promedioMs());
                n++;
            }
        }
        return n == 0 ? -1 : suma / n;
    }
}
