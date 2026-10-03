package com.example.minecraftlodmod.render.mixin;

import com.example.minecraftlodmod.render.DibujoVoxy;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.VanillaRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Avisa a DibujoVoxy cuando Iris no pudo armar el pipeline del pack (lo cambia por el de vanilla). */
@Mixin(value = Iris.class, remap = false)
public class MixinPipelineIris {

    @Inject(method = "createPipeline", at = @At("RETURN"))
    private static void minecraftlodmod$pipelineFallido(CallbackInfoReturnable<WorldRenderingPipeline> cir) {
        if (cir.getReturnValue() instanceof VanillaRenderingPipeline && Iris.getCurrentPack().isPresent()) {
            DibujoVoxy.pipelineFallido();
        }
    }
}
