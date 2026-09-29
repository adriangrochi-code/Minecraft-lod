package com.example.minecraftlodmod;

import com.example.minecraftlodmod.config.QualityPreset;
import com.example.minecraftlodmod.generation.GeneradorLocal;
import com.example.minecraftlodmod.network.ProtocoloLod;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

import java.lang.management.ManagementFactory;

/**
 * Punto de entrada del mod.
 *
 * TODO (próximos hitos, ver el documento de arquitectura):
 *  - Registrar el listener de {@code RenderLevelStageEvent} (módulo render/).
 *  - Registrar el listener de {@code ViewportEvent.ComputeFov} (módulo core/,
 *    para alimentar al selector de LOD con el FOV efectivo).
 *  - Tomar el preset de la config (Cloth Config) en vez de la heurística.
 *  - Registrar la dimensión custom de benchmark (módulo benchmark/).
 *  - Inicializar Cloth Config (módulo config/).
 *
 * Ya registrado: la generación en modo LOCAL ({@link GeneradorLocal}), que
 * corre en todo servidor con el mod — dedicado o integrado de singleplayer —
 * y el protocolo de red que sirve esos nodos ({@link ProtocoloLod}).
 */
@Mod(MinecraftLodMod.MOD_ID)
public class MinecraftLodMod {

    public static final String MOD_ID = "minecraftlodmod";

    public MinecraftLodMod(IEventBus modEventBus) {
        GeneradorLocal generador = new GeneradorLocal(presetInicial());
        ProtocoloLod protocolo = new ProtocoloLod(generador);
        NeoForge.EVENT_BUS.register(generador);
        NeoForge.EVENT_BUS.register(protocolo);
        modEventBus.addListener(protocolo::registrar);
    }

    /** Hasta que exista config/ persistida: la recomendación por hardware (sección 14). */
    private static QualityPreset presetInicial() {
        long ramTotalMb = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean so) {
            ramTotalMb = so.getTotalMemorySize() / (1024 * 1024);
        }
        return QualityPreset.recomendarPorHardware(Runtime.getRuntime().availableProcessors(), ramTotalMb);
    }
}
