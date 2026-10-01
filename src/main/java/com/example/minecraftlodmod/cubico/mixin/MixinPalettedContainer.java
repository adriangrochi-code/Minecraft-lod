package com.example.minecraftlodmod.cubico.mixin;

import com.example.minecraftlodmod.cubico.Compartible;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Marca de "compartido" y guarda contra escrituras: un contenedor compartido
 * entre muchas secciones nunca se modifica (la sección se copia antes en
 * {@code MixinLevelChunkCompartido}). Si algún código escribe directo, falla
 * con un mensaje claro en vez de cambiar todas las secciones que lo usan.
 */
@Mixin(PalettedContainer.class)
public abstract class MixinPalettedContainer implements Compartible {

    @Unique
    private boolean minecraftlodmod$compartido;

    @Override
    public boolean minecraftlodmod$compartido() {
        return minecraftlodmod$compartido;
    }

    @Override
    public void minecraftlodmod$marcarCompartido() {
        minecraftlodmod$compartido = true;
    }

    @Unique
    private void minecraftlodmod$proteger() {
        if (minecraftlodmod$compartido) {
            throw new IllegalStateException("Minecraft LOD: escritura directa en una sección uniforme compartida."
                    + " Apagá cubico.compartirSeccionesUniformes en minecraftlodmod-server.toml (conflicto con otro mod).");
        }
    }

    @Inject(method = "getAndSet", at = @At("HEAD"))
    private void minecraftlodmod$getAndSet(int x, int y, int z, Object v, CallbackInfoReturnable<Object> cir) {
        minecraftlodmod$proteger();
    }

    @Inject(method = "getAndSetUnchecked", at = @At("HEAD"))
    private void minecraftlodmod$getAndSetUnchecked(int x, int y, int z, Object v, CallbackInfoReturnable<Object> cir) {
        minecraftlodmod$proteger();
    }

    @Inject(method = "set", at = @At("HEAD"))
    private void minecraftlodmod$set(int x, int y, int z, Object v, CallbackInfo ci) {
        minecraftlodmod$proteger();
    }

    @Inject(method = "read", at = @At("HEAD"))
    private void minecraftlodmod$read(FriendlyByteBuf buf, CallbackInfo ci) {
        minecraftlodmod$proteger();
    }
}
