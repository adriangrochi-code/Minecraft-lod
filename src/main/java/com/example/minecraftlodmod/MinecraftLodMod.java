package com.example.minecraftlodmod;

import com.example.minecraftlodmod.config.ConfigLod;
import com.example.minecraftlodmod.config.PantallaConfig;
import com.example.minecraftlodmod.generation.GeneradorLocal;
import com.example.minecraftlodmod.network.ProtocoloLod;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Punto de entrada del mod.
 *
 * TODO (próximos hitos, ver el documento de arquitectura):
 *  - Registrar el listener de {@code RenderLevelStageEvent} (módulo render/).
 *  - Registrar el listener de {@code ViewportEvent.ComputeFov} (módulo core/,
 *    para alimentar al selector de LOD con el FOV efectivo).
 *  - Registrar la dimensión custom de benchmark (módulo benchmark/).
 *
 * Ya registrado: la config ({@link ConfigLod}, con pantalla en el cliente),
 * la generación en modo LOCAL ({@link GeneradorLocal}), que corre en todo
 * servidor con el mod — dedicado o integrado de singleplayer — y el
 * protocolo de red que sirve esos nodos ({@link ProtocoloLod}).
 */
@Mod(MinecraftLodMod.MOD_ID)
public class MinecraftLodMod {

    public static final String MOD_ID = "minecraftlodmod";

    public MinecraftLodMod(IEventBus modEventBus, ModContainer contenedor) {
        ConfigLod.registrar(contenedor);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            PantallaConfig.registrar(contenedor);
        }

        GeneradorLocal generador = new GeneradorLocal(ConfigLod::calidadServidor);
        ProtocoloLod protocolo = new ProtocoloLod(generador);
        NeoForge.EVENT_BUS.register(generador);
        NeoForge.EVENT_BUS.register(protocolo);
        modEventBus.addListener(protocolo::registrar);
    }
}
