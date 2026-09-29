package com.example.minecraftlodmod.generation;

import com.example.minecraftlodmod.core.PerformanceAutoTuner;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

/**
 * Bucle de auto-ajuste de la sección 7/21/23: junta frame times, cada
 * período llama a {@link PerformanceAutoTuner#ajustar} y aplica la tercera
 * perilla sobre {@link GenerationTaskScheduler#ajustarLimiteConcurrencia}.
 *
 * Corre en su propio hilo de control porque bajar el límite del scheduler
 * puede bloquear hasta que terminen tareas en curso — nunca debe pasar en el
 * hilo principal ni en el de render. El hilo de render solo llama a
 * {@link #registrarFrame} (sin bloqueos) y lee los valores publicados.
 */
public final class ControlDeRendimiento {

    private final PerformanceAutoTuner tuner;
    private final GenerationTaskScheduler scheduler;
    private final double frameTimeObjetivoMs;

    private final DoubleAdder sumaFrameTimesMs = new DoubleAdder();
    private final LongAdder cantidadFrames = new LongAdder();

    // Snapshot publicado para el hilo de render: el tuner no es thread-safe.
    private volatile double umbralPx;
    private volatile int radioActivo;
    private volatile boolean enPisoAbsoluto;

    private ScheduledExecutorService hiloDeControl;

    public ControlDeRendimiento(PerformanceAutoTuner tuner, GenerationTaskScheduler scheduler,
                                double frameTimeObjetivoMs) {
        if (frameTimeObjetivoMs <= 0) {
            throw new IllegalArgumentException("frameTimeObjetivoMs debe ser positivo, fue: " + frameTimeObjetivoMs);
        }
        this.tuner = tuner;
        this.scheduler = scheduler;
        this.frameTimeObjetivoMs = frameTimeObjetivoMs;
        publicar();
    }

    /** Frame time objetivo en ms para un FPS objetivo (ej. {@code QualityPreset.objetivoFpsSugerido}). */
    public static double objetivoMsDesdeFps(int fps) {
        if (fps <= 0) {
            throw new IllegalArgumentException("fps debe ser positivo, fue: " + fps);
        }
        return 1000.0 / fps;
    }

    /** Llamar una vez por frame desde el hilo de render. No bloquea. */
    public void registrarFrame(double frameTimeMs) {
        sumaFrameTimesMs.add(frameTimeMs);
        cantidadFrames.increment();
    }

    /**
     * Un ciclo de ajuste. Sin frames registrados desde el último ciclo (juego
     * pausado, ventana minimizada) no ajusta nada: no hay medición real.
     */
    public synchronized void ejecutarCiclo() {
        long frames = cantidadFrames.sumThenReset();
        double suma = sumaFrameTimesMs.sumThenReset();
        if (frames == 0) {
            return;
        }
        tuner.ajustar(suma / frames, frameTimeObjetivoMs);
        if (tuner.limiteConcurrencia() != scheduler.limiteConcurrenciaActual()) {
            scheduler.ajustarLimiteConcurrencia(tuner.limiteConcurrencia());
        }
        publicar();
    }

    private void publicar() {
        umbralPx = tuner.umbralPx();
        radioActivo = tuner.radioActivo();
        enPisoAbsoluto = tuner.enPisoAbsoluto();
    }

    /** Arranca el hilo de control con el período dado (la sección 7 sugiere ~1000 ms). */
    public synchronized void iniciar(long periodoMs) {
        if (hiloDeControl != null) {
            throw new IllegalStateException("El control de rendimiento ya está iniciado");
        }
        hiloDeControl = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread hilo = new Thread(r, "LOD-ControlDeRendimiento");
            hilo.setDaemon(true);
            return hilo;
        });
        hiloDeControl.scheduleAtFixedRate(this::ejecutarCiclo, periodoMs, periodoMs, TimeUnit.MILLISECONDS);
    }

    public synchronized void detener() {
        if (hiloDeControl != null) {
            hiloDeControl.shutdownNow();
            hiloDeControl = null;
        }
    }

    public double umbralPx() {
        return umbralPx;
    }

    public int radioActivo() {
        return radioActivo;
    }

    public boolean enPisoAbsoluto() {
        return enPisoAbsoluto;
    }
}
