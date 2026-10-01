package com.example.minecraftlodmod.tierra;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lee un {@code .lodt} con una caché de teselas decodificadas con tope de
 * bytes (mismo criterio que {@code storage/BoundedRegionCache}: el tope es en
 * bytes, no en cantidad). Thread-safe: la generación consulta desde muchos
 * hilos. Las lecturas son posicionales ({@code FileChannel.read(buf, pos)}),
 * sin candado del canal; dos hilos que piden la misma tesela que no está
 * pueden decodificarla los dos (pasa poco, y es más barato que bloquear).
 *
 * El desalojo es LRU aproximado: cada tesela guarda el último "tic" en que se
 * usó y al pasarse del tope se sacan las de tic más viejo. Con tesela de 256
 * (192 KB) y un tope de 64 MB son ~340 teselas: recorrerlas al insertar es
 * nada al lado de leer y descomprimir.
 */
public final class LectorLodt implements AutoCloseable {

    /** Tesela decodificada: elevación en metros y clase de bioma, lado × lado, fila 0 al norte. */
    public static final class Tesela {
        public final short[] elevacion;
        public final byte[] bioma;
        volatile long ultimoUso;

        Tesela(int lado) {
            elevacion = new short[lado * lado];
            bioma = new byte[lado * lado];
        }

        long bytes() {
            return elevacion.length * 2L + bioma.length + 64;
        }
    }

    public final FormatoLodt.Cabecera cabecera;
    private final FileChannel canal;
    private final long[] offsets;
    private final int[] tamanos;
    private final long topeBytes;
    private final ConcurrentHashMap<Integer, Tesela> cache = new ConcurrentHashMap<>();
    private final AtomicLong bytesUsados = new AtomicLong();
    private final AtomicLong tic = new AtomicLong();
    private final Object candadoDesalojo = new Object();

    private LectorLodt(FileChannel canal, FormatoLodt.Cabecera cabecera, long[] offsets, int[] tamanos, long topeBytes) {
        this.canal = canal;
        this.cabecera = cabecera;
        this.offsets = offsets;
        this.tamanos = tamanos;
        this.topeBytes = topeBytes;
    }

    public static LectorLodt abrir(Path archivo, long topeBytes) throws IOException {
        if (topeBytes <= 0) throw new IllegalArgumentException("El tope de bytes debe ser positivo");
        FileChannel canal = FileChannel.open(archivo, StandardOpenOption.READ);
        try {
            FormatoLodt.Cabecera cab = FormatoLodt.Cabecera.leer(leer(canal, 0, FormatoLodt.BYTES_CABECERA));
            int n = cab.cantidadTeselas();
            ByteBuffer indice = leer(canal, FormatoLodt.BYTES_CABECERA, n * FormatoLodt.BYTES_ENTRADA_INDICE);
            long[] offsets = new long[n];
            int[] tamanos = new int[n];
            long largo = canal.size();
            for (int i = 0; i < n; i++) {
                offsets[i] = indice.getLong();
                tamanos[i] = indice.getInt();
                if (offsets[i] < cab.bytesHastaTeselas() || tamanos[i] <= 0 || offsets[i] + tamanos[i] > largo) {
                    throw new IOException("Índice .lodt fuera del archivo (tesela " + i + ")");
                }
            }
            return new LectorLodt(canal, cab, offsets, tamanos, topeBytes);
        } catch (IOException | RuntimeException e) {
            canal.close();
            throw e;
        }
    }

    /** Elevación en metros de la muestra (col, fila); col y fila ya dentro de la grilla. */
    public short elevacion(int col, int fila) {
        int lado = cabecera.lado();
        int tx = col / lado, tz = fila / lado;
        return tesela(tx, tz).elevacion[(fila - tz * lado) * lado + (col - tx * lado)];
    }

    public byte bioma(int col, int fila) {
        int lado = cabecera.lado();
        int tx = col / lado, tz = fila / lado;
        return tesela(tx, tz).bioma[(fila - tz * lado) * lado + (col - tx * lado)];
    }

    /** Tesela (tx, tz), leyéndola si no está en caché. Errores de disco salen como {@link UncheckedIOException}. */
    public Tesela tesela(int tx, int tz) {
        int indice = tz * cabecera.teselasX() + tx;
        Tesela t = cache.get(indice);
        if (t == null) {
            t = cargar(indice);
        }
        t.ultimoUso = tic.incrementAndGet();
        return t;
    }

    private Tesela cargar(int indice) {
        Tesela t = new Tesela(cabecera.lado());
        try {
            FormatoLodt.decodificarTesela(leer(canal, offsets[indice], tamanos[indice]), cabecera.lado(), t.elevacion, t.bioma);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Tesela previa = cache.putIfAbsent(indice, t);
        if (previa != null) return previa;
        if (bytesUsados.addAndGet(t.bytes()) > topeBytes) desalojar(indice);
        return t;
    }

    private void desalojar(int recien) {
        synchronized (candadoDesalojo) {
            while (bytesUsados.get() > topeBytes && cache.size() > 1) {
                int peor = -1;
                long peorTic = Long.MAX_VALUE;
                for (var e : cache.entrySet()) {
                    if (e.getKey() != recien && e.getValue().ultimoUso < peorTic) {
                        peorTic = e.getValue().ultimoUso;
                        peor = e.getKey();
                    }
                }
                if (peor < 0) return;
                Tesela sacada = cache.remove(peor);
                if (sacada != null) bytesUsados.addAndGet(-sacada.bytes());
            }
        }
    }

    public int teselasEnCache() {
        return cache.size();
    }

    public long bytesEnCache() {
        return bytesUsados.get();
    }

    private static ByteBuffer leer(FileChannel canal, long pos, int n) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(n);
        while (b.hasRemaining()) {
            if (canal.read(b, pos + b.position()) < 0) throw new IOException(".lodt truncado");
        }
        return b.flip();
    }

    @Override
    public void close() throws IOException {
        canal.close();
        cache.clear();
        bytesUsados.set(0);
    }
}
