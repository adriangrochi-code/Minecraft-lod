package com.example.minecraftlodmod.cubico.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Altura de un block entity dentro del paquete de columna. */
@Mixin(targets = "net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData$BlockEntityInfo")
public interface AccesoInfoBlockEntity {

    @Accessor("y")
    int minecraftlodmod$y();
}
