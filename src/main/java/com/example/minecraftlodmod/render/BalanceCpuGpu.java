package com.example.minecraftlodmod.render;

import com.example.minecraftlodmod.config.ConfigLod;
import com.example.minecraftlodmod.config.ModoEscalado;
import com.example.minecraftlodmod.config.ParametrosCalidad;
import com.example.minecraftlodmod.core.BalanceadorCpuGpu;
import com.example.minecraftlodmod.generation.GenerationTaskScheduler;
import com.example.minecraftlodmod.generation.GeneradorLocal;
import com.mojang.blaze3d.systems.TimerQuery;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Mide en cada cuadro cuánto tardan el CPU y la GPU y, cada segundo, deja que
 * {@link BalanceadorCpuGpu} mueva las perillas del lado que limita (sección 28
 * del documento de arquitectura). Es la opción "Auto-ajuste" de la config.
 *
 * GPU: {@link TimerQuery} de Minecraft (consulta de tiempo de OpenGL, sin
 * llamadas GL propias) entre el inicio y el fin del cuadro. Minecraft usa esa
 * misma consulta con F3 abierto: ahí se lee su {@code getGpuUtilization()}.
 * Con VulkanMod no hay medición de GPU y se ajusta sin saber qué limita.
 *
 * Solo hilo de render, salvo el cambio de concurrencia de generación, que
 * puede esperar a que terminen tareas y va en un hilo aparte.
 */
public final class BalanceCpuGpu {

    private static final Logger LOG = LogUtils.getLogger();
    static final long PERIODO_NANOS = 1_000_000_000L;
    /** Consultas de GPU sin respuesta que se toleran (la GPU va unos cuadros atrás). */
    static final int MAX_CONSULTAS = 8;
    /** Tope de FPS "sin límite" de las opciones de video. */
    static final int FPS_SIN_LIMITE = 260;

    /** La instancia en uso, para {@link Escalado} (que no conoce el render). */
    private static volatile BalanceCpuGpu actual;

    private final GeneradorLocal generador;
    private final ExecutorService aplicador = Executors.newSingleThreadExecutor(r -> {
        Thread hilo = new Thread(r, "LOD-Balance");
        hilo.setDaemon(true);
        return hilo;
    });

    private ParametrosCalidad base;
    private BalanceadorCpuGpu balanceador;
    /** Sube cada vez que cambia una perilla que obliga a replanificar. */
    private volatile int version;

    // Perillas publicadas (las lee el hilo de render al planificar y dibujar).
    private volatile double umbralPx = Double.NaN, distanciaUnBuffer = BalanceadorCpuGpu.UN_BUFFER_INICIAL,
            factorOclusion = 1, detalleExtra = 1;
    private volatile int radioChunks = -1, reduccionEscala;
    private volatile String diagnostico = "-";

    // Mediciones del período en curso.
    private long ultimoCuadroNanos, inicioPeriodoNanos;
    private double[] cuadros = new double[256];
    private int cantidadCuadros;
    private double sumaGpuMs;
    private int muestrasGpu;
    private final ArrayDeque<TimerQuery.FrameProfile> consultas = new ArrayDeque<>();
    private boolean midiendo;
    private boolean gpuNoDisponible;
    private boolean estabaActivo = true;
    private double ultimoGpuMs = Double.NaN;

    public BalanceCpuGpu(GeneradorLocal generador) {
        this.generador = generador;
        actual = this;
    }

    static BalanceCpuGpu actual() {
        return actual;
    }

    private static boolean activo() {
        return ConfigLod.CLIENTE.autoAjuste.get();
    }

    @SubscribeEvent
    public void alEmpezarCuadro(RenderFrameEvent.Pre evento) {
        // También sin auto-ajuste si el HUD muestra el uso de GPU.
        if (!(activo() || ConfigLod.CLIENTE.hudRendimiento.get()) || gpuNoDisponible || midiendo) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (RenderLod.conVulkanMod() || mc.getDebugOverlay().showDebugScreen()) {
            return; // con F3, la consulta es de Minecraft (se lee su resultado al cerrar el cuadro)
        }
        try {
            TimerQuery.getInstance().ifPresentOrElse(t -> {
                t.beginProfile();
                midiendo = true;
            }, () -> gpuNoDisponible = true);
        } catch (IllegalStateException e) {
            // Otra medición en curso (métricas de Minecraft): este cuadro no se mide.
        } catch (RuntimeException e) {
            gpuNoDisponible = true;
            LOG.warn("LOD: no se puede medir la GPU ({}); el auto-ajuste sigue sin esa medición", e.toString());
        }
    }

    @SubscribeEvent
    public void alTerminarCuadro(RenderFrameEvent.Post evento) {
        long ahora = System.nanoTime();
        if (midiendo) {
            midiendo = false;
            TimerQuery.getInstance().ifPresent(t -> consultas.addLast(t.endProfile()));
            while (consultas.size() > MAX_CONSULTAS) {
                consultas.removeFirst().cancel();
            }
        }
        while (!consultas.isEmpty() && consultas.peekFirst().isDone()) {
            sumaGpuMs += consultas.removeFirst().get() / 1e6;
            muestrasGpu++;
        }
        if (ultimoCuadroNanos != 0) {
            double ms = (ahora - ultimoCuadroNanos) / 1e6;
            if (cantidadCuadros == cuadros.length) {
                cuadros = Arrays.copyOf(cuadros, cuadros.length * 2);
            }
            cuadros[cantidadCuadros++] = ms;
            Minecraft mc = Minecraft.getInstance();
            if (mc.getDebugOverlay().showDebugScreen() && mc.getGpuUtilization() > 0) {
                // Minecraft da la GPU como % del tiempo de CPU del cuadro.
                sumaGpuMs += ms * Math.min(100, mc.getGpuUtilization()) / 100;
                muestrasGpu++;
            }
        }
        ultimoCuadroNanos = ahora;
        if (inicioPeriodoNanos == 0) {
            inicioPeriodoNanos = ahora;
        }
        if (ahora - inicioPeriodoNanos >= PERIODO_NANOS) {
            cerrarPeriodo();
            inicioPeriodoNanos = ahora;
        }
    }

    private void cerrarPeriodo() {
        int n = cantidadCuadros;
        double gpuMs = muestrasGpu > 0 ? sumaGpuMs / muestrasGpu : Double.NaN;
        cantidadCuadros = 0;
        sumaGpuMs = 0;
        muestrasGpu = 0;
        ultimoGpuMs = gpuMs;
        ParametrosCalidad c = base;
        boolean ahoraActivo = activo();
        if (estabaActivo && !ahoraActivo && c != null) {
            // Se apagó el auto-ajuste: todo vuelve al preset (el detalle y el radio los
            // devuelven los getters; la generación hay que devolverla a mano).
            version++;
            GenerationTaskScheduler s = generador.scheduler();
            if (s != null) {
                aplicador.execute(() -> s.ajustarLimiteConcurrencia(c.hilosGeneracion()));
            }
        } else if (!estabaActivo && ahoraActivo) {
            version++;
        }
        estabaActivo = ahoraActivo;
        if (!ahoraActivo || c == null || n == 0 || Minecraft.getInstance().isPaused()) {
            return;
        }
        double suma = 0;
        for (int i = 0; i < n; i++) {
            suma += cuadros[i];
        }
        Arrays.sort(cuadros, 0, n);
        double peor = cuadros[Math.min(n - 1, (int) Math.floor(n * 0.99))];
        boolean escalado = ConfigLod.CLIENTE.escalado.get() != ModoEscalado.APAGADO;
        BalanceadorCpuGpu b = balanceador;
        int concurrenciaAntes = b.limiteConcurrencia();
        boolean cambio = b.ajustar(suma / n, peor, gpuMs, objetivoMs(c), escalado);
        diagnostico = texto(b, gpuMs);
        if (!cambio) {
            return;
        }
        publicar(b);
        if (b.limiteConcurrencia() != concurrenciaAntes) {
            int limite = b.limiteConcurrencia();
            aplicador.execute(() -> {
                GenerationTaskScheduler s = generador.scheduler();
                if (s != null) {
                    s.ajustarLimiteConcurrencia(limite); // puede esperar a tareas en curso: fuera del render
                }
            });
        }
        LOG.debug("LOD: auto-ajuste {} -> umbral {} radio {} gen {} agrupado {} oclusión {} escala -{}% detalle extra {}",
                b.ultimoLimite(), b.umbralPx(), b.radioChunks(), b.limiteConcurrencia(),
                (int) b.distanciaUnBuffer(), b.factorOclusion(), b.reduccionEscala(), b.detalleExtra());
    }

    /** Frame time objetivo: el FPS objetivo del preset, o el tope de FPS del jugador si es menor. */
    static double objetivoMs(ParametrosCalidad c) {
        int fps = c.fpsObjetivo();
        int tope = Minecraft.getInstance().options.framerateLimit().get();
        if (tope > 0 && tope < FPS_SIN_LIMITE) {
            fps = Math.min(fps, tope);
        }
        return 1000.0 / fps;
    }

    private static String texto(BalanceadorCpuGpu b, double gpuMs) {
        String limite = switch (b.ultimoLimite()) {
            case CPU -> "CPU";
            case GPU -> "GPU";
            case DESCONOCIDO -> "?";
        };
        String gpu = Double.isNaN(gpuMs) ? "-" : String.format(java.util.Locale.ROOT, "%.1f", gpuMs);
        return limite + " (GPU " + gpu + " ms)" + (b.conTirones() ? " tirones" : "")
                + (b.detalleExtra() < 1 ? String.format(java.util.Locale.ROOT, " detalle +%.0f%%",
                (1 / b.detalleExtra() - 1) * 100) : "")
                + (b.enPisoAbsoluto() ? " PISO" : "");
    }

    private void publicar(BalanceadorCpuGpu b) {
        boolean replanificar = b.umbralPx() != umbralPx || b.radioChunks() != radioChunks
                || b.factorOclusion() != factorOclusion || b.detalleExtra() != detalleExtra;
        detalleExtra = b.detalleExtra();
        umbralPx = b.umbralPx();
        radioChunks = b.radioChunks();
        distanciaUnBuffer = b.distanciaUnBuffer();
        factorOclusion = b.factorOclusion();
        reduccionEscala = b.reduccionEscala();
        if (replanificar) {
            version++;
        }
    }

    /**
     * La calidad base (el preset o la calibración). Si cambió, el balanceador
     * arranca de nuevo desde ella. Hilo de render.
     */
    void usarBase(ParametrosCalidad c) {
        if (c.equals(base)) {
            return;
        }
        base = c;
        balanceador = new BalanceadorCpuGpu(c.umbralPx(), c.radioLodChunks(), c.hilosGeneracion());
        publicar(balanceador);
        version++;
        GenerationTaskScheduler s = generador.scheduler();
        if (s != null && s.limiteConcurrenciaActual() != c.hilosGeneracion()) {
            aplicador.execute(() -> s.ajustarLimiteConcurrencia(c.hilosGeneracion()));
        }
    }

    double umbralPx(ParametrosCalidad c) {
        return activo() && c.equals(base) ? umbralPx : c.umbralPx();
    }

    int radioChunks(ParametrosCalidad c) {
        return activo() && c.equals(base) ? radioChunks : c.radioLodChunks();
    }

    double distanciaUnBuffer() {
        return activo() ? distanciaUnBuffer : BalanceadorCpuGpu.UN_BUFFER_INICIAL;
    }

    double factorOclusion() {
        return activo() ? factorOclusion : 1;
    }

    /** Multiplicador del umbral y del piso de píxeles cuando sobra margen (1 = el del preset). */
    double detalleExtra(ParametrosCalidad c) {
        return activo() && c.equals(base) ? detalleExtra : 1;
    }

    /** Puntos de porcentaje a restar a la escala del escalado. */
    int reduccionEscala() {
        return activo() ? reduccionEscala : 0;
    }

    int version() {
        return version;
    }

    /** Para el HUD: "CPU (GPU 12.3 ms)", "GPU (...)" o "-" si está apagado. */
    public String diagnostico() {
        return activo() ? diagnostico : "-";
    }

    /** Resumen de perillas para el log de depuración. */
    public String resumen() {
        BalanceadorCpuGpu b = balanceador;
        if (!activo() || b == null) {
            return "off";
        }
        return String.format(java.util.Locale.ROOT, "%s umbral=%.2f radio=%d gen=%d agrupado=%d oclusion=%.2f escala=-%d%%",
                diagnostico, b.umbralPx(), b.radioChunks(), b.limiteConcurrencia(), (int) b.distanciaUnBuffer(),
                b.factorOclusion(), b.reduccionEscala());
    }

    /** Tiempo de GPU por cuadro del último segundo, en ms; NaN si no se mide (Vulkan, driver sin consultas). */
    public double ultimoGpuMs() {
        return ultimoGpuMs;
    }
}
