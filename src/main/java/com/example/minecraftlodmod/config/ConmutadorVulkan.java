package com.example.minecraftlodmod.config;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLConfig;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prender y apagar el VulkanMod integrado (paquete {@code vulkanmod}) desde el
 * menú del LOD.
 *
 * VulkanMod reemplaza el renderer entero al arrancar el juego: no se puede
 * cambiar en caliente. El pedido se guarda en
 * {@code config/minecraftlodmod-vulkan.properties} y se lee al arrancar, antes
 * de aplicar los mixins ({@code vulkanmod.mixin.MixinPlugin}): apagado, sus
 * mixins no se aplican y el juego es el OpenGL de siempre.
 *
 * VulkanMod además necesita la ventana temprana de NeoForge apagada
 * ({@code earlyWindowControl} en {@code config/fml.toml}; esa ventana ya abre
 * un contexto OpenGL). Prenderlo la apaga y apagarlo la deja como estaba.
 *
 * Sin clases de Minecraft: el plugin de mixins la usa antes de que carguen.
 */
public final class ConmutadorVulkan {

    private static final Logger LOG = LogUtils.getLogger();
    static final String ARCHIVO = "minecraftlodmod-vulkan.properties";
    static final String CLAVE_ACTIVO = "activo";
    /** Cómo estaba earlyWindowControl antes de que lo apagáramos, para devolverlo. */
    static final String CLAVE_VENTANA_PREVIA = "ventanaTempranaPrevia";
    private static final Pattern VENTANA_TEMPRANA =
            Pattern.compile("(?m)^(\\s*earlyWindowControl\\s*=\\s*)(true|false)");

    public enum Estado {
        /** Vulkan en uso y sigue la próxima vez. */
        ACTIVO,
        /** Vulkan en uso, pero el próximo arranque es con OpenGL. */
        SE_APAGA,
        /** OpenGL, y sigue así. */
        APAGADO,
        /** OpenGL ahora; el próximo arranque es con Vulkan. */
        SE_PRENDE,
        /** Sistema sin los nativos incluidos (solo Windows y Linux). */
        NO_DISPONIBLE
    }

    private static Boolean activoEnEstaSesion;

    private ConmutadorVulkan() {
    }

    /**
     * Si esta sesión arranca con Vulkan. Se decide una sola vez (lo pregunta
     * el plugin de mixins antes de aplicar el primero) y no cambia hasta
     * reiniciar el juego.
     */
    public static synchronized boolean activoEnEstaSesion() {
        if (activoEnEstaSesion == null) {
            boolean pedido = pedido();
            boolean ventana = FMLConfig.getBoolConfigValue(FMLConfig.ConfigValue.EARLY_WINDOW_CONTROL);
            activoEnEstaSesion = decidir(pedido, ventana, sistemaSoportado());
            if (pedido && !activoEnEstaSesion && sistemaSoportado()) {
                // Alguien volvió a prender la ventana temprana: se apaga para el próximo arranque.
                LOG.warn("LOD: Vulkan pedido pero la ventana temprana de NeoForge está prendida; "
                        + "se apaga y Vulkan arranca la próxima vez");
                cambiarVentanaTemprana(false);
            }
            LOG.info("LOD: {} (VulkanMod integrado)", activoEnEstaSesion ? "Vulkan activo" : "OpenGL");
        }
        return activoEnEstaSesion;
    }

    /** Arranca con Vulkan si se pidió, el sistema tiene los nativos y la ventana temprana está apagada. */
    static boolean decidir(boolean pedido, boolean ventanaTemprana, boolean sistemaSoportado) {
        return pedido && !ventanaTemprana && sistemaSoportado;
    }

    public static synchronized Estado estado() {
        if (!sistemaSoportado()) {
            return Estado.NO_DISPONIBLE;
        }
        boolean ahora = activoEnEstaSesion();
        boolean proximo = pedido();
        if (ahora) {
            return proximo ? Estado.ACTIVO : Estado.SE_APAGA;
        }
        return proximo ? Estado.SE_PRENDE : Estado.APAGADO;
    }

    /** Pasa al estado contrario; el efecto real es en el próximo arranque. */
    public static synchronized void alternar() {
        if (!sistemaSoportado()) {
            return;
        }
        try {
            Properties p = leer(archivo());
            boolean prender = !Boolean.parseBoolean(p.getProperty(CLAVE_ACTIVO, "false"));
            if (prender) {
                if (!p.containsKey(CLAVE_VENTANA_PREVIA)) {
                    p.setProperty(CLAVE_VENTANA_PREVIA, Boolean.toString(
                            FMLConfig.getBoolConfigValue(FMLConfig.ConfigValue.EARLY_WINDOW_CONTROL)));
                }
                cambiarVentanaTemprana(false);
            } else {
                cambiarVentanaTemprana(Boolean.parseBoolean(p.getProperty(CLAVE_VENTANA_PREVIA, "true")));
                p.remove(CLAVE_VENTANA_PREVIA);
            }
            p.setProperty(CLAVE_ACTIVO, Boolean.toString(prender));
            escribir(archivo(), p);
            LOG.info("LOD: Vulkan {} desde el próximo arranque", prender ? "activado" : "desactivado");
        } catch (IOException | RuntimeException e) {
            LOG.error("LOD: no se pudo cambiar el estado de Vulkan", e);
        }
    }

    private static boolean pedido() {
        try {
            return Boolean.parseBoolean(leer(archivo()).getProperty(CLAVE_ACTIVO, "false"));
        } catch (IOException e) {
            LOG.warn("LOD: no se pudo leer {}: {}", ARCHIVO, e.getMessage());
            return false;
        }
    }

    /** Los nativos de VMA y shaderc que trae el mod son de Windows y Linux (x64). */
    static boolean sistemaSoportado() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win") || os.contains("linux");
    }

    private static Path archivo() {
        return FMLPaths.CONFIGDIR.get().resolve(ARCHIVO);
    }

    static Properties leer(Path archivo) throws IOException {
        Properties p = new Properties();
        if (Files.isRegularFile(archivo)) {
            try (Reader r = Files.newBufferedReader(archivo, StandardCharsets.UTF_8)) {
                p.load(r);
            }
        }
        return p;
    }

    static void escribir(Path archivo, Properties p) throws IOException {
        Files.createDirectories(archivo.getParent());
        try (Writer w = Files.newBufferedWriter(archivo, StandardCharsets.UTF_8)) {
            p.store(w, "Minecraft LOD: VulkanMod integrado (se lee al arrancar el juego)");
        }
    }

    private static void cambiarVentanaTemprana(boolean valor) {
        try {
            FMLConfig.updateConfig(FMLConfig.ConfigValue.EARLY_WINDOW_CONTROL, valor);
        } catch (RuntimeException e) {
            LOG.debug("LOD: FMLConfig.updateConfig falló; se edita fml.toml a mano", e);
        }
        Path fml = FMLPaths.CONFIGDIR.get().resolve("fml.toml");
        try {
            if (Files.isRegularFile(fml)) {
                String antes = Files.readString(fml, StandardCharsets.UTF_8);
                String despues = conVentanaTemprana(antes, valor);
                if (!despues.equals(antes)) {
                    Files.writeString(fml, despues, StandardCharsets.UTF_8);
                }
            }
        } catch (IOException e) {
            LOG.error("LOD: no se pudo editar {}", fml, e);
        }
    }

    /** El fml.toml con earlyWindowControl en {@code valor} (lo agrega si falta). */
    static String conVentanaTemprana(String toml, boolean valor) {
        Matcher m = VENTANA_TEMPRANA.matcher(toml);
        if (m.find()) {
            return m.replaceFirst(Matcher.quoteReplacement(m.group(1) + valor));
        }
        String separador = toml.isEmpty() || toml.endsWith("\n") ? "" : "\n";
        return toml + separador + "earlyWindowControl = " + valor + "\n";
    }
}
