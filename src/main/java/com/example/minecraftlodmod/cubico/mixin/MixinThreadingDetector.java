package com.example.minecraftlodmod.cubico.mixin;

import net.minecraft.util.ThreadingDetector;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Detector de acceso desde varios hilos sin candados propios: vanilla arma un
 * {@code Semaphore} y un {@code ReentrantLock} (con sus objetos internos, ~96 B)
 * por cada contenedor de paleta de cada sección, cliente y servidor. Acá el
 * "dueño" se guarda en un campo y se protege con el monitor del propio
 * detector (sin memoria extra). Mismo error que vanilla ("Accessing
 * PalettedContainer from multiple threads", con la traza del otro hilo).
 * No se aplica si está FerriteCore ({@code PluginCubico}).
 */
@Mixin(ThreadingDetector.class)
public abstract class MixinThreadingDetector {

    @Unique
    private static final Semaphore minecraftlodmod$SEMAFORO = new Semaphore(1);
    @Unique
    private static final ReentrantLock minecraftlodmod$CANDADO = new ReentrantLock();

    @Shadow
    @Final
    private String name;

    @Unique
    private Thread minecraftlodmod$duenio;

    @Redirect(method = "<init>", at = @At(value = "NEW", target = "java/util/concurrent/Semaphore"))
    private Semaphore minecraftlodmod$sinSemaforo(int permisos) {
        return minecraftlodmod$SEMAFORO; // nunca se usa: checkAndLock/checkAndUnlock están reescritos
    }

    @Redirect(method = "<init>", at = @At(value = "NEW", target = "java/util/concurrent/locks/ReentrantLock"))
    private ReentrantLock minecraftlodmod$sinCandado() {
        return minecraftlodmod$CANDADO;
    }

    /**
     * @author Minecraft LOD
     * @reason mismo chequeo sin Semaphore ni ReentrantLock por contenedor
     */
    @Overwrite
    public void checkAndLock() {
        Thread actual = Thread.currentThread();
        Thread otro;
        synchronized (this) {
            otro = minecraftlodmod$duenio;
            if (otro == null) {
                minecraftlodmod$duenio = actual;
                return;
            }
        }
        throw ThreadingDetector.makeThreadingException(name, otro);
    }

    /**
     * @author Minecraft LOD
     * @reason mismo chequeo sin Semaphore ni ReentrantLock por contenedor
     */
    @Overwrite
    public void checkAndUnlock() {
        synchronized (this) {
            minecraftlodmod$duenio = null;
        }
    }
}
