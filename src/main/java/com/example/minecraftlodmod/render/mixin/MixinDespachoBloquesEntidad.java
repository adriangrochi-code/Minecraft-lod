package com.example.minecraftlodmod.render.mixin;

import com.example.minecraftlodmod.render.OcultamientoEntidades;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Bloques con entidad tapados por el terreno o más lejos que el límite: no se dibujan ({@link OcultamientoEntidades}). */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class MixinDespachoBloquesEntidad {

    @Shadow
    public Camera camera;

    @Shadow
    public abstract <E extends BlockEntity> BlockEntityRenderer<E> getRenderer(E bloque);

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private <E extends BlockEntity> void lod$ocultar(E bloque, float parcial, PoseStack pose, MultiBufferSource buffers,
                                                     CallbackInfo ci) {
        BlockEntityRenderer<E> renderer = getRenderer(bloque);
        if (renderer == null || camera == null || renderer.shouldRenderOffScreen(bloque)) {
            return;
        }
        Vec3 c = camera.getPosition();
        if (OcultamientoEntidades.ocultarBloque(bloque, renderer.getRenderBoundingBox(bloque), c.x, c.y, c.z)) {
            ci.cancel();
        }
    }
}
