package com.example.minecraftlodmod.cubico.mixin;

import com.example.minecraftlodmod.cubico.RangoSecciones;
import com.example.minecraftlodmod.cubico.SincroVertical;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Paquete de columna con las secciones fuera del rango vertical del jugador
 * como aire (y sin sus block entities). Sin rango en envío, vanilla intacto.
 */
@Mixin(ClientboundLevelChunkPacketData.class)
public abstract class MixinPaqueteChunk {

    @Shadow
    @Final
    private List<?> blockEntitiesData;

    @Inject(method = "calculateChunkSize", at = @At("HEAD"), cancellable = true)
    private static void minecraftlodmod$tamano(LevelChunk chunk, CallbackInfoReturnable<Integer> cir) {
        RangoSecciones rango = SincroVertical.rangoEnEnvio();
        if (rango == null) {
            return;
        }
        LevelChunkSection[] secciones = chunk.getSections();
        int total = 0;
        for (int i = 0; i < secciones.length; i++) {
            total += rango.contiene(chunk.getSectionYFromSectionIndex(i)) ? secciones[i].getSerializedSize()
                    : SincroVertical.tamanoOculta(secciones[i]);
        }
        cir.setReturnValue(total);
    }

    @Inject(method = "extractChunkData", at = @At("HEAD"), cancellable = true)
    private static void minecraftlodmod$datos(FriendlyByteBuf buf, LevelChunk chunk, CallbackInfo ci) {
        RangoSecciones rango = SincroVertical.rangoEnEnvio();
        if (rango == null) {
            return;
        }
        LevelChunkSection[] secciones = chunk.getSections();
        for (int i = 0; i < secciones.length; i++) {
            if (rango.contiene(chunk.getSectionYFromSectionIndex(i))) {
                secciones[i].write(buf);
            } else {
                SincroVertical.escribirOculta(buf, secciones[i]);
            }
        }
        ci.cancel();
    }

    @Inject(method = "<init>(Lnet/minecraft/world/level/chunk/LevelChunk;)V", at = @At("RETURN"))
    private void minecraftlodmod$sinBlockEntitiesOcultos(LevelChunk chunk, CallbackInfo ci) {
        RangoSecciones rango = SincroVertical.rangoEnEnvio();
        if (rango != null) {
            blockEntitiesData.removeIf(info -> !rango.contiene(((AccesoInfoBlockEntity) info).minecraftlodmod$y() >> 4));
        }
    }
}
