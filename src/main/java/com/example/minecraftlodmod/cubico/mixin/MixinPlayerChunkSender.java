package com.example.minecraftlodmod.cubico.mixin;

import com.example.minecraftlodmod.cubico.SincroVertical;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Qué columna se le manda a qué jugador (sincronización vertical, ver SincroVertical). */
@Mixin(PlayerChunkSender.class)
public abstract class MixinPlayerChunkSender {

    @Inject(method = "sendChunk", at = @At("HEAD"))
    private static void minecraftlodmod$antes(ServerGamePacketListenerImpl conexion, ServerLevel nivel, LevelChunk chunk,
                                             CallbackInfo ci) {
        SincroVertical.antesDeEnviarChunk(conexion.player, chunk);
    }

    @Inject(method = "sendChunk", at = @At("RETURN"))
    private static void minecraftlodmod$despues(ServerGamePacketListenerImpl conexion, ServerLevel nivel, LevelChunk chunk,
                                               CallbackInfo ci) {
        SincroVertical.despuesDeEnviarChunk(conexion.player, chunk);
    }

    @Inject(method = "dropChunk", at = @At("HEAD"))
    private void minecraftlodmod$soltar(ServerPlayer jugador, ChunkPos pos, CallbackInfo ci) {
        SincroVertical.alSoltarChunk(jugador, pos);
    }
}
