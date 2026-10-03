package com.example.minecraftlodmod.render.mixin;

import com.example.minecraftlodmod.render.DibujoVoxy;
import com.google.common.collect.ImmutableSet;
import net.irisshaders.iris.gl.sampler.SamplerHolder;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.samplers.IrisSamplers;
import net.irisshaders.iris.targets.RenderTargets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

/**
 * Profundidad propia del LOD ({@code vxDepthTexOpaque/Trans}) para todos los
 * programas del pack, como Iris agrega {@code dhDepthTex} para Distant Horizons.
 */
@Mixin(value = IrisSamplers.class, remap = false)
public abstract class MixinSamplersIris {

    @Inject(method = "addRenderTargetSamplers", at = @At("TAIL"))
    private static void minecraftlodmod$vx(SamplerHolder samplers, Supplier<ImmutableSet<Integer>> giradas,
                                          RenderTargets targets, boolean pantallaCompleta, WorldRenderingPipeline pipeline,
                                          CallbackInfo ci) {
        samplers.addDynamicSampler(DibujoVoxy::profundidadOpaca, "vxDepthTexOpaque");
        samplers.addDynamicSampler(DibujoVoxy::profundidadTranslucida, "vxDepthTexTrans");
    }
}
