package com.example.minecraftlodmod.render.mixin;

import com.example.minecraftlodmod.render.EscaladoFsr;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** FSR: mientras se dibuja el mundo, el framebuffer "principal" es el chico (ver {@link EscaladoFsr}). */
@Mixin(Minecraft.class)
public abstract class MixinMinecraft {

    @Inject(method = "getMainRenderTarget", at = @At("HEAD"), cancellable = true)
    private void minecraftlodmod$framebufferEscalado(CallbackInfoReturnable<RenderTarget> cir) {
        RenderTarget escalado = EscaladoFsr.objetivoActivo();
        if (escalado != null) {
            cir.setReturnValue(escalado);
        }
    }
}
