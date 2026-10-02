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
 * por defecto) en toda la altura, hasta el nivel de la masa de agua de cada
 * columna (el mar o su lago, {@code AlturaTierra.nivelAguaY}); en las columnas
 * secas (tierra firme, depresiones como el Mar Muerto o Qattara) es aire.
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
            ThreadLocal<Aquifer.FluidStatus[]> estados = ThreadLocal.withInitial(() -> new Aquifer.FluidStatus[256]);
            cir.setReturnValue((x, y, z) -> {
                // Agua hasta el nivel de su masa (el mar en 63, cada lago en el suyo: AguaContinental);
                // bajo la tierra firme y en las depresiones secas, aire.
                long clave = (long) x << 32 | (z & 0xFFFFFFFFL);
                int i = (x & 15) << 4 | (z & 15);
                long[] k = claves.get();
                Aquifer.FluidStatus[] estado = estados.get();
                if (k[i] != clave) {
                    AlturaTierra a = superficie.altura();
                    int n = a == null ? ajustes.seaLevel() : a.nivelAguaY(x + 0.5, z + 0.5);
                    estado[i] = n == Integer.MIN_VALUE ? seco
                            : n == ajustes.seaLevel() ? agua : new Aquifer.FluidStatus(n, ajustes.defaultFluid());
                    k[i] = clave;
                }
                return estado[i];
            });
        }
    }
}
