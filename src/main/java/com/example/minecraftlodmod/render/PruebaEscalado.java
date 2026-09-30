package com.example.minecraftlodmod.render;

import com.example.minecraftlodmod.config.ModoEscalado;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * Escalado "solo si gana" (lógica pura salvo el log): bajar la resolución
 * solo sirve si el límite es la GPU dibujando píxeles. Si el límite es el
 * procesador o la cantidad de vértices (lo más común en Minecraft), las
 * pasadas del escalador cuestan y el FPS baja. Cada {@link #REPETIR_NANOS}
 * se miden {@link #MEDIR_NANOS} con escalado y otro tanto sin él, y se deja
 * el que dio cuadros más cortos (con escalado, si gana al menos
 * {@link #GANANCIA_MINIMA}).
 *
 * Se llama una vez por cuadro, justo antes de dibujar el mundo: el tiempo
 * entre llamadas es el tiempo de cuadro. Los primeros cuadros después de
 * cada cambio no cuentan (el historial temporal y los buffers se rearman).
 */
public final class PruebaEscalado {

    private static final Logger LOG = LogUtils.getLogger();

    static final long MEDIR_NANOS = 3_000_000_000L;
    static final long REPETIR_NANOS = 120_000_000_000L;
    static final int CUADROS_DESCARTADOS = 20;
    /** Con escalado tiene que ser al menos un 3% más rápido; si no, no vale la pérdida de nitidez. */
    static final double GANANCIA_MINIMA = 0.97;

    enum Fase { CON, SIN, DECIDIDO }

    private Fase fase = Fase.CON;
    private ModoEscalado modoMedido;
    private long faseDesde, ultimoCuadro;
    private int descartar = CUADROS_DESCARTADOS;
    private double sumaCon, sumaSin;
    private int cuadrosCon, cuadrosSin;
    private boolean conviene = true;
    private long proximaPrueba;
    private double ultimoCon, ultimoSin;

    /**
     * @param pedido   modo que pide la config (no APAGADO)
     * @param ahora    System.nanoTime()
     * @param activada la opción "solo si gana"; apagada, siempre se escala
     * @return true si este cuadro se dibuja con escalado
     */
    public boolean usar(ModoEscalado pedido, long ahora, boolean activada) {
        long dt = ultimoCuadro == 0 ? 0 : ahora - ultimoCuadro;
        ultimoCuadro = ahora;
        if (!activada) {
            return true;
        }
        if (pedido != modoMedido || dt > 1_000_000_000L) {
            // Otro modo, o se volvió de un menú o de una pausa larga: medir de nuevo.
            modoMedido = pedido;
            empezar(ahora);
            return true;
        }
        switch (fase) {
            case CON -> {
                if (contar(dt)) {
                    sumaCon += dt;
                    cuadrosCon++;
                }
                if (ahora - faseDesde >= MEDIR_NANOS && cuadrosCon > 0) {
                    fase = Fase.SIN;
                    faseDesde = ahora;
                    descartar = CUADROS_DESCARTADOS;
                    return false;
                }
                return true;
            }
            case SIN -> {
                if (contar(dt)) {
                    sumaSin += dt;
                    cuadrosSin++;
                }
                if (ahora - faseDesde >= MEDIR_NANOS && cuadrosSin > 0) {
                    decidir(ahora);
                    return conviene;
                }
                return false;
            }
            default -> {
                if (ahora >= proximaPrueba) {
                    empezar(ahora);
                    return true;
                }
                return conviene;
            }
        }
    }

    private boolean contar(long dt) {
        if (descartar > 0) {
            descartar--;
            return false;
        }
        return dt > 0;
    }

    private void empezar(long ahora) {
        fase = Fase.CON;
        faseDesde = ahora;
        descartar = CUADROS_DESCARTADOS;
        sumaCon = sumaSin = 0;
        cuadrosCon = cuadrosSin = 0;
    }

    private void decidir(long ahora) {
        double con = sumaCon / cuadrosCon / 1e6, sin = sumaSin / cuadrosSin / 1e6;
        boolean antes = conviene;
        conviene = con <= sin * GANANCIA_MINIMA;
        ultimoCon = con;
        ultimoSin = sin;
        fase = Fase.DECIDIDO;
        proximaPrueba = ahora + REPETIR_NANOS;
        if (conviene != antes || LOG.isDebugEnabled()) {
            LOG.info("LOD: escalado {}: {} ms por cuadro con escalado, {} ms sin (se vuelve a medir en 2 min)",
                    conviene ? "prendido" : "APAGADO porque no da ganancia en esta PC ahora",
                    String.format("%.2f", con), String.format("%.2f", sin));
        }
    }

    /** Para el HUD: false si la última medición dijo que no conviene. */
    public boolean conviene() {
        return conviene;
    }

    double ultimoCon() {
        return ultimoCon;
    }

    double ultimoSin() {
        return ultimoSin;
    }
}
