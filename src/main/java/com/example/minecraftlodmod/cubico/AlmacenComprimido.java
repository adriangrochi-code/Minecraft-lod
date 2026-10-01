package com.example.minecraftlodmod.cubico;

import net.minecraft.util.BitStorage;
import net.minecraft.util.SimpleBitStorage;

import java.nio.ByteBuffer;
import java.util.function.IntConsumer;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Datos de bloques de una sección guardados comprimidos (Deflate) mientras
 * nadie los usa ({@link SeccionesComprimidas}). La primera lectura los
 * descomprime y quedan así hasta que el barrido los vuelve a comprimir.
 *
 * Hilos: lee cualquiera (el LOD lee secciones desde sus hilos); escribe y
 * comprime solo el hilo del servidor. Cada lectura toma la versión
 * descomprimida en una variable local, así que comprimir en el medio no la
 * rompe. Mientras está descomprimida, la versión comprimida se conserva
 * hasta que haya una escritura (volver a comprimir sin cambios es gratis).
 */
public final class AlmacenComprimido implements BitStorage {

    private static final ThreadLocal<Deflater> COMPRESOR = ThreadLocal.withInitial(() -> new Deflater(1));
    private static final ThreadLocal<Inflater> DESCOMPRESOR = ThreadLocal.withInitial(Inflater::new);

    private final int bits;
    private final int tamano;
    private final int largoRaw;
    private volatile SimpleBitStorage descomprimido;
    private volatile byte[] comprimido;

    public AlmacenComprimido(SimpleBitStorage original) {
        this.bits = original.getBits();
        this.tamano = original.getSize();
        this.largoRaw = original.getRaw().length;
        this.descomprimido = original;
    }

    /** Hilo del servidor. Devuelve los bytes que ocupa ahora (comprimido). */
    public int comprimir() {
        SimpleBitStorage d = descomprimido;
        if (d != null) {
            if (comprimido == null) {
                comprimido = deflate(d.getRaw());
            }
            descomprimido = null;
        }
        return comprimido.length;
    }

    public boolean estaComprimido() {
        return descomprimido == null;
    }

    private SimpleBitStorage leer() {
        SimpleBitStorage d = descomprimido;
        return d != null ? d : descomprimir();
    }

    private synchronized SimpleBitStorage descomprimir() {
        SimpleBitStorage d = descomprimido;
        if (d == null) {
            d = new SimpleBitStorage(bits, tamano, inflate(comprimido, largoRaw));
            descomprimido = d;
            SeccionesComprimidas.ESTADISTICAS.descomprimidas.increment();
        }
        return d;
    }

    /** Para escribir: descomprime y descarta la versión comprimida (deja de valer). */
    private SimpleBitStorage paraEscribir() {
        SimpleBitStorage d = leer();
        comprimido = null;
        return d;
    }

    @Override
    public int getAndSet(int indice, int valor) {
        return paraEscribir().getAndSet(indice, valor);
    }

    @Override
    public void set(int indice, int valor) {
        paraEscribir().set(indice, valor);
    }

    @Override
    public int get(int indice) {
        return leer().get(indice);
    }

    @Override
    public long[] getRaw() {
        // Guardar el chunk o armar el paquete: sin dejarlo descomprimido.
        SimpleBitStorage d = descomprimido;
        if (d != null) {
            return d.getRaw();
        }
        byte[] c = comprimido;
        return c != null ? inflate(c, largoRaw) : leer().getRaw();
    }

    @Override
    public int getSize() {
        return tamano;
    }

    @Override
    public int getBits() {
        return bits;
    }

    @Override
    public void getAll(IntConsumer consumidor) {
        leer().getAll(consumidor);
    }

    @Override
    public void unpack(int[] destino) {
        leer().unpack(destino);
    }

    @Override
    public BitStorage copy() {
        return new SimpleBitStorage(bits, tamano, getRaw().clone());
    }

    // ------------------------------------------------------------------ Deflate

    static byte[] deflate(long[] raw) {
        ByteBuffer b = ByteBuffer.allocate(raw.length * 8);
        b.asLongBuffer().put(raw);
        Deflater d = COMPRESOR.get();
        d.reset();
        d.setInput(b.array());
        d.finish();
        byte[] salida = new byte[raw.length * 8 + 64];
        int n = d.deflate(salida);
        return java.util.Arrays.copyOf(salida, n);
    }

    static long[] inflate(byte[] comprimido, int largo) {
        Inflater i = DESCOMPRESOR.get();
        i.reset();
        i.setInput(comprimido);
        byte[] bytes = new byte[largo * 8];
        try {
            int n = 0;
            while (n < bytes.length && !i.finished()) {
                n += i.inflate(bytes, n, bytes.length - n);
            }
        } catch (DataFormatException e) {
            throw new IllegalStateException("Sección comprimida dañada", e);
        }
        long[] raw = new long[largo];
        ByteBuffer.wrap(bytes).asLongBuffer().get(raw);
        return raw;
    }
}
