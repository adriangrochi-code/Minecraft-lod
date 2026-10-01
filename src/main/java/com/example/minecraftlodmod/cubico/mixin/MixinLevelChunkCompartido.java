package com.example.minecraftlodmod.cubico.mixin;

import com.example.minecraftlodmod.cubico.SeccionesCompartidas;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Copia al escribir: la sección deja de usar el contenedor compartido antes del cambio. */
@Mixin(LevelChunk.class)
public abstract class MixinLevelChunkCompartido {

    @Inject(method = "setBlockState", at = @At("HEAD"))
    private void minecraftlodmod$copiarAntes(BlockPos pos, BlockState estado, boolean moviendo,
                                             CallbackInfoReturnable<BlockState> cir) {
        SeccionesCompartidas.copiarSiCompartida((LevelChunk) (Object) this, pos.getY());
    }
}
