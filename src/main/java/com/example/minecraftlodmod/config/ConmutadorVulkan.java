package com.example.minecraftlodmod.config;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforgespi.language.IModFileInfo;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Prender y apagar VulkanMod desde el menú del LOD.
 *
 * VulkanMod reemplaza el renderer entero al arrancar el juego: no se puede
 * cambiar en caliente. Esto hace lo mismo que el botón de desactivar de
 * Modrinth (renombrar el jar a {@code .jar.disabled} y viceversa) y el cambio
 * vale desde el próximo arranque. Un jar cargado no se puede renombrar en
 * Windows mientras el juego corre, así que para apagarlo se deja un proceso
 * aparte que espera a que el juego cierre y recién ahí lo renombra.
 */
public final class ConmutadorVulkan {

    private static final Logger LOG = LogUtils.getLogger();
    static final String MOD_ID = "vulkanmod";
    static final String DESACTIVADO = ".disabled";

    public enum Estado {
        /** Cargado y sigue la próxima vez. */
        ACTIVO,
        /** Cargado, pero se apaga al cerrar el juego. */
        SE_APAGA,
        /** No cargado (jar .disabled en mods). */
        APAGADO,
        /** No cargado, ya renombrado: se carga en el próximo arranque. */
        SE_PRENDE,
        /** No hay VulkanMod en la carpeta de mods. */
        NO_INSTALADO
    }

    private static Process renombradorPendiente;
    /** Jar ya reactivado en esta sesión (no cargado todavía). */
    private static Path reactivado;

    private ConmutadorVulkan() {
    }

    public static synchronized Estado estado() {
        if (cargado()) {
            return renombradorPendiente != null && renombradorPendiente.isAlive() ? Estado.SE_APAGA : Estado.ACTIVO;
        }
        if (reactivado != null && Files.exists(reactivado)) {
            return Estado.SE_PRENDE;
        }
        return buscarDesactivado(FMLPaths.MODSDIR.get()).isPresent() ? Estado.APAGADO : Estado.NO_INSTALADO;
    }

    /** Pasa al estado contrario; el efecto real es en el próximo arranque. */
    public static synchronized void alternar() {
        try {
            switch (estado()) {
                case ACTIVO -> programarApagado();
                case SE_APAGA -> {
                    renombradorPendiente.destroy();
                    renombradorPendiente = null;
                    LOG.info("LOD: apagado de VulkanMod cancelado");
                }
                case APAGADO -> {
                    Path jar = buscarDesactivado(FMLPaths.MODSDIR.get()).orElseThrow();
                    reactivado = Files.move(jar, conEstado(jar, true));
                    LOG.info("LOD: VulkanMod reactivado ({}); se carga al reiniciar", reactivado.getFileName());
                }
                case SE_PRENDE -> {
                    Files.move(reactivado, conEstado(reactivado, false));
                    LOG.info("LOD: reactivación de VulkanMod cancelada");
                    reactivado = null;
                }
                case NO_INSTALADO -> {
                }
            }
        } catch (IOException | RuntimeException e) {
            LOG.error("LOD: no se pudo cambiar el estado de VulkanMod", e);
        }
    }

    private static void programarApagado() throws IOException {
        Path jar = jarCargado().orElseThrow(() -> new IOException("no se encontró el jar de VulkanMod"));
        List<String> comando = comandoRenombrar(ProcessHandle.current().pid(), jar, conEstado(jar, false), esWindows());
        renombradorPendiente = new ProcessBuilder(comando).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        LOG.info("LOD: VulkanMod se desactiva al cerrar el juego ({})", jar.getFileName());
    }

    private static boolean cargado() {
        return ModList.get().isLoaded(MOD_ID);
    }

    private static Optional<Path> jarCargado() {
        IModFileInfo info = ModList.get().getModFileById(MOD_ID);
        return info == null ? Optional.empty() : Optional.of(info.getFile().getFilePath());
    }

    private static boolean esWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** {@code x.jar} ↔ {@code x.jar.disabled} (la convención de Modrinth y otros launchers). */
    static Path conEstado(Path jar, boolean activo) {
        String nombre = jar.getFileName().toString();
        boolean desactivado = nombre.endsWith(DESACTIVADO);
        if (activo == !desactivado) {
            return jar;
        }
        String nuevo = activo ? nombre.substring(0, nombre.length() - DESACTIVADO.length()) : nombre + DESACTIVADO;
        return jar.resolveSibling(nuevo);
    }

    /** Un jar de VulkanMod desactivado en la carpeta de mods (por nombre de archivo). */
    static Optional<Path> buscarDesactivado(Path mods) {
        if (!Files.isDirectory(mods)) {
            return Optional.empty();
        }
        try (Stream<Path> archivos = Files.list(mods)) {
            return archivos.filter(p -> {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                return n.contains(MOD_ID) && n.endsWith(".jar" + DESACTIVADO);
            }).findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Proceso que espera a que termine {@code pid} y después mueve {@code desde} a {@code hasta}. */
    static List<String> comandoRenombrar(long pid, Path desde, Path hasta, boolean windows) {
        if (windows) {
            String script = "Wait-Process -Id " + pid + " -ErrorAction SilentlyContinue; "
                    + "Move-Item -LiteralPath " + comillasPowerShell(desde) + " -Destination "
                    + comillasPowerShell(hasta) + " -Force";
            return List.of("powershell", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", script);
        }
        return List.of("sh", "-c", "while kill -0 " + pid + " 2>/dev/null; do sleep 1; done; mv -f \"$0\" \"$1\"",
                desde.toString(), hasta.toString());
    }

    private static String comillasPowerShell(Path p) {
        return "'" + p.toString().replace("'", "''") + "'";
    }
}
