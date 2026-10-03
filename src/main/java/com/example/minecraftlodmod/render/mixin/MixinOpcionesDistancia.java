package com.example.minecraftlodmod.render.mixin;

import com.example.minecraftlodmod.config.DistanciaVanilla;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Modo híbrido: la distancia de render que usan todos los renderers, acotada (ver {@link DistanciaVanilla}). */
@Mixin(Options.class)
public abstract class MixinOpcionesDistancia {

    @Inject(method = "getEffectiveRenderDistance", at = @At("RETURN"), cancellable = true)
    private void minecraftlodmod$acotar(CallbackInfoReturnable<Integer> cir) {
        int tope = DistanciaVanilla.topeActual();
        if (tope != Integer.MAX_VALUE) {
            cir.setReturnValue(DistanciaVanilla.acotar(cir.getReturnValue(), tope));
        }
    }
}
