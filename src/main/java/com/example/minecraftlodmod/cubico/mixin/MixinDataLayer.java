package com.example.minecraftlodmod.cubico.mixin;

import com.example.minecraftlodmod.cubico.LuzComprimible;
import com.example.minecraftlodmod.cubico.SeccionesComprimidas;
import net.minecraft.world.level.chunk.DataLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Luz de secciones lejanas guardada comprimida ({@code SeccionesComprimidas}).
 * {@code get} y {@code copy} se reescriben para leer {@code data} una sola vez
 * (en una local): así una compresión en otro hilo nunca los deja a mitad de
 * camino. Solo se comprimen capas visibles, que el motor de luz no escribe
 * (copia antes de escribir); igual, cualquier escritura descarta lo comprimido.
 */
@Mixin(DataLayer.class)
public abstract class MixinDataLayer implements LuzComprimible {

    @Unique
    private static final ThreadLocal<Deflater> minecraftlodmod$COMPRESOR = ThreadLocal.withInitial(() -> new Deflater(1));
    @Unique
    private static final ThreadLocal<Inflater> minecraftlodmod$DESCOMPRESOR = ThreadLocal.withInitial(Inflater::new);

    @Shadow
    protected byte[] data;
    @Shadow
    private int defaultValue;

    @Unique
    private volatile byte[] minecraftlodmod$comprimido;

    /**
     * @author Minecraft LOD
     * @reason leer {@code data} una sola vez y descomprimir si hace falta
     */
    @Overwrite
    private int get(int indice) {
        byte[] d = this.data;
        if (d == null) {
            if (minecraftlodmod$comprimido == null) {
                return this.defaultValue;
            }
            d = minecraftlodmod$descomprimir();
        }
        return d[indice >> 1] >> 4 * (indice & 1) & 15;
    }

    /**
     * @author Minecraft LOD
     * @reason leer {@code data} una sola vez y descomprimir si hace falta
     */
    @Overwrite
    public DataLayer copy() {
        byte[] d = this.data;
        if (d == null && minecraftlodmod$comprimido != null) {
            d = minecraftlodmod$descomprimir();
        }
        return d == null ? new DataLayer(this.defaultValue) : new DataLayer(d.clone());
    }

    @Inject(method = "getData", at = @At("HEAD"))
    private void minecraftlodmod$antesDeDatos(CallbackInfoReturnable<byte[]> cir) {
        // getData se usa para escribir (set) y para paquetes/guardado: se descomprime y deja de valer lo comprimido.
        if (minecraftlodmod$comprimido != null) {
            minecraftlodmod$descomprimir();
            minecraftlodmod$comprimido = null;
        }
    }

    @Inject(method = "fill", at = @At("HEAD"))
    private void minecraftlodmod$antesDeLlenar(int valor, CallbackInfo ci) {
        minecraftlodmod$comprimido = null;
    }

    @Inject(method = {"isDefinitelyHomogenous", "isDefinitelyFilledWith"}, at = @At("HEAD"), cancellable = true)
    private void minecraftlodmod$noEsUniforme(CallbackInfoReturnable<Boolean> cir) {
        if (minecraftlodmod$comprimido != null) {
            cir.setReturnValue(false);
        }
    }

    @Unique
    private synchronized byte[] minecraftlodmod$descomprimir() {
        byte[] d = this.data;
        if (d != null) {
            return d;
        }
        byte[] c = minecraftlodmod$comprimido;
        if (c == null) {
            return this.data != null ? this.data : new byte[DataLayer.SIZE];
        }
        Inflater i = minecraftlodmod$DESCOMPRESOR.get();
        i.reset();
        i.setInput(c);
        d = new byte[DataLayer.SIZE];
        try {
            int n = 0;
            while (n < d.length && !i.finished()) {
                n += i.inflate(d, n, d.length - n);
            }
        } catch (DataFormatException e) {
            throw new IllegalStateException("Luz comprimida dañada", e);
        }
        this.data = d;
        SeccionesComprimidas.ESTADISTICAS.luzDescomprimida();
        return d;
    }

    @Override
    public synchronized int minecraftlodmod$comprimir() {
        byte[] d = this.data;
        if (d == null) {
            byte[] c = minecraftlodmod$comprimido;
            return c == null ? -1 : c.length;
        }
        if (minecraftlodmod$comprimido == null) {
            Deflater z = minecraftlodmod$COMPRESOR.get();
            z.reset();
            z.setInput(d);
            z.finish();
            byte[] salida = new byte[DataLayer.SIZE + 64];
            int n = z.deflate(salida);
            minecraftlodmod$comprimido = java.util.Arrays.copyOf(salida, n);
        }
        this.data = null;
        return minecraftlodmod$comprimido.length;
    }

    @Override
    public boolean minecraftlodmod$estaComprimida() {
        return this.data == null && minecraftlodmod$comprimido != null;
    }
}
