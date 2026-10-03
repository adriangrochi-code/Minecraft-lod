package com.example.minecraftlodmod.cubico.mixin;

import com.example.minecraftlodmod.cubico.GeneracionVertical;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.server.level.ColumnPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * La "superficie preliminar" (acuíferos, reglas de superficie) se busca en la
 * altura completa de la columna aunque el NoiseChunk esté recortado a una
 * franja: si no, en una banda subterránea la encontraría en el techo de la
 * banda y los acuíferos se comportarían como cerca de la superficie.
 */
@Mixin(NoiseChunk.class)
public abstract class MixinNoiseChunkSuperficie {

    @Shadow
    @Final
    private NoiseSettings noiseSettings;
    @Shadow
    @Final
    private DensityFunction initialDensityNoJaggedness;
    @Shadow
    @Final
    int cellHeight;

    @Unique
    private NoiseSettings minecraftlodmod$completo;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void minecraftlodmod$guardarCompleto(CallbackInfo ci) {
        NoiseSettings c = GeneracionVertical.rangoCompleto();
        if (c != null && !c.equals(noiseSettings)) {
            minecraftlodmod$completo = c;
        }
    }

    @Inject(method = "computePreliminarySurfaceLevel", at = @At("HEAD"), cancellable = true)
    private void minecraftlodmod$superficieCompleta(long columna, CallbackInfoReturnable<Integer> cir) {
        NoiseSettings c = minecraftlodmod$completo;
        if (c == null) {
            return;
        }
        int x = ColumnPos.getX(columna);
        int z = ColumnPos.getZ(columna);
        for (int y = c.minY() + c.height(); y >= c.minY(); y -= cellHeight) {
            if (initialDensityNoJaggedness.compute(new DensityFunction.SinglePointContext(x, y, z)) > 0.390625) {
                cir.setReturnValue(y);
                return;
            }
        }
        cir.setReturnValue(Integer.MAX_VALUE);
    }
}
