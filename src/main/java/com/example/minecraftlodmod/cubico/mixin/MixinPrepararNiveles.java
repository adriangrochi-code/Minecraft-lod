package com.example.minecraftlodmod.cubico.mixin;

import com.example.minecraftlodmod.generation.PregeneracionInicial;
import net.minecraft.Util;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.progress.ChunkProgressListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pregeneración al entrar a un mundo ({@link PregeneracionInicial}): al final de la
 * preparación del área de aparición, antes de cerrar la pantalla de carga, con el
 * mismo bucle de espera que usa vanilla para el spawn.
 */
@Mixin(MinecraftServer.class)
public abstract class MixinPrepararNiveles {

    @Shadow
    protected long nextTickTimeNanos;

    @Shadow
    protected abstract void waitUntilNextTick();

    /** Como el bucle del spawn de vanilla: 10 ms de tareas (tickets, generación, luz) por vuelta. */
    private static final long ESPERA_NANOS = 10_000_000L;

    @Inject(method = "prepareLevels", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/progress/ChunkProgressListener;stop()V"))
    private void minecraftlodmod$pregeneracionInicial(ChunkProgressListener progreso, CallbackInfo ci) {
        PregeneracionInicial.alEntrar((MinecraftServer) (Object) this, this::minecraftlodmod$esperar);
    }

    private void minecraftlodmod$esperar() {
        nextTickTimeNanos = Util.getNanos() + ESPERA_NANOS;
        waitUntilNextTick();
    }
}
