package com.example.minecraftlodmod.generation;

import com.example.minecraftlodmod.core.PerformanceAutoTuner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

class ControlDeRendimientoTest {

    private static PerformanceAutoTuner tuner(int concurrenciaMaxima) {
        return new PerformanceAutoTuner(2.5, 160, 0.5, 8.0, 0, 320, 0.5, 16, 1, concurrenciaMaxima);
    }

    @Test
    void conFramesLentosBajaElLimiteRealDelScheduler() {
        GenerationTaskScheduler scheduler = new GenerationTaskScheduler(3);
        try {
            ControlDeRendimiento control = new ControlDeRendimiento(tuner(3), scheduler, 16.6);

            control.registrarFrame(40.0);
            control.registrarFrame(30.0);
            control.ejecutarCiclo();

            assertEquals(2, scheduler.limiteConcurrenciaActual());
            assertEquals(2.5, control.umbralPx(), "La concurrencia se baja antes que el detalle");
        } finally {
            scheduler.apagar();
        }
    }

    @Test
    void conMargenRecuperaElLimiteDelScheduler() {
        GenerationTaskScheduler scheduler = new GenerationTaskScheduler(2);
        try {
            ControlDeRendimiento control = new ControlDeRendimiento(tuner(2), scheduler, 16.6);
            control.registrarFrame(40.0);
            control.ejecutarCiclo();
            assertEquals(1, scheduler.limiteConcurrenciaActual());

            control.registrarFrame(5.0);
            control.ejecutarCiclo();

            assertEquals(2, scheduler.limiteConcurrenciaActual());
        } finally {
            scheduler.apagar();
        }
    }

    @Test
    void usaElPromedioDeLosFramesDelCiclo() {
        GenerationTaskScheduler scheduler = new GenerationTaskScheduler(1);
        try {
            ControlDeRendimiento control = new ControlDeRendimiento(tuner(1), scheduler, 16.6);

            // Promedio 16.0 ms: ni lento ni con margen de sobra -> no cambia nada.
            control.registrarFrame(10.0);
            control.registrarFrame(22.0);
            control.ejecutarCiclo();

            assertEquals(2.5, control.umbralPx());
            assertEquals(160, control.radioActivo());
        } finally {
            scheduler.apagar();
        }
    }

    @Test
    void sinFramesRegistradosNoAjusta() {
        GenerationTaskScheduler scheduler = new GenerationTaskScheduler(2);
        try {
            ControlDeRendimiento control = new ControlDeRendimiento(tuner(2), scheduler, 16.6);

            control.ejecutarCiclo();

            assertEquals(2, scheduler.limiteConcurrenciaActual());
            assertEquals(2.5, control.umbralPx());
        } finally {
            scheduler.apagar();
        }
    }

    @Test
    @Timeout(5)
    void elHiloDeControlAjustaSolo() throws InterruptedException {
        GenerationTaskScheduler scheduler = new GenerationTaskScheduler(3);
        ControlDeRendimiento control = new ControlDeRendimiento(tuner(3), scheduler, 16.6);
        try {
            control.iniciar(20);
            while (scheduler.limiteConcurrenciaActual() == 3) {
                control.registrarFrame(50.0);
                Thread.sleep(5);
            }
            assertTrue(scheduler.limiteConcurrenciaActual() < 3);
        } finally {
            control.detener();
            scheduler.apagar();
        }
    }

    @Test
    void convierteFpsAFrameTime() {
        assertEquals(25.0, ControlDeRendimiento.objetivoMsDesdeFps(40), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> ControlDeRendimiento.objetivoMsDesdeFps(0));
    }
}
