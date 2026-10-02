package com.example.minecraftlodmod.render.mixin;

import com.example.minecraftlodmod.render.OcultamientoEntidades;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Entidades tapadas por el terreno o más lejos que el límite: no se dibujan ({@link OcultamientoEntidades}). */
@Mixin(EntityRenderDispatcher.class)
public abstract class MixinDespachoEntidades {

    @Inject(method = "shouldRender", at = @At("RETURN"), cancellable = true)
    private <E extends Entity> void lod$ocultar(E entidad, Frustum frustum, double x, double y, double z,
                                                CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && OcultamientoEntidades.ocultarEntidad(entidad, x, y, z)) {
            cir.setReturnValue(false);
        }
    }
}
