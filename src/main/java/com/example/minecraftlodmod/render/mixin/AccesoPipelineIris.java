package com.example.minecraftlodmod.render.mixin;

import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.targets.RenderTargets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Los colortex/depthtex del pack activo (para el framebuffer del contrato Voxy). */
@Mixin(value = IrisRenderingPipeline.class, remap = false)
public interface AccesoPipelineIris {

    @Accessor("renderTargets")
    RenderTargets minecraftlodmod$renderTargets();
}
