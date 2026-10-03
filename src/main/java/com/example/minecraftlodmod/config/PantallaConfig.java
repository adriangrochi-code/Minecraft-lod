package com.example.minecraftlodmod.config;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Registra la pantalla de opciones del mod ({@link PantallaLod}, estilo
 * Sodium): reemplaza a Opciones > Video (como Sodium), y abre en las pestañas
 * del LOD desde el botón "Config" de la lista de mods. Si otro mod ya
 * reemplaza esa pantalla (Sodium, Embeddium, VulkanMod) no se la pisa: queda
 * un botón "LOD" arriba a la derecha de la pantalla de video. Solo cliente:
 * llamar únicamente con {@code Dist.CLIENT}.
 */
public final class PantallaConfig {

    private PantallaConfig() {
    }

    /** Mods con su propia pantalla de video: con ellos no se reemplaza nada. */
    private static final String[] CON_PANTALLA_PROPIA = {"sodium", "embeddium", "rubidium", "vulkanmod"};

    /** La próxima apertura de la pantalla vanilla no se reemplaza ("Opciones de video originales"). */
    private static boolean originalUnaVez;

    public static void registrar(ModContainer contenedor) {
        contenedor.registerExtensionPoint(IConfigScreenFactory.class,
                (mod, anterior) -> new PantallaLod(anterior, PantallaLod.PAGINA_LOD));
        NeoForge.EVENT_BUS.addListener(PantallaConfig::alAbrirPantalla);
        NeoForge.EVENT_BUS.addListener(PantallaConfig::alIniciarPantalla);
        NeoForge.EVENT_BUS.addListener(PantallaConfig::alDibujarPantalla);
    }

    static void abrirOriginalUnaVez() {
        originalUnaVez = true;
    }

    private static void alAbrirPantalla(ScreenEvent.Opening evento) {
        if (!(evento.getNewScreen() instanceof VideoSettingsScreen)) {
            return;
        }
        if (originalUnaVez) {
            originalUnaVez = false;
            return;
        }
        if (reemplazaOtroMod()) {
            return;
        }
        evento.setNewScreen(new PantallaLod(evento.getCurrentScreen()));
    }

    private static boolean reemplazaOtroMod() {
        if (ConmutadorVulkan.activoEnEstaSesion()) {
            return true;
        }
        for (String mod : CON_PANTALLA_PROPIA) {
            if (ModList.get().isLoaded(mod)) {
                return true;
            }
        }
        return false;
    }

    /** Botón al lado del título de la pantalla de video vanilla, cuando se muestra. */
    /** Pantalla de carga del mundo: avance de la pregeneración al entrar (ver PregeneracionInicial). */
    private static void alDibujarPantalla(ScreenEvent.Render.Post evento) {
        if (!(evento.getScreen() instanceof net.minecraft.client.gui.screens.LevelLoadingScreen pantalla)) {
            return;
        }
        var estado = com.example.minecraftlodmod.generation.PregeneracionInicial.estado();
        if (estado == null) {
            return;
        }
        var texto = net.minecraft.network.chat.Component.translatable("minecraftlodmod.pregeneracionInicial.carga",
                estado.chunks(), estado.segundosRestantes());
        var fuente = net.minecraft.client.Minecraft.getInstance().font;
        evento.getGuiGraphics().drawCenteredString(fuente, texto, pantalla.width / 2, pantalla.height / 2 + 60, 0xFFFFFF);
    }

    private static void alIniciarPantalla(ScreenEvent.Init.Post evento) {
        if (!(evento.getScreen() instanceof VideoSettingsScreen video)) {
            return;
        }
        Button boton = Button.builder(Component.translatable("minecraftlodmod.pantalla.boton"),
                        b -> Minecraft.getInstance().setScreen(new PantallaLod(video, PantallaLod.PAGINA_LOD)))
                .bounds(video.width - 56, 6, 50, 20)
                .tooltip(Tooltip.create(Component.translatable("minecraftlodmod.pantalla.boton.tooltip")))
                .build();
        evento.addListener(boton);
    }
}
