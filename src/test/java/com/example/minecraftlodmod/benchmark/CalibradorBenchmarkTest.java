package com.example.minecraftlodmod.benchmark;

import com.example.minecraftlodmod.config.ParametrosCalidad;
import com.example.minecraftlodmod.config.QualityPreset;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.IntToDoubleFunction;

import static org.junit.jupiter.api.Assertions.*;

class CalibradorBenchmarkTest {

    private static final int PUNTOS = 4;
    private static final double CALENTAMIENTO = 1000, MEDICION = 500;

    /**
     * Corre la calibración con un "hardware" simulado: el frame time depende
     * solo del escalón aplicado. Devuelve el resultado y verifica en el
     * camino la secuencia de eventos.
     */
    private static CalibradorBenchmark.Resultado correr(EscalonesCalibracion tabla,
                                                        IntToDoubleFunction msPorEscalon) {
        CalibradorBenchmark c = new CalibradorBenchmark(tabla, PUNTOS, CALENTAMIENTO, MEDICION);
        int puntoEsperado = 0;
        for (int i = 0; i < 1_000_000; i++) {
            CalibradorBenchmark.Evento e = c.registrarFrame(msPorEscalon.applyAsDouble(c.indiceEscalonActual()));
            switch (e) {
                case NADA -> { }
                case CAMBIAR_PUNTO -> assertEquals(++puntoEsperado, c.puntoActual());
                case CAMBIAR_ESCALON -> {
                    assertEquals(PUNTOS - 1, puntoEsperado, "Cada escalón recorre todos los puntos");
                    puntoEsperado = 0;
                    assertEquals(0, c.puntoActual());
                }
                case TERMINADO -> {
                    return c.resultado();
                }
            }
        }
        throw new AssertionError("La calibración no terminó");
    }

    @Test
    void laTablaTieneLosPresetsEnOrdenYConIntermedios() {
        EscalonesCalibracion tabla = EscalonesCalibracion.desde(QualityPreset.MEDIO);
        List<ParametrosCalidad> e = tabla.escalones();
        int presets = QualityPreset.values().length;

        assertEquals(presets + (presets - 1) * EscalonesCalibracion.INTERMEDIOS_POR_TRAMO, e.size());
        assertEquals(QualityPreset.MEDIO.radioLodChunks, e.get(tabla.indiceInicial()).radioLodChunks());
        for (int i = 1; i < e.size(); i++) {
            assertTrue(e.get(i).radioLodChunks() >= e.get(i - 1).radioLodChunks(), "Radio no decreciente");
            assertTrue(e.get(i).umbralPx() <= e.get(i - 1).umbralPx(), "Umbral no creciente (más detalle)");
            assertEquals(QualityPreset.MEDIO.objetivoFpsSugerido, e.get(i).fpsObjetivo(),
                    "Todos los escalones apuntan al fps del preset elegido");
        }
    }

    @Test
    void conMargenDeSobraSubeHastaElPrimeroQueFalla() {
        EscalonesCalibracion tabla = EscalonesCalibracion.desde(QualityPreset.MEDIO); // 40 fps -> umbral 22.5 ms
        int inicial = tabla.indiceInicial();
        // Pasa hasta inicial+3, falla desde inicial+4.
        CalibradorBenchmark.Resultado r = correr(tabla, i -> i <= inicial + 3 ? 20.0 : 30.0);

        assertEquals(inicial + 3, r.indice());
        assertFalse(r.enPiso());
        assertEquals(5, r.mediciones().size(), "Midió inicial..inicial+4");
    }

    @Test
    void siFallaBajaHastaElPrimeroQuePasa() {
        EscalonesCalibracion tabla = EscalonesCalibracion.desde(QualityPreset.ALTO);
        int inicial = tabla.indiceInicial();
        CalibradorBenchmark.Resultado r = correr(tabla, i -> i <= inicial - 2 ? 5.0 : 100.0);

        assertEquals(inicial - 2, r.indice());
        assertFalse(r.enPiso());
    }

    @Test
    void siNiElMasLivianoPasaQuedaEnElPiso() {
        EscalonesCalibracion tabla = EscalonesCalibracion.desde(QualityPreset.BAJO);
        CalibradorBenchmark.Resultado r = correr(tabla, i -> 500.0);

        assertEquals(0, r.indice());
        assertTrue(r.enPiso());
        assertEquals(tabla.escalones().get(0), r.elegido());
    }

    @Test
    void conHardwareSobradoTerminaEnElMasPesado() {
        EscalonesCalibracion tabla = EscalonesCalibracion.desde(QualityPreset.ULTRA);
        CalibradorBenchmark.Resultado r = correr(tabla, i -> 1.0);
        assertEquals(tabla.escalones().size() - 1, r.indice());
    }

    @Test
    void elCalentamientoNoCuentaEnLaMedicion() {
        EscalonesCalibracion tabla = EscalonesCalibracion.desde(QualityPreset.MEDIO);
        CalibradorBenchmark c = new CalibradorBenchmark(tabla, 1, CALENTAMIENTO, MEDICION);
        // Calentamiento con frames terribles (carga de chunks), medición buena.
        double t = 0;
        while (t < CALENTAMIENTO) {
            assertEquals(CalibradorBenchmark.Evento.NADA, c.registrarFrame(250));
            t += 250;
        }
        CalibradorBenchmark.Evento e;
        do {
            e = c.registrarFrame(10);
        } while (e == CalibradorBenchmark.Evento.NADA);

        // Si el calentamiento contara, el promedio superaría el umbral y bajaría.
        assertEquals(CalibradorBenchmark.Evento.CAMBIAR_ESCALON, e);
        assertEquals(tabla.indiceInicial() + 1, c.indiceEscalonActual(), "Pasó: sube un escalón");
    }

    @Test
    void elPromedioDelEscalonPesaIgualCadaPunto() {
        EscalonesCalibracion tabla = EscalonesCalibracion.desde(QualityPreset.MINIMO);
        CalibradorBenchmark c = new CalibradorBenchmark(tabla, 2, 0, 100);
        // Punto 0: 100 frames de 1 ms. Punto 1: 1 frame de 100 ms.
        CalibradorBenchmark.Evento e = CalibradorBenchmark.Evento.NADA;
        while (e == CalibradorBenchmark.Evento.NADA) {
            e = c.registrarFrame(1);
        }
        assertEquals(CalibradorBenchmark.Evento.CAMBIAR_PUNTO, e);
        c.registrarFrame(100);

        // (1 + 100) / 2 = 50.5 ms > umbral de 24 fps (37.5 ms): el primer escalón falla,
        // y como es el más liviano termina en el piso.
        assertNotNull(c.resultado());
        assertEquals(50.5, c.resultado().mediciones().get(0).msPromedio(), 1e-9);
        assertTrue(c.resultado().enPiso());
    }

    @Test
    void despuesDeTerminarSigueDevolviendoTerminado() {
        EscalonesCalibracion tabla = EscalonesCalibracion.desde(QualityPreset.MINIMO);
        CalibradorBenchmark c = new CalibradorBenchmark(tabla, 1, 0, 1);
        while (c.registrarFrame(1000) != CalibradorBenchmark.Evento.TERMINADO) {
        }
        assertEquals(CalibradorBenchmark.Evento.TERMINADO, c.registrarFrame(1));
    }
}
