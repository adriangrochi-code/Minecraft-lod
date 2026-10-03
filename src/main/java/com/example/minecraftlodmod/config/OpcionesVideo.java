package com.example.minecraftlodmod.config;

import com.example.minecraftlodmod.config.OpcionesLod.Accion;
import com.example.minecraftlodmod.config.OpcionesLod.Ciclo;
import com.example.minecraftlodmod.config.OpcionesLod.Deslizador;
import com.example.minecraftlodmod.config.OpcionesLod.Impacto;
import com.example.minecraftlodmod.config.OpcionesLod.Interruptor;
import com.example.minecraftlodmod.config.OpcionesLod.Opcion;
import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.VideoMode;
import com.mojang.blaze3d.platform.Window;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Las opciones de Opciones > Video de Minecraft como filas de
 * {@link PantallaLod}, que reemplaza a esa pantalla (como hace Sodium). Cada
 * fila se arma a partir del {@link OptionInstance} de vanilla: casilla si es
 * sí/no, deslizador si vanilla usa uno, ciclo si no. Al aplicar se usa
 * {@code OptionInstance.set} (dispara lo mismo que en la pantalla vanilla) y
 * se repite lo que vanilla hace al cerrar la suya: guardar, recargar
 * texturas si cambian los mipmaps y cambiar la resolución de pantalla
 * completa. Solo cliente.
 */
final class OpcionesVideo {

    private static final Logger LOG = LogUtils.getLogger();
    private static final String CLAVE = "minecraftlodmod.video.";
    /** Pasos de los deslizadores vanilla que no son enteros (brillo, efectos). */
    private static final int PASOS = 100;

    private final List<Opcion> propias = new ArrayList<>();
    final List<List<Opcion>> pantalla;
    final List<List<Opcion>> graficos;

    OpcionesVideo(Runnable abrirOriginal) {
        Minecraft mc = Minecraft.getInstance();
        Options o = mc.options;

        List<Opcion> ventana = new ArrayList<>();
        if (ConmutadorVulkan.activoEnEstaSesion()) {
            // Con Vulkan, sus opciones propias arriba de todo: las dos pantallas a mano.
            ventana.add(new Accion(Component.translatable(CLAVE + "vulkanmod"),
                    Component.translatable(CLAVE + "vulkanmod.tooltip"), Impacto.NINGUNO,
                    () -> Component.translatable(CLAVE + "abrir"), abrirOriginal));
        }
        ventana.add(de(o.fullscreen(), "fullscreen", Impacto.NINGUNO));
        Opcion resolucion = resolucionPantallaCompleta(mc.getWindow());
        if (resolucion != null) {
            ventana.add(resolucion);
        }
        ventana.add(de(o.enableVsync(), "vsync", Impacto.VARIABLE));
        ventana.add(de(o.framerateLimit(), "framerateLimit", Impacto.VARIABLE));
        pantalla = List.of(
                ventana,
                List.of(de(o.guiScale(), "guiScale", Impacto.NINGUNO),
                        de(o.gamma(), "gamma", Impacto.NINGUNO),
                        de(o.menuBackgroundBlurriness(), "menuBackgroundBlurriness", Impacto.BAJO)),
                List.of(de(o.bobView(), "bobView", Impacto.NINGUNO),
                        de(o.attackIndicator(), "attackIndicator", Impacto.NINGUNO),
                        de(o.showAutosaveIndicator(), "showAutosaveIndicator", Impacto.NINGUNO),
                        de(o.fovEffectScale(), "fovEffectScale", Impacto.NINGUNO),
                        de(o.screenEffectScale(), "screenEffectScale", Impacto.NINGUNO)));

        List<Opcion> extras = new ArrayList<>();
        if (claseShaders() != null) {
            extras.add(new Accion(Component.translatable(CLAVE + "shaders"),
                    Component.translatable(CLAVE + "shaders.tooltip"), Impacto.VARIABLE,
                    () -> Component.translatable(CLAVE + "abrir"), OpcionesVideo::abrirShaders));
        }
        // Con Vulkan activo, la pantalla de video "original" es la de VulkanMod (sus opciones propias).
        String original = ConmutadorVulkan.activoEnEstaSesion() ? "vulkanmod" : "original";
        extras.add(new Accion(Component.translatable(CLAVE + original),
                Component.translatable(CLAVE + original + ".tooltip"), Impacto.NINGUNO,
                () -> Component.translatable(CLAVE + "abrir"), abrirOriginal));
        graficos = List.of(
                List.of(de(o.renderDistance(), "renderDistance", Impacto.ALTO),
                        de(o.simulationDistance(), "simulationDistance", Impacto.ALTO),
                        de(o.prioritizeChunkUpdates(), "prioritizeChunkUpdates", Impacto.MEDIO)),
                List.of(de(o.graphicsMode(), "graphicsMode", Impacto.ALTO),
                        de(o.ambientOcclusion(), "ambientOcclusion", Impacto.BAJO),
                        de(o.cloudStatus(), "cloudStatus", Impacto.BAJO),
                        de(o.particles(), "particles", Impacto.MEDIO),
                        de(o.biomeBlendRadius(), "biomeBlendRadius", Impacto.MEDIO),
                        de(o.entityShadows(), "entityShadows", Impacto.BAJO),
                        de(o.entityDistanceScaling(), "entityDistanceScaling", Impacto.MEDIO),
                        de(o.mipmapLevels(), "mipmapLevels", Impacto.BAJO)),
                List.of(de(o.glintSpeed(), "glintSpeed", Impacto.NINGUNO),
                        de(o.glintStrength(), "glintStrength", Impacto.NINGUNO)),
                extras);
    }

    /** true si hay alguna opción de video cambiada sin aplicar. */
    boolean modificada() {
        return propias.stream().anyMatch(Opcion::modificada);
    }

    /** Aplica las opciones de video pendientes y hace lo que vanilla hace al cerrar su pantalla. */
    void aplicar() {
        if (!modificada()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Options o = mc.options;
        Window ventana = mc.getWindow();
        int mipmaps = o.mipmapLevels().get();
        int escalaGui = o.guiScale().get();
        Optional<VideoMode> modo = ventana.getPreferredFullscreenVideoMode();
        propias.stream().filter(Opcion::modificada).forEach(Opcion::aplicar);
        o.save();
        if (o.mipmapLevels().get() != mipmaps) {
            mc.updateMaxMipLevel(o.mipmapLevels().get());
            mc.delayTextureReload();
        }
        if (!ventana.getPreferredFullscreenVideoMode().equals(modo)) {
            ventana.changeFullscreenVideoMode();
        }
        if (o.guiScale().get() != escalaGui) {
            mc.resizeDisplay();
        }
    }

    // ------------------------------------------------------------------ adaptadores

    @SuppressWarnings("unchecked")
    private <T> Opcion de(OptionInstance<T> opcion, String clave, Impacto impacto) {
        Component descripcion = Component.translatable(CLAVE + clave);
        Opcion fila;
        if (opcion.get() instanceof Boolean) {
            OptionInstance<Boolean> b = (OptionInstance<Boolean>) opcion;
            fila = new Interruptor(opcion.caption, descripcion, impacto, b::get, b::set);
        } else if (opcion.values() instanceof OptionInstance.IntRangeBase rango) {
            OptionInstance<Integer> i = (OptionInstance<Integer>) opcion;
            fila = new Deslizador(opcion.caption, descripcion, impacto, rango.minInclusive(), rango.maxInclusive(), 1,
                    i::get, i::set, v -> valor(i, v));
        } else if (opcion.values() instanceof OptionInstance.SliderableValueSet<T> s) {
            // Posición 0..PASOS de la barra; se redondea a lo que vanilla admite (FPS de a 10, etc.).
            fila = new Deslizador(opcion.caption, descripcion, impacto, 0, PASOS, 1,
                    () -> (int) Math.round(s.toSliderValue(opcion.get()) * PASOS),
                    v -> opcion.set(s.fromSliderValue(v / (double) PASOS)),
                    v -> valor(opcion, s.fromSliderValue(v / (double) PASOS)))
                    .conRedondeo(v -> (int) Math.round(s.toSliderValue(s.fromSliderValue(v / (double) PASOS)) * PASOS));
        } else if (opcion.values() instanceof OptionInstance.CycleableValueSet<T> c) {
            fila = new Ciclo<>(opcion.caption, descripcion, impacto, () -> c.valueListSupplier().getSelectedList(),
                    opcion::get, v -> c.valueSetter().set(opcion, v), v -> valor(opcion, v));
        } else {
            throw new IllegalArgumentException("Opción de video sin control: " + clave);
        }
        propias.add(fila);
        return fila;
    }

    /** Texto del valor como lo muestra vanilla, sin el "Nombre: " de adelante. */
    private static <T> Component valor(OptionInstance<T> opcion, T v) {
        Component completo = opcion.toString.apply(v);
        String prefijo = opcion.caption.getString() + ": ";
        String texto = completo.getString();
        return texto.startsWith(prefijo) ? Component.literal(texto.substring(prefijo.length())) : completo;
    }

    /** Resolución de pantalla completa (vanilla la arma aparte, según el monitor). */
    private Opcion resolucionPantallaCompleta(Window ventana) {
        Monitor monitor = ventana.findBestMonitor();
        if (monitor == null || monitor.getModeCount() == 0) {
            return null;
        }
        Deslizador fila = new Deslizador(Component.translatable("options.fullscreen.resolution"),
                Component.translatable(CLAVE + "resolucion"), Impacto.ALTO, -1, monitor.getModeCount() - 1, 1,
                () -> ventana.getPreferredFullscreenVideoMode().map(monitor::getVideoModeIndex).orElse(-1),
                v -> ventana.setPreferredFullscreenVideoMode(v < 0 ? Optional.empty() : Optional.of(monitor.getMode(v))),
                v -> v < 0 ? Component.translatable("options.fullscreen.current")
                        : Component.literal(monitor.getMode(v).toString()));
        propias.add(fila);
        return fila;
    }

    // ------------------------------------------------------------------ shaders (Iris / Oculus)

    private static Class<?> claseShaders() {
        if (!ModList.get().isLoaded("iris") && !ModList.get().isLoaded("oculus")) {
            return null;
        }
        for (String nombre : new String[]{"net.irisshaders.iris.gui.screen.ShaderPackScreen",
                "net.coderbot.iris.gui.screen.ShaderPackScreen"}) {
            try {
                return Class.forName(nombre);
            } catch (ClassNotFoundException e) {
                // siguiente
            }
        }
        return null;
    }

    private static void abrirShaders() {
        Minecraft mc = Minecraft.getInstance();
        try {
            Object pantalla = claseShaders().getConstructor(Screen.class).newInstance(mc.screen);
            mc.setScreen((Screen) pantalla);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.warn("No se pudo abrir la pantalla de shaders", e);
        }
    }
}
