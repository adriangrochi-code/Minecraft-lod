package com.example.minecraftlodmod.benchmark;

import com.example.minecraftlodmod.config.ParametrosCalidad;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Texto del informe de una corrida del benchmark (calibración o "Medir
 * rendimiento"): hardware, ajustes y, por escalón, una tabla por punto. Es
 * lo que el jugador comparte para comparar máquinas o versiones, así que va
 * en texto plano con columnas alineadas. Lógica pura.
 */
public final class InformeBenchmark {

    private InformeBenchmark() {
    }

    /**
     * @param titulo   "Medición" o "Calibración desde MEDIO"
     * @param sistema  hardware y software (clave → valor), en orden
     * @param ajustes  resumen de la config del LOD
     * @param puntos   nombres de los puntos, en el orden de las mediciones
     * @param cuentan  si cada punto cuenta para decidir el escalón
     */
    public static String armar(String titulo, Map<String, String> sistema, String ajustes, List<String> puntos,
                               boolean[] cuentan, CalibradorBenchmark.Resultado resultado,
                               List<ParametrosCalidad> escalones, double umbralMs, double umbralTironMs,
                               boolean soloMedir) {
        StringBuilder s = new StringBuilder();
        s.append("== Benchmark del LOD: ").append(titulo).append(" ==\n\n");
        int ancho = sistema.keySet().stream().mapToInt(String::length).max().orElse(0);
        sistema.forEach((k, v) -> s.append(String.format(Locale.ROOT, "%-" + ancho + "s  %s%n", k, v)));
        s.append('\n').append("Ajustes: ").append(ajustes).append('\n');
        s.append(String.format(Locale.ROOT,
                "Criterio: promedio <= %.1f ms (90%% del objetivo) y 1%% peor <= %.1f ms en los puntos marcados con *%n",
                umbralMs, umbralTironMs));

        for (CalibradorBenchmark.Medicion m : resultado.mediciones()) {
            ParametrosCalidad p = escalones.get(m.escalon());
            s.append('\n').append(String.format(Locale.ROOT,
                    "-- Escalón %d: radio %d chunks, umbral %.2f px, %d hilos, %d MB, colapso desde %d, objetivo %d FPS -> %s --%n",
                    m.escalon(), p.radioLodChunks(), p.umbralPx(), p.hilosGeneracion(), p.cacheRamMb(),
                    p.colapsoDesdeNivel(), p.fpsObjetivo(),
                    m.paso() ? "PASA" : "NO PASA"));
            s.append(String.format(Locale.ROOT, "%-13s %7s %8s %8s %8s %6s %10s %9s %8s %10s%n",
                    "punto", "FPS", "1% bajo", "peor ms", "GPU ms", "CPU %", "servidor", "vért. (M)", "llamadas",
                    "carga LOD"));
            for (int i = 0; i < m.puntos().length; i++) {
                CalibradorBenchmark.MetricasPunto x = m.puntos()[i];
                String nombre = (cuentan[i] ? "*" : " ") + puntos.get(i);
                s.append(String.format(Locale.ROOT, "%-13s %7.1f %8.1f %8.1f %8s %6s %10s %9s %8s %10s%n",
                        recortar(nombre, 13), x.fps(), x.fpsUnoPorCientoBajo(), x.peorMs(),
                        dato(x.gpuMs(), "%.1f"), dato(x.cpuJuego(), "%.0f"), dato(x.msServidor(), "%.1f ms"),
                        dato(x.verticesDibujados() < 0 ? -1 : x.verticesDibujados() / 1e6, "%.2f"),
                        dato(x.llamadas(), "%.0f"),
                        String.format(Locale.ROOT, "%.1f s%s", x.cargaMs() / 1000, x.lodListo() ? "" : " (!)")));
            }
            s.append(String.format(Locale.ROOT, "promedio de los puntos *: %.2f ms (%.1f FPS)%n",
                    m.msPromedio(), m.msPromedio() <= 0 ? 0 : 1000 / m.msPromedio()));
        }

        s.append('\n');
        if (soloMedir) {
            CalibradorBenchmark.Medicion m = resultado.mediciones().get(0);
            s.append(m.paso() ? "Resultado: la config actual alcanza el objetivo.\n"
                    : "Resultado: la config actual NO alcanza el objetivo (promedio o tirones).\n");
        } else {
            ParametrosCalidad e = resultado.elegido();
            s.append(String.format(Locale.ROOT, "Resultado: escalón %d (radio %d chunks, umbral %.2f px)%s%n",
                    resultado.indice(), e.radioLodChunks(), e.umbralPx(),
                    resultado.enPiso() ? ", en el piso: ni el más liviano alcanza el objetivo" : ""));
        }
        boolean algunoSinTerminar = resultado.mediciones().stream()
                .flatMap(m -> java.util.Arrays.stream(m.puntos())).anyMatch(x -> !x.lodListo());
        if (algunoSinTerminar) {
            s.append("(!) en carga LOD: se midió sin que el LOD terminara de armarse (se esperó el máximo).\n");
        }
        return s.toString();
    }

    private static String dato(double v, String formato) {
        return v < 0 || Double.isNaN(v) ? "-" : String.format(Locale.ROOT, formato, v);
    }

    private static String recortar(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }
}
