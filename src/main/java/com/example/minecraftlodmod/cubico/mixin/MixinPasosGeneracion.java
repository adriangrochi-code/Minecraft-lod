package com.example.minecraftlodmod.cubico.mixin;

import com.example.minecraftlodmod.cubico.GeneracionParalela;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/** Superficie, carvers y features en el pool de hilos en vez del mailbox de worldgen ({@link GeneracionParalela}). */
@Mixin(ChunkStatusTasks.class)
public abstract class MixinPasosGeneracion {

    @Inject(method = "generateSurface", at = @At("HEAD"), cancellable = true)
    private static void lod$superficie(WorldGenContext contexto, ChunkStep paso, StaticCache2D<GenerationChunkHolder> vecinos,
                                       ChunkAccess chunk, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (GeneracionParalela.desviar()) {
            cir.setReturnValue(GeneracionParalela.enPool(
                    () -> AccesoPasosGeneracion.superficie(contexto, paso, vecinos, chunk)));
        }
    }

    @Inject(method = "generateCarvers", at = @At("HEAD"), cancellable = true)
    private static void lod$carvers(WorldGenContext contexto, ChunkStep paso, StaticCache2D<GenerationChunkHolder> vecinos,
                                    ChunkAccess chunk, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (GeneracionParalela.desviar()) {
            cir.setReturnValue(GeneracionParalela.enPool(
                    () -> AccesoPasosGeneracion.carvers(contexto, paso, vecinos, chunk)));
        }
    }

    @Inject(method = "generateFeatures", at = @At("HEAD"), cancellable = true)
    private static void lod$features(WorldGenContext contexto, ChunkStep paso, StaticCache2D<GenerationChunkHolder> vecinos,
                                     ChunkAccess chunk, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (GeneracionParalela.desviar()) {
            cir.setReturnValue(GeneracionParalela.enPoolConVecinos(chunk.getPos(),
                    () -> AccesoPasosGeneracion.features(contexto, paso, vecinos, chunk)));
        }
    }
}
