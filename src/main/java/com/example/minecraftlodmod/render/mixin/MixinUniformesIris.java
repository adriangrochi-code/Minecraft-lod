package com.example.minecraftlodmod.render.mixin;

import com.example.minecraftlodmod.render.DibujoVoxy;
import net.irisshaders.iris.gl.uniform.UniformHolder;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.IdMap;
import net.irisshaders.iris.uniforms.CommonUniforms;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Uniforms del contrato Voxy ({@code vxProj}, {@code vxModelView}, sus inversas y
 * previas, {@code vxRenderDistance}) para todos los programas del pack: Iris arma
 * con este método los uniforms que asigna a gbuffers, deferred, composite y final.
 */
@Mixin(value = CommonUniforms.class, remap = false)
public abstract class MixinUniformesIris {

    @Inject(method = "addNonDynamicUniforms", at = @At("TAIL"))
    private static void minecraftlodmod$vx(UniformHolder uniforms, IdMap idMap, PackDirectives directivas,
                                          FrameUpdateNotifier notificador, CallbackInfo ci) {
        uniforms.uniform1i(UniformUpdateFrequency.PER_FRAME, "vxRenderDistance", DibujoVoxy::distanciaChunks);
        uniforms.uniformMatrix(UniformUpdateFrequency.PER_FRAME, "vxProj", DibujoVoxy::proyeccion);
        uniforms.uniformMatrix(UniformUpdateFrequency.PER_FRAME, "vxProjInv", DibujoVoxy::proyeccionInversa);
        uniforms.uniformMatrix(UniformUpdateFrequency.PER_FRAME, "vxProjPrev", DibujoVoxy::proyeccionPrevia);
        uniforms.uniformMatrix(UniformUpdateFrequency.PER_FRAME, "vxModelView", DibujoVoxy::vista);
        uniforms.uniformMatrix(UniformUpdateFrequency.PER_FRAME, "vxModelViewInv", DibujoVoxy::vistaInversa);
        uniforms.uniformMatrix(UniformUpdateFrequency.PER_FRAME, "vxModelViewPrev", DibujoVoxy::vistaPrevia);
    }
}
