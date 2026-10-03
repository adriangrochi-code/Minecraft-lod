package com.example.minecraftlodmod.storage;

import java.util.Arrays;
import java.nio.ByteBuffer;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Deflate POR NODO para lo que se guarda en memoria y en disco.
 *
 * Resuelve la contradicción anotada de la sección 5 ("Deflate a nivel de
 * archivo" vs. lectura parcial por tabla de offsets): comprimiendo cada nodo
 * por separado, la tabla de offsets sigue apuntando a un bloque que se lee y
 * descomprime solo, sin tocar el resto del archivo. Deflate sobre el RLE
 * aprovecha lo que el RLE no ve (colores que se repiten no contiguos, luz,
 * alturas en secuencia).
 *
 * Formato: largo original (4 bytes) | datos deflate. Nivel rápido: se
 * comprime en los hilos de generación, en cada nodo nuevo.
 */
public final class CompresionNodos {

    private static final ThreadLocal<Deflater> DEFLATER = ThreadLocal.withInitial(() -> new Deflater(Deflater.BEST_SPEED));
    private static final ThreadLocal<Inflater> INFLATER = ThreadLocal.withInitial(Inflater::new);
    /** Salida de {@link #comprimir}, una por hilo; crece si un nodo no entra. */
    private static final ThreadLocal<byte[]> SALIDA = ThreadLocal.withInitial(() -> new byte[64 * 1024]);

    /** Tope al descomprimir: un nodo de nivel 0 sin ninguna corrida ocupa ~40 KB. */
    static final int MAX_ORIGINAL = 1 << 20;

    private CompresionNodos() {
    }

    public static byte[] comprimir(byte[] datos) {
        Deflater deflater = DEFLATER.get();
        deflater.reset();
        deflater.setInput(datos);
        deflater.finish();
        // Directo a un buffer del hilo que se reusa (antes: un bloque de 4 KB y un
        // ByteArrayOutputStream nuevos por nodo); solo la copia final es por nodo.
        byte[] salida = SALIDA.get();
        salida[0] = (byte) (datos.length >>> 24);
        salida[1] = (byte) (datos.length >>> 16);
        salida[2] = (byte) (datos.length >>> 8);
        salida[3] = (byte) datos.length;
        int escritos = 4;
        while (!deflater.finished()) {
            if (escritos == salida.length) {
                salida = Arrays.copyOf(salida, salida.length * 2);
                SALIDA.set(salida);
            }
            escritos += deflater.deflate(salida, escritos, salida.length - escritos);
        }
        return Arrays.copyOf(salida, escritos);
    }

    public static byte[] descomprimir(byte[] comprimido) {
        int largo = ByteBuffer.wrap(comprimido, 0, 4).getInt();
        if (largo < 0 || largo > MAX_ORIGINAL) {
            throw new IllegalArgumentException("Nodo comprimido con largo inválido: " + largo);
        }
        Inflater inflater = INFLATER.get();
        inflater.reset();
        inflater.setInput(comprimido, 4, comprimido.length - 4);
        byte[] datos = new byte[largo];
        try {
            int leidos = 0;
            while (leidos < largo) {
                int n = inflater.inflate(datos, leidos, largo - leidos);
                if (n == 0 && (inflater.finished() || inflater.needsInput())) {
                    break;
                }
                leidos += n;
            }
            if (leidos != largo) {
                throw new IllegalArgumentException("Nodo comprimido truncado: " + leidos + " de " + largo);
            }
            // Consumir el cierre del stream: ahí está el checksum (adler32). Sin
            // esto, datos corruptos del largo esperado pasarían sin error.
            if (!inflater.finished() && (inflater.inflate(new byte[1]) != 0 || !inflater.finished())) {
                throw new IllegalArgumentException("Nodo comprimido con datos de más o sin cierre");
            }
        } catch (DataFormatException e) {
            throw new IllegalArgumentException("Nodo comprimido corrupto", e);
        }
        return datos;
    }
}
