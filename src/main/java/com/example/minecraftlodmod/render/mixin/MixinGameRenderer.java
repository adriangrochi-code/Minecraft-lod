package com.example.minecraftlodmod.render.mixin;

import com.example.minecraftlodmod.render.Escalado;
import com.example.minecraftlodmod.render.RenderLod;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Escalado: el mundo se dibuja en el framebuffer chico y después se escala; en modo temporal la
 * proyección lleva el jitter del cuadro (ver {@link Escalado}).
 * Shaderpacks: el far de la proyección se estira hasta donde llega el LOD (ver RenderLod#dibujarConShaderpack).
 */
@Mixin(GameRenderer.class)
public abstract class MixinGameRenderer {

    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
            shift = At.Shift.BEFORE))
    private void minecraftlodmod$antesDelMundo(DeltaTracker delta, boolean renderizarMundo, CallbackInfo ci) {
        Escalado.antesDelMundo();
    }

    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
            shift = At.Shift.AFTER))
    private void minecraftlodmod$despuesDelMundo(DeltaTracker delta, boolean renderizarMundo, CallbackInfo ci) {
        Escalado.despuesDelMundo();
    }

    @Inject(method = "getDepthFar", at = @At("RETURN"), cancellable = true)
    private void minecraftlodmod$farDelLod(CallbackInfoReturnable<Float> cir) {
        float lod = RenderLod.farParaShaders();
        if (lod > cir.getReturnValueF()) {
            cir.setReturnValue(lod);
        }
    }

    @Inject(method = "getProjectionMatrix", at = @At("RETURN"), cancellable = true)
    private void minecraftlodmod$jitter(double fov, CallbackInfoReturnable<org.joml.Matrix4f> cir) {
        org.joml.Matrix4f conJitter = Escalado.conJitter(cir.getReturnValue());
        if (conJitter != cir.getReturnValue()) {
            cir.setReturnValue(conJitter);
        }
    }
}
