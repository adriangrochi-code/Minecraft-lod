package com.example.minecraftlodmod.core;

/**
 * Auto-ajuste de rendimiento con DOS perillas, no una sola (ver sección 7
 * del documento de arquitectura, ahora extendida):
 *
 *   1. umbralPx — como ya existía en LodSelector: sube (baja detalle) o
 *      baja (sube detalle) dentro del radio activo actual.
 *   2. radioActivo — NUEVO: el radio de LOD realmente en uso puede
 *      reducirse por debajo del techo del preset (hasta un mínimo
 *      configurable, potencialmente 0 = LOD efectivamente apagado, solo
 *      terreno vanilla) cuando ni el umbral más permisivo alcanza para
 *      sostener el frame time objetivo.
 *
 * Por qué hace falta la segunda perilla: en hardware muy por debajo del
 * preset "Bajo" (ver sección 21 — rango desde una Lenovo ThinkPad A275
 * hasta una PC de gama alta), subir umbralPx tiene un límite práctico de
 * cuánto puede aliviar la carga; en algún punto la única opción honesta es
 * generar/dibujar MENOS ÁREA, no solo menos detalle por área. Esta clase
 * decide cuándo tocar cada perilla, en ese orden de prioridad: primero
 * empeorar detalle (menos intrusivo visualmente), recién después reducir
 * el radio (más notorio, pero necesario como último recurso).
 *
 * Simétricamente, en hardware potente con margen de sobra, primero se
 * recupera el radio hasta el techo del preset, y recién después se mejora
 * el detalle (bajar umbralPx) — así en una PC de gama alta el radio llega
 * al máximo del preset antes de "gastar" margen en más detalle por sección.
 */
public final class PerformanceAutoTuner {

    private double umbralPx;
    private int radioActivo;

    private final double umbralPxMinimo;
    private final double umbralPxMaximo;
    private final int radioMinimo;
    private final int radioMaximo; // techo del preset actual

    private final double pasoUmbral;
    private final int pasoRadio;

    public PerformanceAutoTuner(double umbralPxInicial, int radioInicial,
                                 double umbralPxMinimo, double umbralPxMaximo,
                                 int radioMinimo, int radioMaximo,
                                 double pasoUmbral, int pasoRadio) {
        this.umbralPx = umbralPxInicial;
        this.radioActivo = radioInicial;
        this.umbralPxMinimo = umbralPxMinimo;
        this.umbralPxMaximo = umbralPxMaximo;
        this.radioMinimo = radioMinimo;
        this.radioMaximo = radioMaximo;
        this.pasoUmbral = pasoUmbral;
        this.pasoRadio = pasoRadio;
    }

    public double umbralPx() {
        return umbralPx;
    }

    public int radioActivo() {
        return radioActivo;
    }

    /**
     * Llamar periódicamente (ej. cada 1s) con el frame time promedio medido.
     * Ajusta como mucho una perilla por llamada, para que los cambios sean
     * graduales y no se note un salto brusco de golpe.
     */
    public void ajustar(double frameTimeMsPromedio, double frameTimeMsObjetivo) {
        boolean vaLento = frameTimeMsPromedio > frameTimeMsObjetivo;
        boolean sobraMargen = frameTimeMsPromedio < frameTimeMsObjetivo * 0.8;

        if (vaLento) {
            if (umbralPx < umbralPxMaximo) {
                umbralPx = Math.min(umbralPxMaximo, umbralPx + pasoUmbral);
            } else if (radioActivo > radioMinimo) {
                radioActivo = Math.max(radioMinimo, radioActivo - pasoRadio);
            }
            // Si ya está en el piso de ambas perillas, no hay más margen —
            // se queda ahí; eso es "cero overhead posible" en ese hardware.
        } else if (sobraMargen) {
            if (radioActivo < radioMaximo) {
                radioActivo = Math.min(radioMaximo, radioActivo + pasoRadio);
            } else if (umbralPx > umbralPxMinimo) {
                umbralPx = Math.max(umbralPxMinimo, umbralPx - pasoUmbral);
            }
        }
    }

    /** true si el sistema llegó al piso absoluto (radio y detalle mínimos) — señal de hardware insuficiente incluso para el mínimo. */
    public boolean enPisoAbsoluto() {
        return radioActivo <= radioMinimo && umbralPx >= umbralPxMaximo;
    }
}
