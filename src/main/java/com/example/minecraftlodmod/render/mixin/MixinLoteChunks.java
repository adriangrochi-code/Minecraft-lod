package com.example.minecraftlodmod.render.mixin;

import com.example.minecraftlodmod.config.ConfigLod;
import net.minecraft.client.multiplayer.ChunkBatchSizeCalculator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Ritmo de llegada de chunks: el cliente le pide al servidor tantos chunks
 * por tick como entran en 7 ms de su propio procesamiento (lo mide), y el
 * servidor se ajusta a eso. Con {@code msCargaChunks} ese presupuesto es
 * configurable: más ms = los chunks aparecen antes al moverse o entrar al
 * mundo, a cambio de algo más de trabajo por tick en el hilo principal.
 */
@Mixin(ChunkBatchSizeCalculator.class)
public abstract class MixinLoteChunks {

    @ModifyConstant(method = "getDesiredChunksPerTick", constant = @Constant(doubleValue = 7000000.0))
    private double lod$presupuesto(double original) {
        if (!ConfigLod.SPEC_CLIENTE.isLoaded()) {
            return original;
        }
        return ConfigLod.CLIENTE.msCargaChunks.get() * 1_000_000.0;
    }
}
