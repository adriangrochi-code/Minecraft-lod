package com.example.minecraftlodmod.cubico.mixin;

import com.example.minecraftlodmod.cubico.GeneracionVertical;
import net.minecraft.core.Holder;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paso NOISE: llena solo la franja de {@link GeneracionVertical} y rellena lo de abajo. */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class MixinGeneradorRuido {

    @Shadow
    @Final
    private Holder<NoiseGeneratorSettings> settings;

    @Unique
    private static final ThreadLocal<long[]> minecraftlodmod$inicio = ThreadLocal.withInitial(() -> new long[1]);

    @Redirect(method = "fillFromNoise", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/NoiseSettings;clampToHeightAccessor(Lnet/minecraft/world/level/LevelHeightAccessor;)Lnet/minecraft/world/level/levelgen/NoiseSettings;"))
    private NoiseSettings minecraftlodmod$recortar(NoiseSettings ajustes, LevelHeightAccessor acceso) {
        return GeneracionVertical.recortar(ajustes.clampToHeightAccessor(acceso), acceso);
    }

    @Inject(method = "doFill", at = @At("HEAD"))
    private void minecraftlodmod$antesDeLlenar(Blender blender, StructureManager estructuras, RandomState aleatorio,
                                               ChunkAccess chunk, int celdaMinY, int celdasY,
                                               CallbackInfoReturnable<ChunkAccess> cir) {
        minecraftlodmod$inicio.get()[0] = System.nanoTime();
    }

    @Inject(method = "doFill", at = @At("RETURN"))
    private void minecraftlodmod$despuesDeLlenar(Blender blender, StructureManager estructuras, RandomState aleatorio,
                                                 ChunkAccess chunk, int celdaMinY, int celdasY,
                                                 CallbackInfoReturnable<ChunkAccess> cir) {
        GeneracionVertical.despuesDeLlenar(chunk, settings.value(), System.nanoTime() - minecraftlodmod$inicio.get()[0]);
    }
}
