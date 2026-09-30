package com.example.minecraftlodmod.render.mixin;

import com.example.minecraftlodmod.render.EscaladoFsr;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * FSR: el uniform ScreenSize sale del tamaño de la ventana, pero mientras se dibuja
 * el mundo el framebuffer es el chico. Las líneas (contorno del bloque apuntado,
 * hitboxes) se ensanchan en pantalla con ScreenSize: con el tamaño equivocado
 * quedan con otro grosor y se deforman al girar. Acá se corrige al tamaño real.
 */
@Mixin(ShaderInstance.class)
public abstract class MixinShaderInstance {

    @Inject(method = "setDefaultUniforms", at = @At("TAIL"), require = 0)
    private void minecraftlodmod$tamanoDelFramebuffer(VertexFormat.Mode modo, Matrix4f vista, Matrix4f proyeccion,
                                                     Window ventana, CallbackInfo ci) {
        RenderTarget escalado = EscaladoFsr.objetivoActivo();
        ShaderInstance shader = (ShaderInstance) (Object) this;
        if (escalado != null && shader.SCREEN_SIZE != null) {
            shader.SCREEN_SIZE.set((float) escalado.width, (float) escalado.height);
        }
    }
}
