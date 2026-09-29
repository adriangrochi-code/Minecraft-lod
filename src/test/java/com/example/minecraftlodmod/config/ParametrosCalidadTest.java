package com.example.minecraftlodmod.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ParametrosCalidadTest {

    @Test
    void todosLosPresetsEntranEnLosRangosValidos() {
        for (QualityPreset preset : QualityPreset.values()) {
            assertDoesNotThrow(() -> ParametrosCalidad.de(preset), preset.name());
        }
    }

    @Test
    void cadaSeleccionFijaResuelveASuPreset() {
        for (QualityPreset preset : QualityPreset.values()) {
            ParametrosCalidad.Seleccion seleccion = ParametrosCalidad.Seleccion.valueOf(preset.name());
            assertEquals(ParametrosCalidad.de(preset), ParametrosCalidad.resolver(seleccion, null, 4, 8192));
        }
    }

    @Test
    void automaticoUsaLaRecomendacionPorHardware() {
        assertEquals(ParametrosCalidad.de(QualityPreset.recomendarPorHardware(4, 12288)),
                ParametrosCalidad.resolver(ParametrosCalidad.Seleccion.AUTOMATICO, null, 4, 12288));
    }

    @Test
    void personalizadoRecortaValoresFueraDeRango() {
        ParametrosCalidad.Crudos editadoAMano = new ParametrosCalidad.Crudos(99999, Double.NaN, 0, 1, 0, 1000);
        ParametrosCalidad p = ParametrosCalidad.resolver(ParametrosCalidad.Seleccion.PERSONALIZADO, editadoAMano, 4, 8192);

        assertEquals(ParametrosCalidad.RADIO_MAX, p.radioLodChunks());
        assertEquals(ParametrosCalidad.UMBRAL_MIN, p.umbralPx());
        assertEquals(ParametrosCalidad.HILOS_MIN, p.hilosGeneracion());
        assertEquals(ParametrosCalidad.CACHE_MIN_MB, p.cacheRamMb());
        assertEquals(ParametrosCalidad.COLAPSO_MIN, p.colapsoDesdeNivel());
        assertEquals(ParametrosCalidad.FPS_MAX, p.fpsObjetivo());
    }

    @Test
    void personalizadoValidoSeRespeta() {
        ParametrosCalidad.Crudos crudos = new ParametrosCalidad.Crudos(200, 2.0, 3, 700, 2, 45);
        ParametrosCalidad p = ParametrosCalidad.resolver(ParametrosCalidad.Seleccion.PERSONALIZADO, crudos, 4, 8192);
        assertEquals(new ParametrosCalidad(200, 2.0, 3, 700, 2, 45), p);
    }

    @Test
    void elConstructorRechazaValoresInvalidos() {
        assertThrows(IllegalArgumentException.class, () -> new ParametrosCalidad(100, 1.0, 1, 100, 0, 30),
                "El colapso nunca puede aplicar desde el nivel 0");
        assertThrows(IllegalArgumentException.class, () -> new ParametrosCalidad(100, Double.NaN, 1, 100, 1, 30));
    }

    @Test
    void frameTimeObjetivoSaleDelFps() {
        assertEquals(25.0, ParametrosCalidad.de(QualityPreset.MEDIO).frameTimeObjetivoMs(), 1e-9);
    }

    @Test
    void lasSeleccionesSinPresetDevuelvenNull() {
        assertNull(ParametrosCalidad.Seleccion.AUTOMATICO.preset());
        assertNull(ParametrosCalidad.Seleccion.PERSONALIZADO.preset());
    }
}
