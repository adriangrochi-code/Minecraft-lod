package com.example.minecraftlodmod.tierra.mixin;

import com.example.minecraftlodmod.tierra.TierraReal;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla marca como "ajustes experimentales" todo Overworld que no sea el
 * suyo (tipo de dimensión o fuente de biomas propios) y pide confirmación al
 * crear y al abrir el mundo. Es una protección, no un error; para el
 * Overworld de Tierra real (sus {@code dimension_type} propios) se trata como
 * estable, igual que el Amplificado.
 */
@Mixin(WorldDimensions.class)
public abstract class MixinEstabilidadTierra {

    @Inject(method = "checkStability", at = @At("HEAD"), cancellable = true)
    private static void minecraftlodmod$tierraEsEstable(ResourceKey<LevelStem> clave, LevelStem dimension,
                                                        CallbackInfoReturnable<com.mojang.serialization.Lifecycle> cir) {
        if (clave == LevelStem.OVERWORLD && dimension.type().unwrapKey()
                .map(k -> TierraReal.metrosPorBloque(k.location()) > 0).orElse(false)) {
            cir.setReturnValue(com.mojang.serialization.Lifecycle.stable());
        }
    }
}
