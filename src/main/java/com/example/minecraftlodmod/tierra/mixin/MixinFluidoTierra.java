package com.example.minecraftlodmod.tierra.mixin;

import com.example.minecraftlodmod.tierra.AlturaTierra;
import com.example.minecraftlodmod.tierra.SuperficieTierra;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.dimension.DimensionType;
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
 * por defecto) hasta el nivel del mar en toda la altura, pero solo en las
 * columnas del mar; bajo tierra firme el fluido global es aire.
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class MixinFluidoTierra {

    @Inject(method = "createFluidPicker", at = @At("HEAD"), cancellable = true)
    private static void minecraftlodmod$aguaEnTodaLaAltura(NoiseGeneratorSettings ajustes,
                                                          CallbackInfoReturnable<Aquifer.FluidPicker> cir) {
        SuperficieTierra superficie = SuperficieTierra.de(ajustes);
        if (superficie != null) {
            Aquifer.FluidStatus agua = new Aquifer.FluidStatus(ajustes.seaLevel(), ajustes.defaultFluid());
            Aquifer.FluidStatus seco = new Aquifer.FluidStatus(DimensionType.MIN_Y * 2, Blocks.AIR.defaultBlockState());
            // Vanilla consulta esto en cada bloque de aire o agua del llenado: la respuesta por
            // columna se guarda en una tabla de 16×16 por hilo (una entrada por columna del chunk).
            ThreadLocal<long[]> claves = ThreadLocal.withInitial(() -> {
                long[] k = new long[256];
                java.util.Arrays.fill(k, Long.MIN_VALUE);
                return k;
            });
            ThreadLocal<boolean[]> mares = ThreadLocal.withInitial(() -> new boolean[256]);
            cir.setReturnValue((x, y, z) -> {
                // Agua solo bajo columnas cuyo suelo está bajo el nivel del mar (el mar); bajo la
                // tierra firme, las cuevas quedan secas en vez de inundadas hasta y 62.
                long clave = (long) x << 32 | (z & 0xFFFFFFFFL);
                int i = (x & 15) << 4 | (z & 15);
                long[] k = claves.get();
                boolean[] mar = mares.get();
                if (k[i] != clave) {
                    AlturaTierra a = superficie.altura();
                    mar[i] = a == null || a.alturaExacta(x + 0.5, z + 0.5) < ajustes.seaLevel();
                    k[i] = clave;
                }
                return mar[i] ? agua : seco;
            });
        }
    }
}
