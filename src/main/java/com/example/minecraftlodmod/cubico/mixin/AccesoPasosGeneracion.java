package com.example.minecraftlodmod.cubico.mixin;

import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.concurrent.CompletableFuture;

/** Los pasos de generación originales, para correrlos desde el pool (ver GeneracionParalela). */
@Mixin(ChunkStatusTasks.class)
public interface AccesoPasosGeneracion {

    @Invoker("generateSurface")
    static CompletableFuture<ChunkAccess> superficie(WorldGenContext contexto, ChunkStep paso,
                                                     StaticCache2D<GenerationChunkHolder> vecinos, ChunkAccess chunk) {
        throw new AssertionError();
    }

    @Invoker("generateCarvers")
    static CompletableFuture<ChunkAccess> carvers(WorldGenContext contexto, ChunkStep paso,
                                                  StaticCache2D<GenerationChunkHolder> vecinos, ChunkAccess chunk) {
        throw new AssertionError();
    }

    @Invoker("generateFeatures")
    static CompletableFuture<ChunkAccess> features(WorldGenContext contexto, ChunkStep paso,
                                                   StaticCache2D<GenerationChunkHolder> vecinos, ChunkAccess chunk) {
        throw new AssertionError();
    }
}
