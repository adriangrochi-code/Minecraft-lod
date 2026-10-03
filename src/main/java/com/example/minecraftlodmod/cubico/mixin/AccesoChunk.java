package com.example.minecraftlodmod.cubico.mixin;

import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Nivel del chunk (en un ProtoChunk del servidor, el ServerLevel). */
@Mixin(ChunkAccess.class)
public interface AccesoChunk {

    @Accessor("levelHeightAccessor")
    LevelHeightAccessor minecraftlodmod$alturas();
}
