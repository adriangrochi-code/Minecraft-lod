package com.example.minecraftlodmod.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConmutadorVulkanTest {

    @Test
    void soloArrancaConVulkanSiSePidioYLaVentanaTempranaEstaApagada() {
        assertTrue(ConmutadorVulkan.decidir(true, false, true));
        assertFalse(ConmutadorVulkan.decidir(false, false, true));
        assertFalse(ConmutadorVulkan.decidir(true, true, true), "la ventana temprana ya abrió OpenGL");
        assertFalse(ConmutadorVulkan.decidir(true, false, false), "sin nativos para este sistema");
    }

    @Test
    void cambiaEarlyWindowControlSinTocarElResto() {
        String toml = "earlyWindowHeight = 480\nearlyWindowControl = true\nversionCheck = true\n";
        assertEquals("earlyWindowHeight = 480\nearlyWindowControl = false\nversionCheck = true\n",
                ConmutadorVulkan.conVentanaTemprana(toml, false));
        assertEquals(toml, ConmutadorVulkan.conVentanaTemprana(toml, true));
    }

    @Test
    void agregaEarlyWindowControlSiFalta() {
        assertEquals("versionCheck = true\nearlyWindowControl = false\n",
                ConmutadorVulkan.conVentanaTemprana("versionCheck = true", false));
    }

    @Test
    void elPedidoSeGuardaYSeLee(@TempDir Path dir) throws Exception {
        Path archivo = dir.resolve("sub").resolve(ConmutadorVulkan.ARCHIVO);
        assertTrue(ConmutadorVulkan.leer(archivo).isEmpty(), "sin archivo: nada pedido");
        Properties p = new Properties();
        p.setProperty(ConmutadorVulkan.CLAVE_ACTIVO, "true");
        ConmutadorVulkan.escribir(archivo, p);
        assertEquals("true", ConmutadorVulkan.leer(archivo).getProperty(ConmutadorVulkan.CLAVE_ACTIVO));
    }
}
