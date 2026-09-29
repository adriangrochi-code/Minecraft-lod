package com.example.minecraftlodmod.benchmark;

import com.example.minecraftlodmod.config.ParametrosCalidad;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Máquina de estados de la calibración (sección 9, paso 3), sin Minecraft:
 * quien la maneja le pasa el frame time de cada frame y reacciona a los
 * {@link Evento}s (mover al jugador de punto, aplicar otro escalón).
 *
 * Por escalón se recorren todos los puntos representativos; en cada uno se
 * espera {@code calentamientoMs} (carga de chunks, generación de LOD) y
 * después se mide durante {@code medicionMs}. El frame time del escalón es
 * el promedio de los promedios por punto (cada punto pesa igual, aunque en
 * uno haya más frames que en otro).
 *
 * Búsqueda: desde el escalón del preset elegido, si pasa se sube hasta el
 * primero que falla (resultado: el último que pasó); si falla se baja hasta
 * el primero que pasa. Si ni el más liviano pasa, el resultado es ese con
 * {@link Resultado#enPiso()} — mismo concepto que
 * {@code PerformanceAutoTuner.enPisoAbsoluto()}.
 *
 * "Pasa" deja {@link #MARGEN} de holgura sobre el frame time objetivo: la
 * calibración mide escenas fijas y el juego real tiene picos (mobs,
 * redstone, otros mods) que absorbe después el auto-ajuste dinámico.
 */
public final class CalibradorBenchmark {

    /** Un escalón pasa si su frame time promedio es a lo sumo este factor del objetivo. */
    public static final double MARGEN = 0.9;

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

    /** Frame time promedio medido para un escalón, por punto y total. */
    public record Medicion(int escalon, double[] msPorPunto, double msPromedio, boolean paso) {
    }

    public record Resultado(ParametrosCalidad elegido, int indice, boolean enPiso, List<Medicion> mediciones) {
    }

    private final List<ParametrosCalidad> escalones;
    private final int cantidadPuntos;
    private final double calentamientoMs;
    private final double medicionMs;
    private final double umbralMs;

    private int escalon;
    private int punto;
    private double transcurridoMs;
    private double sumaMs;
    private int frames;
    private final double[] msPorPunto;

    /** +1 subiendo, -1 bajando, 0 todavía en el primer escalón. */
    private int direccion;
    private final List<Medicion> mediciones = new ArrayList<>();
    private Resultado resultado;

    public CalibradorBenchmark(EscalonesCalibracion tabla, int cantidadPuntos,
                               double calentamientoMs, double medicionMs) {
        if (cantidadPuntos < 1 || calentamientoMs < 0 || medicionMs <= 0) {
            throw new IllegalArgumentException("Parámetros de calibración inválidos");
        }
        this.escalones = tabla.escalones();
        this.escalon = tabla.indiceInicial();
        this.cantidadPuntos = cantidadPuntos;
        this.calentamientoMs = calentamientoMs;
        this.medicionMs = medicionMs;
        this.umbralMs = escalones.get(escalon).frameTimeObjetivoMs() * MARGEN;
        this.msPorPunto = new double[cantidadPuntos];
    }

    /**
     * Registrar un frame. La primera llamada ya cuenta: quien maneja la
     * calibración tiene que haber aplicado {@link #escalonActual()} y movido
     * al jugador a {@link #puntoActual()} antes de empezar.
     */
    public Evento registrarFrame(double frameMs) {
        if (resultado != null) {
            return Evento.TERMINADO;
        }
        transcurridoMs += frameMs;
        if (transcurridoMs <= calentamientoMs) {
            return Evento.NADA;
        }
        sumaMs += frameMs;
        frames++;
        if (transcurridoMs < calentamientoMs + medicionMs) {
            return Evento.NADA;
        }

        msPorPunto[punto] = sumaMs / frames;
        reiniciarVentana();
        if (punto + 1 < cantidadPuntos) {
            punto++;
            return Evento.CAMBIAR_PUNTO;
        }
        return cerrarEscalon();
    }

    private Evento cerrarEscalon() {
        double promedio = Arrays.stream(msPorPunto).average().orElseThrow();
        boolean paso = promedio <= umbralMs;
        mediciones.add(new Medicion(escalon, msPorPunto.clone(), promedio, paso));
        punto = 0;

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
        sumaMs = 0;
        frames = 0;
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

    /** null hasta que {@link #registrarFrame} devuelva TERMINADO. */
    public Resultado resultado() {
        return resultado;
    }

    public double umbralMs() {
        return umbralMs;
    }
}
