package com.example.minecraftlodmod.render.mixin;

import com.example.minecraftlodmod.render.Escalado;
import com.mojang.blaze3d.pipeline.RenderTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * FSR: vanilla vuelve al framebuffer principal con {@code bindWrite(false)} (sin
 * tocar el viewport) después de limpiar otros del tamaño de la ventana, como el
 * del contorno de entidades. Con el principal chico, el viewport quedaba del
 * tamaño de la ventana: entidades, transparentes, líneas y la mano se dibujaban
 * al doble y corridos. Al activar el framebuffer escalado se fija siempre su viewport.
 */
@Mixin(RenderTarget.class)
public abstract class MixinRenderTarget {

    @ModifyVariable(method = "bindWrite", at = @At("HEAD"), argsOnly = true)
    private boolean minecraftlodmod$viewportDelEscalado(boolean fijarViewport) {
        return fijarViewport || Escalado.objetivoActivo() == (Object) this;
    }
}
