package com.example.minecraftlodmod.render;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MedidorGpuWindowsTest {

    @Test
    void elUsoEsElMotorMasOcupadoSumandoProcesos() {
        Map<String, Double> instancias = Map.of(
                "pid_100_luid_0x0_0x1_phys_0_eng_0_engtype_3D", 40.0,
                "pid_200_luid_0x0_0x1_phys_0_eng_0_engtype_3D", 25.0, // mismo motor, otro proceso: se suma
                "pid_100_luid_0x0_0x1_phys_0_eng_3_engtype_VideoDecode", 10.0,
                "pid_300_luid_0x0_0x2_phys_0_eng_0_engtype_3D", 50.0); // otra placa
        assertEquals(65.0, MedidorGpuWindows.usoGpu(instancias), 1e-9);
    }

    @Test
    void elUsoTieneTopeCien() {
        assertEquals(100.0, MedidorGpuWindows.usoGpu(Map.of(
                "pid_1_luid_0x0_0x1_phys_0_eng_0_engtype_3D", 70.0,
                "pid_2_luid_0x0_0x1_phys_0_eng_0_engtype_3D", 60.0)), 1e-9);
        assertEquals(0.0, MedidorGpuWindows.usoGpu(Map.of()), 1e-9);
    }

    @Test
    void laMemoriaDelRegistroSeLeeEnSusTresFormatos() {
        assertEquals(6L << 30, MedidorGpuWindows.bytes(6L << 30));
        assertEquals(0xC0000000L, MedidorGpuWindows.bytes((int) 0xC0000000));
        assertEquals(512L << 20, MedidorGpuWindows.bytes(new byte[]{0, 0, 0, 0x20}));
        assertEquals(-1, MedidorGpuWindows.bytes("otra cosa"));
    }
}
