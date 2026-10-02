package com.example.minecraftlodmod.render.mixin;

import com.example.minecraftlodmod.config.DistanciaVanilla;
import net.minecraft.client.server.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Modo híbrido: el servidor integrado toma su distancia de vista de la opción del
 * cliente; acotada igual, carga y manda menos chunks (ver {@link DistanciaVanilla}).
 */
@Mixin(IntegratedServer.class)
public abstract class MixinServidorIntegrado {

    @ModifyArg(method = "tickServer", at = @At(value = "INVOKE", target = "Ljava/lang/Math;max(II)I", ordinal = 0), index = 1)
    private int minecraftlodmod$distanciaDeVista(int pedida) {
        int tope = DistanciaVanilla.topeActual();
        return tope == Integer.MAX_VALUE ? pedida : DistanciaVanilla.acotar(pedida, tope);
    }
}
