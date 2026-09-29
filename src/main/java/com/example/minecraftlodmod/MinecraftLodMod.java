package com.example.minecraftlodmod;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * Punto de entrada del mod.
 *
 * TODO (próximos hitos, ver el documento de arquitectura):
 *  - Registrar el listener de {@code RenderLevelStageEvent} (módulo render/).
 *  - Registrar el listener de {@code ViewportEvent.ComputeFov} (módulo core/,
 *    para alimentar al selector de LOD con el FOV efectivo).
 *  - Registrar los payloads de red (módulo network/).
 *  - Registrar la dimensión custom de benchmark (módulo benchmark/).
 *  - Inicializar Cloth Config (módulo config/).
 *
 * Por ahora esta clase solo deja el esqueleto mínimo para que el proyecto
 * compile y cargue como mod vacío.
 */
@Mod(MinecraftLodMod.MOD_ID)
public class MinecraftLodMod {

    public static final String MOD_ID = "minecraftlodmod";

    public MinecraftLodMod(IEventBus modEventBus) {
        // Los listeners de ciclo de vida del mod (setup, registro de
        // dimensiones, etc.) se agregan acá a medida que se implementan
        // los módulos correspondientes.
    }
}
