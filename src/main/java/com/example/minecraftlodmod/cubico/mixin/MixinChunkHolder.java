package com.example.minecraftlodmod.cubico.mixin;

import com.example.minecraftlodmod.cubico.SincroVertical;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Cambios de bloques y block entities: solo a los jugadores que tienen esa
 * sección (los demás la tienen como aire; el cambio les llega al cargarla).
 * La luz va a todos, como en vanilla.
 */
@Mixin(ChunkHolder.class)
public abstract class MixinChunkHolder {

    @Inject(method = "broadcast", at = @At("HEAD"), cancellable = true)
    private void minecraftlodmod$filtrar(List<ServerPlayer> jugadores, Packet<?> paquete, CallbackInfo ci) {
        List<ServerPlayer> visibles = SincroVertical.filtrar(jugadores, paquete);
        if (visibles != null && visibles != jugadores) {
            visibles.forEach(j -> j.connection.send(paquete));
            ci.cancel();
        }
    }
}
