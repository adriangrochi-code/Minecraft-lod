package com.example.minecraftlodmod.config;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Registra la pantalla de opciones del mod ({@link PantallaLod}, estilo
 * Sodium): el botón "Config" de la lista de mods y un botón "LOD" arriba a la
 * derecha de Opciones > Video. Solo cliente: llamar únicamente con
 * {@code Dist.CLIENT}.
 */
public final class PantallaConfig {

    private PantallaConfig() {
    }

    public static void registrar(ModContainer contenedor) {
        contenedor.registerExtensionPoint(IConfigScreenFactory.class, (mod, anterior) -> new PantallaLod(anterior));
        NeoForge.EVENT_BUS.addListener(PantallaConfig::alIniciarPantalla);
    }

    /** Botón al lado del título de Opciones > Video (con Sodium/VulkanMod esa pantalla es otra: no aparece). */
    private static void alIniciarPantalla(ScreenEvent.Init.Post evento) {
        if (!(evento.getScreen() instanceof VideoSettingsScreen video)) {
            return;
        }
        Button boton = Button.builder(Component.translatable("minecraftlodmod.pantalla.boton"),
                        b -> Minecraft.getInstance().setScreen(new PantallaLod(video)))
                .bounds(video.width - 56, 6, 50, 20)
                .tooltip(Tooltip.create(Component.translatable("minecraftlodmod.pantalla.boton.tooltip")))
                .build();
        evento.addListener(boton);
    }
}
