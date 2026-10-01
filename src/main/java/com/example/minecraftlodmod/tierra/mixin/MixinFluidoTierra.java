package com.example.minecraftlodmod.tierra.mixin;

import com.example.minecraftlodmod.tierra.SuperficieTierra;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla pone lava bajo {@code min(-54, sea_level)} en el fluido global; en
 * Tierra real el fondo del océano está cientos de bloques más abajo y el mar
 * entero saldría de lava ({@code docs/tierra-real/01-decisiones.md}, riesgo
 * 1). Solo en los ajustes que usan {@link SuperficieTierra}: agua (el fluido
 * por defecto) hasta el nivel del mar en toda la altura.
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class MixinFluidoTierra {

    @Inject(method = "createFluidPicker", at = @At("HEAD"), cancellable = true)
    private static void minecraftlodmod$aguaEnTodaLaAltura(NoiseGeneratorSettings ajustes,
                                                          CallbackInfoReturnable<Aquifer.FluidPicker> cir) {
        if (SuperficieTierra.de(ajustes) != null) {
            Aquifer.FluidStatus agua = new Aquifer.FluidStatus(ajustes.seaLevel(), ajustes.defaultFluid());
            cir.setReturnValue((x, y, z) -> agua);
        }
    }
}
