package com.example.minecraftlodmod.render.mixin;

import com.example.minecraftlodmod.render.RenderLod;
import net.irisshaders.iris.shadows.ShadowRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * El LOD en el mapa de sombras del shaderpack ({@link RenderLod#dibujarSombras}): después
 * de la tercera capa de terreno que Iris dibuja en la sombra (sólido, cutout,
 * cutoutMipped), con el mismo estado (mapa de sombras atado, culling apagado) y sus
 * matrices. Se aplica solo con Iris instalado ({@link PluginMixins}).
 */
@Mixin(value = ShadowRenderer.class, remap = false)
public class MixinSombrasIris {

    @Inject(method = "renderShadows", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/mixin/LevelRendererAccessor;invokeRenderSectionLayer(Lnet/minecraft/client/renderer/RenderType;DDDLorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",
            ordinal = 2, shift = At.Shift.AFTER))
    private void minecraftlodmod$lodEnSombras(CallbackInfo ci) {
        // La misma posición de cámara con la que Iris corre el terreno en esta pasada.
        org.joml.Vector3d camara = net.irisshaders.iris.uniforms.CameraUniforms.getUnshiftedCameraPosition();
        RenderLod.dibujarSombras(ShadowRenderer.MODELVIEW, ShadowRenderer.PROJECTION, camara.x, camara.y, camara.z);
    }
}
