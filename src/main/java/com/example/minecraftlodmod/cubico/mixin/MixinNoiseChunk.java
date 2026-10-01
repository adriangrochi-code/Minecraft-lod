package com.example.minecraftlodmod.cubico.mixin;

import com.example.minecraftlodmod.cubico.GeneracionVertical;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Rango vertical del NoiseChunk de cada chunk: la franja de {@link GeneracionVertical}. */
@Mixin(NoiseChunk.class)
public abstract class MixinNoiseChunk {

    @Inject(method = "forChunk", at = @At("HEAD"))
    private static void minecraftlodmod$antes(ChunkAccess chunk, RandomState aleatorio,
                                              DensityFunctions.BeardifierOrMarker beardifier,
                                              NoiseGeneratorSettings ajustes, Aquifer.FluidPicker fluidos,
                                              Blender blender, CallbackInfoReturnable<NoiseChunk> cir) {
        GeneracionVertical.antesDeCrearNoiseChunk(chunk, aleatorio, ajustes);
    }

    @Inject(method = "forChunk", at = @At("RETURN"))
    private static void minecraftlodmod$despues(ChunkAccess chunk, RandomState aleatorio,
                                                DensityFunctions.BeardifierOrMarker beardifier,
                                                NoiseGeneratorSettings ajustes, Aquifer.FluidPicker fluidos,
                                                Blender blender, CallbackInfoReturnable<NoiseChunk> cir) {
        GeneracionVertical.despuesDeCrearNoiseChunk();
    }

    @Redirect(method = "forChunk", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/NoiseSettings;clampToHeightAccessor(Lnet/minecraft/world/level/LevelHeightAccessor;)Lnet/minecraft/world/level/levelgen/NoiseSettings;"))
    private static NoiseSettings minecraftlodmod$recortar(NoiseSettings ajustes, LevelHeightAccessor acceso) {
        return GeneracionVertical.recortar(ajustes.clampToHeightAccessor(acceso), acceso);
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/Aquifer;create(Lnet/minecraft/world/level/levelgen/NoiseChunk;Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/levelgen/NoiseRouter;Lnet/minecraft/world/level/levelgen/PositionalRandomFactory;IILnet/minecraft/world/level/levelgen/Aquifer$FluidPicker;)Lnet/minecraft/world/level/levelgen/Aquifer;"))
    private Aquifer minecraftlodmod$acuifero(NoiseChunk noiseChunk, ChunkPos pos, NoiseRouter router,
                                             PositionalRandomFactory aleatorio, int minY, int alto,
                                             Aquifer.FluidPicker fluidos) {
        NoiseSettings completo = GeneracionVertical.rangoCompleto();
        if (completo != null) {
            minY = completo.minY();
            alto = completo.height();
        }
        return Aquifer.create(noiseChunk, pos, router, aleatorio, minY, alto, fluidos);
    }
}
