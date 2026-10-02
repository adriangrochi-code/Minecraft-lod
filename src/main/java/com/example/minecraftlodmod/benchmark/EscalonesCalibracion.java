package com.example.minecraftlodmod.benchmark;

import com.example.minecraftlodmod.config.ParametrosCalidad;
import com.example.minecraftlodmod.config.QualityPreset;

import java.util.ArrayList;
import java.util.List;

/**
 * Tabla de escalones de la calibración (sección 9; resuelve el punto
 * abierto de la sección 14 "cuántas combinaciones intermedias entre
 * presets"): los presets en orden de costo, con
 * {@link #INTERMEDIOS_POR_TRAMO} escalones interpolados entre cada par de
 * presets consecutivos. Lógica pura.
 *
 * Todos los escalones llevan el MISMO fps objetivo (el del preset desde el
 * que se calibra): lo que cambia entre escalones es el costo, no la meta.
 */
public final class EscalonesCalibracion {

    /** Escalones interpolados entre dos presets vecinos: con 2, cada tramo se parte en tercios. */
    public static final int INTERMEDIOS_POR_TRAMO = 2;

    private final List<ParametrosCalidad> escalones;
    private final int indiceInicial;

    private EscalonesCalibracion(List<ParametrosCalidad> escalones, int indiceInicial) {
        this.escalones = List.copyOf(escalones);
        this.indiceInicial = indiceInicial;
    }

    /**
     * @param inicial preset elegido por el jugador como punto de partida
     */
    public static EscalonesCalibracion desde(QualityPreset inicial) {
        QualityPreset[] presets = QualityPreset.values(); // ya ordenados de más liviano a más pesado
        int fps = inicial.objetivoFpsSugerido;
        List<ParametrosCalidad> lista = new ArrayList<>();
        int indiceInicial = -1;
        for (int i = 0; i < presets.length; i++) {
            if (presets[i] == inicial) {
                indiceInicial = lista.size();
            }
            lista.add(conFps(ParametrosCalidad.de(presets[i]), fps));
            if (i + 1 < presets.length) {
                ParametrosCalidad a = ParametrosCalidad.de(presets[i]);
                ParametrosCalidad b = ParametrosCalidad.de(presets[i + 1]);
                for (int k = 1; k <= INTERMEDIOS_POR_TRAMO; k++) {
                    lista.add(interpolar(a, b, k / (double) (INTERMEDIOS_POR_TRAMO + 1), fps));
                }
            }
        }
        return new EscalonesCalibracion(lista, indiceInicial);
    }

    /** Un solo escalón, la calidad actual: medir sin buscar otra ("Medir rendimiento"). */
    public static EscalonesCalibracion soloMedir(ParametrosCalidad actual) {
        return new EscalonesCalibracion(List.of(actual), 0);
    }

    /**
     * Mezcla lineal entre dos presets vecinos. El colapso homogéneo se toma
     * del más liviano ({@code a}) hasta llegar al otro preset: es la
     * perilla de menor impacto visual, así que se sube recién al final.
     */
    static ParametrosCalidad interpolar(ParametrosCalidad a, ParametrosCalidad b, double t, int fps) {
        return new ParametrosCalidad(
                (int) Math.round(a.radioLodChunks() + (b.radioLodChunks() - a.radioLodChunks()) * t),
                a.umbralPx() + (b.umbralPx() - a.umbralPx()) * t,
                (int) Math.round(a.hilosGeneracion() + (b.hilosGeneracion() - a.hilosGeneracion()) * t),
                (int) Math.round(a.cacheRamMb() + (b.cacheRamMb() - a.cacheRamMb()) * t),
                a.colapsoDesdeNivel(),
                fps);
    }

    private static ParametrosCalidad conFps(ParametrosCalidad p, int fps) {
        return new ParametrosCalidad(p.radioLodChunks(), p.umbralPx(), p.hilosGeneracion(),
                p.cacheRamMb(), p.colapsoDesdeNivel(), fps);
    }

    public List<ParametrosCalidad> escalones() {
        return escalones;
    }

    /** Índice del escalón que corresponde al preset elegido. */
    public int indiceInicial() {
        return indiceInicial;
    }
}
