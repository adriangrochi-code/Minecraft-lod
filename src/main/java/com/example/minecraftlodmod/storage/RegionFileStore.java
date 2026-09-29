package com.example.minecraftlodmod.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cache en disco de nodos ya serializados (sección 5 del documento de
 * arquitectura). Por región y dimensión, dos archivos:
 *
 *   r.X.Z.idx        generación (8 bytes) | {@link RegionHeader} (tabla de offsets)
 *   r.X.Z.GEN.mlod   nodos comprimidos uno detrás de otro, APPEND-ONLY
 *
 * Leer un nodo = índice (cacheado en RAM) + UNA lectura posicional del
 * tamaño exacto del nodo (la lectura parcial que motiva la tabla).
 *
 * Escritura diferida (write-behind): {@link #guardar} deja el nodo en un
 * mapa de pendientes; un hilo propio los baja a disco en lote cada
 * {@code periodoEscrituraMs}, o antes si se supera el presupuesto de RAM.
 * Cada lote solo AGREGA sus nodos al final del archivo de datos y reescribe
 * el índice (chico) con un move atómico: el costo es proporcional a lo
 * nuevo, no al tamaño de la región. Un nodo reescrito deja su versión vieja
 * como bytes muertos; cuando son más de la mitad del archivo, se compacta a
 * una generación nueva ({@code GEN+1}), se cambia el índice y recién ahí se
 * borra la vieja. Un crash en cualquier punto deja el índice anterior
 * apuntando a datos válidos (a lo sumo, bytes huérfanos al final).
 *
 * Invalidación: el índice lleva el {@code hashFuente} con el que se generó.
 * Si no coincide con el del store (cambió el algoritmo de LOD, o la
 * seed/versión del mundo que el caller mezcle en el hash), la región se
 * ignora como si no existiera y se empieza de cero en el próximo guardado.
 *
 * Thread-safe: pensado para usarse desde los hilos de {@code generation/}.
 */
public final class RegionFileStore implements AutoCloseable {

    /** Identifica un archivo de región. */
    public record ClaveRegion(byte dimensionId, int regionX, int regionZ) {
    }

    private final Path directorioBase;
    private final long hashFuente;

    /** Nodos guardados que todavía no se bajaron a disco. */
    private final ConcurrentHashMap<ClaveRegion, ConcurrentHashMap<Long, byte[]>> pendientes = new ConcurrentHashMap<>();
    /** Nodos que se están escribiendo ahora: siguen visibles para lectura hasta que el archivo quede en disco. */
    private final ConcurrentHashMap<ClaveRegion, Map<Long, byte[]>> enEscritura = new ConcurrentHashMap<>();
    /** Headers ya leídos y válidos; una región sin archivo (o con archivo inválido) no tiene entrada. */
    private final ConcurrentHashMap<ClaveRegion, Indice> headers = new ConcurrentHashMap<>();

    /** Índice cargado de una región: la tabla y qué generación de datos describe. */
    private record Indice(RegionHeader header, long generacion) {
    }

    /** No compactar archivos chicos: el espacio muerto no justifica reescribirlos. */
    static final long COMPACTAR_DESDE_BYTES = 1L << 20;
    private final ConcurrentHashMap<ClaveRegion, Object> candados = new ConcurrentHashMap<>();

    private final ScheduledExecutorService hiloEscritura;

    /** Presupuesto por defecto de RAM (constructor sin presupuesto: tests y usos simples). */
    public static final long PRESUPUESTO_RAM_POR_DEFECTO = 64L * 1024 * 1024;

    /**
     * Límite de bytes pendientes (comprimidos) antes de forzar un vaciado a
     * disco sin esperar al período: la RAM que el jugador eligió para LOD
     * manda, no el reloj.
     */
    private final long limitePendientes;
    private final AtomicLong bytesPendientes = new AtomicLong();
    private final AtomicBoolean vaciadoUrgentePedido = new AtomicBoolean();
    /** Nodos leídos de disco, todavía comprimidos; acceso sincronizado sobre el propio cache. */
    private final BoundedRegionCache cacheLectura;

    /**
     * @param periodoEscrituraMs cada cuánto se bajan los pendientes a disco;
     *                           0 o menos desactiva el hilo (solo {@link #vaciar()} manual)
     */
    public RegionFileStore(Path directorioBase, long hashFuente, long periodoEscrituraMs) {
        this(directorioBase, hashFuente, periodoEscrituraMs, PRESUPUESTO_RAM_POR_DEFECTO);
    }

    /**
     * @param presupuestoRamBytes RAM total para datos de LOD (el slider
     *                            "RAM para LOD"): una cuarta parte para lo
     *                            pendiente de escribir (al superarla se baja
     *                            a disco en el acto), el resto para el cache
     *                            de lectura LRU. Todo se guarda comprimido
     *                            ({@link CompresionNodos}), así que entra
     *                            varias veces más terreno que en crudo.
     */
    public RegionFileStore(Path directorioBase, long hashFuente, long periodoEscrituraMs, long presupuestoRamBytes) {
        if (presupuestoRamBytes < 4) {
            throw new IllegalArgumentException("Presupuesto de RAM inválido: " + presupuestoRamBytes);
        }
        this.limitePendientes = presupuestoRamBytes / 4;
        this.cacheLectura = new BoundedRegionCache(presupuestoRamBytes - limitePendientes);
        this.directorioBase = directorioBase;
        this.hashFuente = hashFuente;
        if (periodoEscrituraMs > 0) {
            hiloEscritura = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread hilo = new Thread(r, "LOD-EscrituraRegiones");
                hilo.setDaemon(true);
                return hilo;
            });
            hiloEscritura.scheduleWithFixedDelay(this::vaciarSinExcepciones,
                    periodoEscrituraMs, periodoEscrituraMs, TimeUnit.MILLISECONDS);
        } else {
            hiloEscritura = null;
        }
    }

    public void guardar(ClaveRegion region, long claveNodo, byte[] datos) {
        // Vacío = marca sin contenido (ej. "chunk generado"): no vale la pena comprimir.
        byte[] comprimido = datos.length == 0 ? datos : CompresionNodos.comprimir(datos);
        long[] reemplazado = {0};
        // compute (no computeIfAbsent + put): atómico frente al remove de escribirRegion,
        // así un nodo nunca cae en un mapa que ya se está bajando a disco.
        pendientes.compute(region, (r, nodos) -> {
            ConcurrentHashMap<Long, byte[]> destino = nodos != null ? nodos : new ConcurrentHashMap<>();
            byte[] anterior = destino.put(claveNodo, comprimido);
            reemplazado[0] = anterior == null ? 0 : anterior.length;
            return destino;
        });
        long clave = claveCache(region, claveNodo);
        if (clave != SIN_CACHE) {
            synchronized (cacheLectura) {
                cacheLectura.quitar(clave);
            }
        }
        long total = bytesPendientes.addAndGet(comprimido.length - reemplazado[0]);
        if (total > limitePendientes && hiloEscritura != null && vaciadoUrgentePedido.compareAndSet(false, true)) {
            hiloEscritura.execute(() -> {
                vaciadoUrgentePedido.set(false);
                vaciarSinExcepciones();
            });
        }
    }

    /** @return los bytes del nodo, o null si no está ni pendiente ni en disco. */
    public byte[] leer(ClaveRegion region, long claveNodo) {
        byte[] comprimido = leerComprimido(region, claveNodo);
        if (comprimido == null) {
            return null;
        }
        return comprimido.length == 0 ? comprimido : CompresionNodos.descomprimir(comprimido);
    }

    private byte[] leerComprimido(ClaveRegion region, long claveNodo) {
        byte[] enMemoria = buscarEnMemoria(region, claveNodo);
        if (enMemoria != null) {
            return enMemoria;
        }
        long clave = claveCache(region, claveNodo);
        if (clave != SIN_CACHE) {
            synchronized (cacheLectura) {
                byte[] cacheado = cacheLectura.obtener(clave);
                if (cacheado != null) {
                    return cacheado;
                }
            }
        }
        synchronized (candado(region)) {
            // Re-chequear: una escritura pudo terminar mientras esperábamos el candado.
            enMemoria = buscarEnMemoria(region, claveNodo);
            if (enMemoria != null) {
                return enMemoria;
            }
            Indice indice = indiceDe(region);
            if (indice == null) {
                return null;
            }
            long[] ubicacion = indice.header().buscarNodo(claveNodo);
            if (ubicacion == null) {
                return null;
            }
            byte[] deDisco = leerDeDisco(region, indice.generacion(), ubicacion[0], (int) ubicacion[1]);
            if (clave != SIN_CACHE) {
                synchronized (cacheLectura) {
                    cacheLectura.poner(clave, deDisco);
                }
            }
            return deDisco;
        }
    }

    /** Clave que no entra en {@link #claveCache}: ese nodo simplemente no se cachea. */
    static final long SIN_CACHE = -1L;

    /**
     * Clave global para el cache (un solo presupuesto para todas las
     * regiones): dimensión (8 bits) | región X y Z con signo (14 bits c/u,
     * ±8192 regiones = ±4 millones de bloques) | clave de nodo (26 bits,
     * ver {@code SectionExtractor.claveNodo}). Fuera de ese rango, o con una
     * clave de nodo más ancha, devuelve {@link #SIN_CACHE}.
     */
    static long claveCache(ClaveRegion region, long claveNodo) {
        int rx = region.regionX(), rz = region.regionZ();
        if (rx < -8192 || rx >= 8192 || rz < -8192 || rz >= 8192 || claveNodo < 0 || claveNodo >= (1L << 26)) {
            return SIN_CACHE;
        }
        return ((long) (region.dimensionId() & 0xFF) << 54) | ((long) (rx & 0x3FFF) << 40)
                | ((long) (rz & 0x3FFF) << 26) | claveNodo;
    }

    public boolean contiene(ClaveRegion region, long claveNodo) {
        if (buscarEnMemoria(region, claveNodo) != null) {
            return true;
        }
        synchronized (candado(region)) {
            Indice indice = indiceDe(region);
            return indice != null && indice.header().tieneNodo(claveNodo);
        }
    }

    /**
     * Borra una región entera (disco y pendientes) — para la invalidación
     * ante cambios de bloques de la sección 10.
     */
    public void invalidarRegion(ClaveRegion region) {
        synchronized (candado(region)) {
            ConcurrentHashMap<Long, byte[]> descartados = pendientes.remove(region);
            if (descartados != null) {
                bytesPendientes.addAndGet(-bytesDe(descartados));
            }
            Indice indice = indiceDe(region);
            headers.remove(region);
            synchronized (cacheLectura) {
                cacheLectura.limpiar(); // raro (cambio de bloques): más simple que filtrar por región
            }
            try {
                Files.deleteIfExists(archivoDe(region));
                if (indice != null) {
                    Files.deleteIfExists(datosDe(region, indice.generacion()));
                }
            } catch (IOException e) {
                throw new UncheckedIOException("No se pudo borrar " + archivoDe(region), e);
            }
        }
    }

    /** Baja a disco todos los pendientes ahora, en el hilo que llama. */
    public void vaciar() throws IOException {
        IOException primerError = null;
        for (ClaveRegion region : pendientes.keySet()) {
            try {
                escribirRegion(region);
            } catch (IOException e) {
                if (primerError == null) {
                    primerError = e;
                } else {
                    primerError.addSuppressed(e);
                }
            }
        }
        if (primerError != null) {
            throw primerError;
        }
    }

    public int regionesPendientes() {
        return pendientes.size();
    }

    /** Bytes (comprimidos) esperando ir a disco. */
    public long bytesPendientes() {
        return bytesPendientes.get();
    }

    /** Bytes (comprimidos) en el cache de lectura. */
    public long bytesEnCache() {
        synchronized (cacheLectura) {
            return cacheLectura.bytesUsados();
        }
    }

    private static long bytesDe(Map<Long, byte[]> nodos) {
        long total = 0;
        for (byte[] datos : nodos.values()) {
            total += datos.length;
        }
        return total;
    }

    /** El índice de la región: es el archivo que la hace existir. */
    Path archivoDe(ClaveRegion region) {
        return carpetaDe(region).resolve("r." + region.regionX() + "." + region.regionZ() + ".idx");
    }

    Path datosDe(ClaveRegion region, long generacion) {
        return carpetaDe(region).resolve("r." + region.regionX() + "." + region.regionZ() + "." + generacion + ".mlod");
    }

    private Path carpetaDe(ClaveRegion region) {
        return directorioBase.resolve("dim" + (region.dimensionId() & 0xFF));
    }

    private void escribirRegion(ClaveRegion region) throws IOException {
        synchronized (candado(region)) {
            ConcurrentHashMap<Long, byte[]> nuevos = pendientes.remove(region);
            if (nuevos == null || nuevos.isEmpty()) {
                return;
            }
            bytesPendientes.addAndGet(-bytesDe(nuevos));
            enEscritura.put(region, nuevos);
            try {
                Indice existente = indiceDe(region);
                long generacion = existente != null ? existente.generacion() : 0;
                RegionHeader header = new RegionHeader(
                        region.regionX(), region.regionZ(), region.dimensionId(), hashFuente);
                if (existente != null) {
                    for (long clave : existente.header().claves()) {
                        long[] ubicacion = existente.header().buscarNodo(clave);
                        header.registrarNodo(clave, ubicacion[0], ubicacion[1]);
                    }
                }
                Path datos = datosDe(region, generacion);
                Files.createDirectories(datos.getParent());
                long tamanoArchivo;
                // Sin índice válido (región nueva, o de otro algoritmo): empezar el archivo de cero.
                StandardOpenOption modo = existente != null ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING;
                try (FileChannel canal = FileChannel.open(datos, StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE, modo)) {
                    long offset = canal.size();
                    for (Map.Entry<Long, byte[]> nodo : nuevos.entrySet()) {
                        header.registrarNodo(nodo.getKey(), offset, nodo.getValue().length);
                        escribirCompleto(canal, ByteBuffer.wrap(nodo.getValue()));
                        offset += nodo.getValue().length;
                    }
                    // Los datos tienen que estar en disco ANTES que el índice que los referencia.
                    canal.force(false);
                    tamanoArchivo = offset;
                }
                escribirIndice(region, header, generacion);
                headers.put(region, new Indice(header, generacion));

                long vivos = 0;
                for (long clave : header.claves()) {
                    vivos += header.buscarNodo(clave)[1];
                }
                if (tamanoArchivo >= COMPACTAR_DESDE_BYTES && vivos * 2 < tamanoArchivo) {
                    compactar(region, header, generacion);
                }
            } catch (IOException | RuntimeException e) {
                // Devolver los nodos a pendientes para reintentar, sin pisar
                // versiones más nuevas que hayan llegado mientras tanto.
                long[] devueltos = {0};
                pendientes.compute(region, (r, llegados) -> {
                    if (llegados == null) {
                        devueltos[0] = bytesDe(nuevos);
                        return nuevos;
                    }
                    nuevos.forEach((clave, datos) -> {
                        if (llegados.putIfAbsent(clave, datos) == null) {
                            devueltos[0] += datos.length;
                        }
                    });
                    return llegados;
                });
                bytesPendientes.addAndGet(devueltos[0]);
                throw e;
            } finally {
                enEscritura.remove(region);
            }
        }
    }

    /**
     * Reescribe solo los nodos vivos en la generación siguiente, cambia el
     * índice (atómico) y después borra la generación vieja. Llamar con el
     * candado de la región tomado.
     */
    private void compactar(ClaveRegion region, RegionHeader actual, long generacion) throws IOException {
        long nueva = generacion + 1;
        RegionHeader compacto = new RegionHeader(region.regionX(), region.regionZ(), region.dimensionId(), hashFuente);
        Path destino = datosDe(region, nueva);
        try (FileChannel canal = FileChannel.open(destino, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            long offset = 0;
            for (long clave : actual.claves()) {
                long[] ubicacion = actual.buscarNodo(clave);
                byte[] datos = leerDeDisco(region, generacion, ubicacion[0], (int) ubicacion[1]);
                compacto.registrarNodo(clave, offset, datos.length);
                escribirCompleto(canal, ByteBuffer.wrap(datos));
                offset += datos.length;
            }
            canal.force(false);
        }
        escribirIndice(region, compacto, nueva);
        headers.put(region, new Indice(compacto, nueva));
        Files.deleteIfExists(datosDe(region, generacion));
    }

    private void escribirIndice(ClaveRegion region, RegionHeader header, long generacion) throws IOException {
        byte[] tabla = header.serializarHeader();
        Path indice = archivoDe(region);
        Path temporal = indice.resolveSibling(indice.getFileName() + ".tmp");
        try (FileChannel canal = FileChannel.open(temporal, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            escribirCompleto(canal, ByteBuffer.allocate(8).putLong(generacion).flip());
            escribirCompleto(canal, ByteBuffer.wrap(tabla));
            canal.force(false);
        }
        moverAtomico(temporal, indice);
    }

    private byte[] buscarEnMemoria(ClaveRegion region, long claveNodo) {
        Map<Long, byte[]> pendientesRegion = pendientes.get(region);
        if (pendientesRegion != null) {
            byte[] datos = pendientesRegion.get(claveNodo);
            if (datos != null) {
                return datos;
            }
        }
        Map<Long, byte[]> escribiendose = enEscritura.get(region);
        return escribiendose == null ? null : escribiendose.get(claveNodo);
    }

    /** Llamar con el candado de la región tomado. null si no hay índice, o es de otra fuente o está roto. */
    private Indice indiceDe(ClaveRegion region) {
        Indice enCache = headers.get(region);
        if (enCache != null) {
            return enCache;
        }
        Path archivo = archivoDe(region);
        if (!Files.exists(archivo)) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(archivo);
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            long generacion = buffer.getLong();
            byte[] tabla = new byte[bytes.length - 8];
            buffer.get(tabla);
            RegionHeader header = RegionHeader.deserializarHeader(tabla);
            if (header.hashFuente != hashFuente || !Files.exists(datosDe(region, generacion))) {
                return null; // generado con otro algoritmo/fuente, o datos perdidos: se regenera
            }
            Indice indice = new Indice(header, generacion);
            headers.put(region, indice);
            return indice;
        } catch (IOException | RuntimeException e) {
            // Índice corrupto o de otra versión de formato: se trata como ausente
            // y se reescribe en el próximo guardado, en vez de romper la carga.
            return null;
        }
    }

    private byte[] leerDeDisco(ClaveRegion region, long generacion, long offset, int tamano) {
        try (FileChannel canal = FileChannel.open(datosDe(region, generacion), StandardOpenOption.READ)) {
            byte[] datos = new byte[tamano];
            leerCompleto(canal, ByteBuffer.wrap(datos), offset);
            return datos;
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer " + datosDe(region, generacion), e);
        }
    }

    private Object candado(ClaveRegion region) {
        return candados.computeIfAbsent(region, r -> new Object());
    }

    private void vaciarSinExcepciones() {
        try {
            vaciar();
        } catch (IOException | RuntimeException e) {
            // Los nodos volvieron a pendientes; se reintenta en el próximo ciclo.
        }
    }

    private static void escribirCompleto(FileChannel canal, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            canal.write(buffer);
        }
    }

    private static void leerCompleto(FileChannel canal, ByteBuffer buffer, long posicion) throws IOException {
        long cursor = posicion;
        while (buffer.hasRemaining()) {
            int leidos = canal.read(buffer, cursor);
            if (leidos < 0) {
                throw new IOException("Fin de archivo inesperado en la posición " + cursor);
            }
            cursor += leidos;
        }
    }

    private static void moverAtomico(Path origen, Path destino) throws IOException {
        try {
            Files.move(origen, destino, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(origen, destino, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Detiene el hilo de escritura y baja todo lo pendiente a disco. */
    @Override
    public void close() throws IOException {
        if (hiloEscritura != null) {
            hiloEscritura.shutdown();
            try {
                hiloEscritura.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        vaciar();
    }
}
