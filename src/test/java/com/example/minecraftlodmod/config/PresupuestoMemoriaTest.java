package com.example.minecraftlodmod.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PresupuestoMemoriaTest {

    @Test
    void elCacheMasLaColaNoSuperanElPresupuestoDelPreset() {
        for (QualityPreset preset : QualityPreset.values()) {
            PresupuestoMemoria p = PresupuestoMemoria.para(preset);
            long total = (long) preset.cacheRamMb * 1024 * 1024;
            long cola = (long) p.maxTareasEnCola() * PresupuestoMemoria.BYTES_ESTIMADOS_POR_TAREA;

            assertTrue(p.bytesCacheRegiones() + cola <= total, "Se pasa del presupuesto en " + preset);
        }
    }

    @Test
    void presetsMasPesadosTienenMasCacheYMasCola() {
        PresupuestoMemoria minimo = PresupuestoMemoria.para(QualityPreset.MINIMO);
        PresupuestoMemoria ultra = PresupuestoMemoria.para(QualityPreset.ULTRA);

        assertTrue(ultra.bytesCacheRegiones() > minimo.bytesCacheRegiones());
        assertTrue(ultra.maxTareasEnCola() > minimo.maxTareasEnCola());
    }

    @Test
    void laColaTieneAlMenosDosTareasPorHilo() {
        PresupuestoMemoria p = PresupuestoMemoria.para(1, 8); // 1 MB alcanza para casi nada

        assertEquals(16, p.maxTareasEnCola());
    }

    @Test
    void laColaNuncaSuperaElTechoAbsoluto() {
        PresupuestoMemoria p = PresupuestoMemoria.para(1_000_000, 4);

        assertEquals(PresupuestoMemoria.MAX_TAREAS_ABSOLUTO, p.maxTareasEnCola());
    }

    @Test
    void rechazaValoresInvalidos() {
        assertThrows(IllegalArgumentException.class, () -> PresupuestoMemoria.para(0, 1));
        assertThrows(IllegalArgumentException.class, () -> PresupuestoMemoria.para(100, 0));
    }
}
