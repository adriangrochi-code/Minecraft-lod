package com.example.minecraftlodmod.benchmark;

import com.example.minecraftlodmod.config.ParametrosCalidad;
import com.example.minecraftlodmod.config.QualityPreset;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecomendacionesBenchmarkTest {

    private static final boolean[] CUENTAN = {true, false};

    private static CalibradorBenchmark.MetricasPunto punto(double ms, double gpuMs) {
        return new CalibradorBenchmark.MetricasPunto(100, ms, ms, ms, 0, true, gpuMs, -1, -1, -1, -1, -1, -1);
    }

    private static CalibradorBenchmark.Resultado resultado(int indice, boolean enPiso, boolean paso, double ms,
                                                           double gpuMs) {
        CalibradorBenchmark.MetricasPunto[] p = {punto(ms, gpuMs), punto(500, 0)}; // el vuelo no cuenta
        var m = new CalibradorBenchmark.Medicion(indice, new double[]{ms, 500}, ms, paso, p);
        return new CalibradorBenchmark.Resultado(ParametrosCalidad.de(QualityPreset.MINIMO), indice, enPiso, List.of(m));
    }

    @Test
    void enElPisoConLaGpuAlLimitePrendeElEscalado() {
        var r = RecomendacionesBenchmark.de(resultado(0, true, false, 60, 57), CUENTAN, 41.7, 10);
        assertTrue(r.escalado(), "Como la A275: la GPU es el límite y ni el más liviano alcanza");
        assertFalse(r.oclusionCostados());
    }

    @Test
    void enElPisoPeroLimitadoPorProcesadorNoEscala() {
        var r = RecomendacionesBenchmark.de(resultado(0, true, false, 60, 20), CUENTAN, 41.7, 10);
        assertFalse(r.escalado(), "Bajar la resolución no ayuda si espera al procesador");
    }

    @Test
    void conMuchoMargenEnElMasAltoSumaOclusionEnCostados() {
        var r = RecomendacionesBenchmark.de(resultado(10, false, true, 10, 6), CUENTAN, 22.2, 10);
        assertTrue(r.oclusionCostados(), "Como la 1060 con Horizonte de sobra");
        assertFalse(r.escalado());
    }

    @Test
    void sinMedicionDeGpuNoSeTocaNada() {
        var r = RecomendacionesBenchmark.de(resultado(10, false, true, 10, -1), CUENTAN, 22.2, 10);
        assertFalse(r.oclusionCostados(), "Sin saber cuánto trabaja la GPU, no se suma costo de GPU");
        assertFalse(r.escalado());
    }
}
