package com.example.minecraftlodmod.render.mixin;

import com.example.minecraftlodmod.render.EscaladoFsr;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** FSR: el mundo se dibuja en el framebuffer chico y después se escala (ver {@link EscaladoFsr}). */
@Mixin(GameRenderer.class)
public abstract class MixinGameRenderer {

    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
            shift = At.Shift.BEFORE))
    private void minecraftlodmod$antesDelMundo(DeltaTracker delta, boolean renderizarMundo, CallbackInfo ci) {
        EscaladoFsr.antesDelMundo();
    }

    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
            shift = At.Shift.AFTER))
    private void minecraftlodmod$despuesDelMundo(DeltaTracker delta, boolean renderizarMundo, CallbackInfo ci) {
        EscaladoFsr.despuesDelMundo();
    }
}
