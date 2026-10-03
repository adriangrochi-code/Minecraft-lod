package com.example.minecraftlodmod.render.mixin;

import com.example.minecraftlodmod.config.ConfigLod;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleRenderType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.Queue;

/**
 * Tope total de partículas ({@code maxParticulas}): vanilla admite 16384 por
 * tipo de dibujo; con el tope, las nuevas se descartan mientras haya tantas
 * vivas (explosiones, lluvia de partículas de mods).
 */
@Mixin(ParticleEngine.class)
public abstract class MixinMotorParticulas {

    @Shadow
    @Final
    private Map<ParticleRenderType, Queue<Particle>> particles;

    @Shadow
    @Final
    private Queue<Particle> particlesToAdd;

    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void lod$tope(Particle particula, CallbackInfo ci) {
        if (!ConfigLod.SPEC_CLIENTE.isLoaded()) {
            return;
        }
        int tope = ConfigLod.CLIENTE.maxParticulas.get();
        if (tope >= ConfigLod.MAX_PARTICULAS_VANILLA) {
            return;
        }
        int vivas = particlesToAdd.size();
        for (Queue<Particle> cola : particles.values()) {
            vivas += cola.size();
        }
        if (vivas >= tope) {
            ci.cancel();
        }
    }
}
