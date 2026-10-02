package com.example.minecraftlodmod.tierra.mixin;

import com.example.minecraftlodmod.generation.FuenteAltura;
import com.example.minecraftlodmod.tierra.TierraReal;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.stream.Stream;

/**
 * En Tierra real, las alturas de {@code height_range} (menas, geodas, lagos de
 * lava subterráneos...) se miden desde la superficie de la columna y no desde
 * y = 0: vanilla las piensa para un suelo cerca de y {@link TierraReal#SUPERFICIE_VANILLA},
 * así que se corren lo que la superficie real está por encima o por debajo de
 * eso. Los diamantes quedan a 56-136 bloques bajo el suelo en el Himalaya y
 * bajo el fondo del mar, en vez de a y -64..16 en todo el mundo.
 */
@Mixin(HeightRangePlacement.class)
public abstract class MixinAlturaRelativa {

    @Shadow
    @Final
    private HeightProvider height;

    @Inject(method = "getPositions", at = @At("HEAD"), cancellable = true)
    private void minecraftlodmod$desdeLaSuperficie(PlacementContext contexto, RandomSource azar, BlockPos pos,
                                                  CallbackInfoReturnable<Stream<BlockPos>> cir) {
        FuenteAltura fuente = TierraReal.fuenteDe(contexto.getLevel().getLevel());
        if (fuente == null) return;
        int y = height.sample(azar, contexto) + fuente.altura(pos.getX(), pos.getZ()) - TierraReal.SUPERFICIE_VANILLA;
        int min = contexto.getLevel().getMinBuildHeight(), max = contexto.getLevel().getMaxBuildHeight();
        cir.setReturnValue(Stream.of(pos.atY(Math.clamp(y, min + 1, max - 2))));
    }
}
