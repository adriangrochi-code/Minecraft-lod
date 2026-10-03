package com.example.minecraftlodmod.cubico.mixin;

import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Sección de un paquete de varios cambios de bloques. */
@Mixin(ClientboundSectionBlocksUpdatePacket.class)
public interface AccesoSeccionBloques {

    @Accessor("sectionPos")
    SectionPos minecraftlodmod$seccion();
}
