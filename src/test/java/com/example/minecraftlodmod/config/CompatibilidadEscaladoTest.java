package com.example.minecraftlodmod.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompatibilidadEscaladoTest {

    private static final String GTX_1060 = "NVIDIA Corporation NVIDIA GeForce GTX 1060 6GB/PCIe/SSE2";
    private static final String RTX_3060 = "NVIDIA Corporation NVIDIA GeForce RTX 3060/PCIe/SSE2";
    private static final String VEGA_8 = "ATI Technologies Inc. AMD Radeon(TM) Vega 8 Graphics";
    private static final String R7_A275 = "ATI Technologies Inc. AMD Radeon R7 Graphics";
    private static final String RX_6600 = "ATI Technologies Inc. AMD Radeon RX 6600";
    private static final String ARC = "Intel Intel(R) Arc(TM) A770 Graphics";
    private static final String UHD_620 = "Intel Intel(R) UHD Graphics 620";

    @Test
    void laGtx1060TieneXessPeroNoDlss() {
        assertEquals(List.of(ModoEscalado.APAGADO, ModoEscalado.FSR1, ModoEscalado.TEMPORAL, ModoEscalado.XESS),
                CompatibilidadEscalado.disponibles(GTX_1060, true, false));
    }

    @Test
    void unaRtxTieneTodo() {
        assertEquals(List.of(ModoEscalado.values()), CompatibilidadEscalado.disponibles(RTX_3060, true, false));
    }

    @Test
    void lasIntegradasViejasSoloTienenLosPropios() {
        for (String gpu : List.of(VEGA_8, R7_A275, UHD_620)) {
            assertFalse(CompatibilidadEscalado.soportaXess(gpu, true), gpu);
            assertFalse(CompatibilidadEscalado.soportaDlss(gpu, true), gpu);
        }
        assertTrue(CompatibilidadEscalado.soportaXess(RX_6600, true));
        assertTrue(CompatibilidadEscalado.soportaXess(ARC, true));
    }

    @Test
    void fueraDeWindowsNoHayXessNiDlss() {
        assertEquals(List.of(ModoEscalado.APAGADO, ModoEscalado.FSR1, ModoEscalado.TEMPORAL),
                CompatibilidadEscalado.disponibles(RTX_3060, false, false));
    }

    @Test
    void conVulkanModSoloApagado() {
        assertEquals(List.of(ModoEscalado.APAGADO), CompatibilidadEscalado.disponibles(RTX_3060, true, true));
    }

    @Test
    void unModoNoDisponibleCaeAlTemporal() {
        List<ModoEscalado> modos = CompatibilidadEscalado.disponibles(GTX_1060, true, false);
        assertEquals(ModoEscalado.TEMPORAL, CompatibilidadEscalado.efectivo(ModoEscalado.DLSS, modos));
        assertEquals(ModoEscalado.XESS, CompatibilidadEscalado.efectivo(ModoEscalado.XESS, modos));
        assertEquals(ModoEscalado.APAGADO, CompatibilidadEscalado.efectivo(ModoEscalado.DLSS, List.of(ModoEscalado.APAGADO)));
    }
}
