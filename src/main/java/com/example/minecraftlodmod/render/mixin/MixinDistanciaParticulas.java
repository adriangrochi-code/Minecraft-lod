package com.example.minecraftlodmod.render.mixin;

import com.example.minecraftlodmod.config.ConfigLod;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Distancia máxima de partículas ({@code distanciaParticulas}); vanilla usa 32 bloques. */
@Mixin(LevelRenderer.class)
public abstract class MixinDistanciaParticulas {

    @ModifyConstant(method = "addParticleInternal(Lnet/minecraft/core/particles/ParticleOptions;ZZDDDDDD)Lnet/minecraft/client/particle/Particle;",
            constant = @Constant(doubleValue = 1024.0))
    private double lod$distancia(double original) {
        if (!ConfigLod.SPEC_CLIENTE.isLoaded()) {
            return original;
        }
        double d = ConfigLod.CLIENTE.distanciaParticulas.get();
        return Math.min(original, d * d);
    }
}
