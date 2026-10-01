package com.example.minecraftlodmod.cubico.mixin;

import com.example.minecraftlodmod.cubico.DecoracionVertical;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mientras {@link DecoracionVertical} corre features sobre el mundo vivo, las
 * escrituras fuera de la banda completada se descartan y las de adentro van
 * sin avisar a los vecinos (como en la generación, que escribe en un
 * {@code WorldGenRegion}).
 */
@Mixin(Level.class)
public abstract class MixinLevelFiltro {

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD"), cancellable = true)
    private void minecraftlodmod$filtrar(BlockPos pos, BlockState estado, int banderas, int recursion,
                                        CallbackInfoReturnable<Boolean> cir) {
        if (DecoracionVertical.descartar(pos)) {
            cir.setReturnValue(false);
        }
    }

    @ModifyVariable(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int minecraftlodmod$sinVecinos(int banderas) {
        return DecoracionVertical.banderas(banderas);
    }
}
