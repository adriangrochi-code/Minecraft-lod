package com.example.minecraftlodmod.render.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * El lightmap de vanilla en CPU (16×16: x = luz de bloque, y = luz de cielo), para que el
 * LOD siga la hora del día (ver {@code RenderLod.colorLuzCielo}). VulkanMod también lo llena.
 */
@Mixin(LightTexture.class)
public interface AccesoLightTexture {

    @Accessor("lightPixels")
    NativeImage minecraftlodmod$pixeles();
}
