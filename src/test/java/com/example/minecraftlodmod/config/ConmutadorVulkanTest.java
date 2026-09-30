package com.example.minecraftlodmod.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class ConmutadorVulkanTest {

    @Test
    void desactivarAgregaDisabledYActivarLoSaca() {
        Path jar = Path.of("mods", "vulkanmod-0.5.5-dev+3.1.jar");
        Path apagado = ConmutadorVulkan.conEstado(jar, false);
        assertEquals("vulkanmod-0.5.5-dev+3.1.jar.disabled", apagado.getFileName().toString());
        assertEquals(jar, ConmutadorVulkan.conEstado(apagado, true));
        assertEquals(jar, ConmutadorVulkan.conEstado(jar, true), "Ya activo: queda igual");
        assertEquals(apagado, ConmutadorVulkan.conEstado(apagado, false), "Ya apagado: queda igual");
    }

    @Test
    void encuentraElJarDesactivadoSoloDeVulkanMod(@TempDir Path mods) throws Exception {
        Files.createFile(mods.resolve("sodium.jar.disabled"));
        Files.createFile(mods.resolve("vulkanmod-0.5.5.jar"));
        assertTrue(ConmutadorVulkan.buscarDesactivado(mods).isEmpty(), "Uno activo no cuenta como desactivado");
        Files.createFile(mods.resolve("VulkanMod-0.5.5.jar.disabled"));
        assertEquals("VulkanMod-0.5.5.jar.disabled",
                ConmutadorVulkan.buscarDesactivado(mods).orElseThrow().getFileName().toString());
    }

    @Test
    void enWindowsLasRutasConComillaQuedanEscapadas() {
        List<String> comando = ConmutadorVulkan.comandoRenombrar(1234, Path.of("C:/mods/it's.jar"),
                Path.of("C:/mods/it's.jar.disabled"), true);
        assertEquals("powershell", comando.get(0));
        String script = comando.get(comando.size() - 1);
        assertTrue(script.startsWith("Wait-Process -Id 1234 "), script);
        assertTrue(script.contains("'C:/mods/it''s.jar'".replace('/', java.io.File.separatorChar)), script);
    }

    @Test
    void renombraRecienCuandoTerminaElProceso(@TempDir Path mods) throws Exception {
        assumeFalse(System.getProperty("os.name", "").toLowerCase().contains("win"));
        Path jar = Files.createFile(mods.resolve("vulkanmod.jar"));
        Path apagado = ConmutadorVulkan.conEstado(jar, false);
        Process juego = new ProcessBuilder("sleep", "2").start();

        Process renombrador = new ProcessBuilder(
                ConmutadorVulkan.comandoRenombrar(juego.pid(), jar, apagado, false)).start();
        Thread.sleep(500);
        assertTrue(Files.exists(jar), "Con el juego abierto no se toca el jar");

        juego.waitFor(10, TimeUnit.SECONDS);
        assertTrue(renombrador.waitFor(10, TimeUnit.SECONDS));
        assertFalse(Files.exists(jar));
        assertTrue(Files.exists(apagado));
    }
}
