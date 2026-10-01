package com.example.minecraftlodmod.cubico.mixin;

import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Cada contenedor de paleta tenía su propio {@code Configuration} (24 B)
 * aunque solo existen unos pocos distintos (fábrica de paleta + bits): se
 * comparte uno por valor.
 */
@Mixin(PalettedContainer.Data.class)
public abstract class MixinDatosPaleta {

    private static final ConcurrentHashMap<PalettedContainer.Configuration<?>, PalettedContainer.Configuration<?>> minecraftlodmod$CONFIGURACIONES =
            new ConcurrentHashMap<>();

    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true)
    private static PalettedContainer.Configuration<?> minecraftlodmod$compartir(PalettedContainer.Configuration<?> c) {
        return c == null ? null : minecraftlodmod$CONFIGURACIONES.computeIfAbsent(c, k -> k);
    }
}
