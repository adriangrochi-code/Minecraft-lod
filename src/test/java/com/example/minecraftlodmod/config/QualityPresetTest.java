package com.example.minecraftlodmod.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class QualityPresetTest {

    @Test
    void recomiendaMinimoParaHardwareExtremadamenteLimitado() {
        assertEquals(QualityPreset.MINIMO, QualityPreset.recomendarPorHardware(2, 3000));
    }

    @Test
    void recomiendaBajoParaHardwareLimitado() {
        assertEquals(QualityPreset.BAJO, QualityPreset.recomendarPorHardware(2, 4000));
    }

    @Test
    void recomiendaMedioParaElHardwareDeReferencia3500u12gb() {
        // 4 núcleos, ~12GB — el caso concreto de referencia de este proyecto.
        // Nota: la misma heurística también recomendaría MEDIO para la A275
        // (4 núcleos, 16GB) aunque su rendimiento real sea menor — ver el
        // javadoc de recomendarPorHardware.
        assertEquals(QualityPreset.MEDIO, QualityPreset.recomendarPorHardware(4, 12000));
    }

    @Test
    void recomiendaUltraParaHardwarePotente() {
        assertEquals(QualityPreset.ULTRA, QualityPreset.recomendarPorHardware(8, 32000));
    }

    @Test
    void navegacionEntrePresetsFuncionaEnAmbosExtremos() {
        assertNull(QualityPreset.MINIMO.masLiviano());
        assertNull(QualityPreset.HORIZONTE.masPesado());
        assertEquals(QualityPreset.BAJO, QualityPreset.MINIMO.masPesado());
        assertEquals(QualityPreset.MEDIO, QualityPreset.BAJO.masPesado());
        assertEquals(QualityPreset.ALTO, QualityPreset.MEDIO.masPesado());
        assertEquals(QualityPreset.HORIZONTE, QualityPreset.ULTRA.masPesado());
        assertEquals(QualityPreset.ULTRA, QualityPreset.HORIZONTE.masLiviano());
    }

    @Test
    void laHeuristicaAutomaticaNuncaRecomiendaHorizonte() {
        // Aunque se le pasen valores extremos de CPU/RAM, la heurística no
        // conoce la GPU, así que el techo que puede sugerir es ULTRA.
        assertEquals(QualityPreset.ULTRA, QualityPreset.recomendarPorHardware(64, 128_000));
    }

    @Test
    void minimoTieneElObjetivoDeFpsMasConservador() {
        assertEquals(24, QualityPreset.MINIMO.objetivoFpsSugerido);
        assertEquals(30, QualityPreset.BAJO.objetivoFpsSugerido);
    }
}
