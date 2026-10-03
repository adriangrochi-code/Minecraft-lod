package com.example.minecraftlodmod.generation;

import com.example.minecraftlodmod.config.ConfigLod;
import com.example.minecraftlodmod.config.ParametrosCalidad;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * {@code /lod pregenerar [on|off|radio <chunks>]}: prender, apagar y ver el
 * avance del {@link PregeneradorChunks} sin salir del mundo. Escribe la
 * misma opción que el menú del mod (config del cliente), así que ambos
 * quedan siempre de acuerdo. Solo singleplayer: en un servidor dedicado esa
 * config no existe (pendiente, ver NOTES.md).
 */
final class ComandoPregeneracion {

    private ComandoPregeneracion() {
    }

    static void registrar(CommandDispatcher<CommandSourceStack> dispatcher, PregeneradorChunks pregenerador) {
        dispatcher.register(Commands.literal("lod")
                .requires(fuente -> fuente.getServer().isSingleplayer())
                .then(Commands.literal("pregenerar")
                        .executes(c -> estado(c.getSource(), pregenerador))
                        .then(Commands.literal("on").executes(c -> prender(c.getSource(), true)))
                        .then(Commands.literal("off").executes(c -> prender(c.getSource(), false)))
                        .then(Commands.literal("radio")
                                .then(Commands.argument("chunks", IntegerArgumentType.integer(16, ParametrosCalidad.RADIO_MAX))
                                        .executes(c -> radio(c.getSource(), IntegerArgumentType.getInteger(c, "chunks")))))));
    }

    private static int prender(CommandSourceStack fuente, boolean activo) {
        ConfigLod.CLIENTE.pregenerar.set(activo);
        ConfigLod.SPEC_CLIENTE.save();
        fuente.sendSuccess(() -> activo
                ? Component.translatable("minecraftlodmod.comando.pregenerar.on", ConfigLod.CLIENTE.radioPregeneracion.get())
                : Component.translatable("minecraftlodmod.comando.pregenerar.off"), false);
        return 1;
    }

    private static int radio(CommandSourceStack fuente, int chunks) {
        ConfigLod.CLIENTE.radioPregeneracion.set(chunks);
        ConfigLod.SPEC_CLIENTE.save();
        fuente.sendSuccess(() -> Component.translatable("minecraftlodmod.comando.pregenerar.radio", chunks), false);
        return 1;
    }

    private static int estado(CommandSourceStack fuente, PregeneradorChunks pregenerador) {
        if (!ConfigLod.CLIENTE.pregenerar.get()) {
            fuente.sendSuccess(() -> Component.translatable("minecraftlodmod.comando.pregenerar.apagado"), false);
            return 1;
        }
        PregeneradorChunks.Estado e = pregenerador.estado();
        fuente.sendSuccess(() -> e.completo()
                ? Component.translatable("minecraftlodmod.comando.pregenerar.completo", e.radio(), e.generados())
                : Component.translatable("minecraftlodmod.comando.pregenerar.estado", e.anillo(), e.radio(),
                e.generados(), String.format("%.1f", e.porSegundo()), e.enCurso()), false);
        return 1;
    }
}
