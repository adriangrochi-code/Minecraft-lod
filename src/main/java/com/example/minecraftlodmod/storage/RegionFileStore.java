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

/**
 * Cache en disco de nodos ya serializados (sección 5 del documento de
 * arquitectura), un archivo por región y dimensión:
 *
 *   largo_header: 4 bytes | {@link RegionHeader} | área de datos
 *
 * Los offsets de la tabla del header son relativos al comienzo del área de
 * datos, así que leer un nodo es leer el header (cacheado en RAM) y hacer
 * UNA lectura posicional del tamaño exacto del nodo — la lectura parcial que
 * motiva la tabla de offsets.
 *
 * Escritura diferida (write-behind): {@link #guardar} solo deja el nodo en
 * un mapa de pendientes; un hilo propio los baja a disco en lote cada
 * {@code periodoEscrituraMs} (la sección 5 sugiere 2-5 s). Cada región se
 * reescribe completa en un archivo temporal y se reemplaza con un move
 * atómico, así un crash a mitad de escritura nunca deja un archivo roto.
 *
 * Invalidación: cada archivo lleva el {@code hashFuente} con el que se
 * generó. Si no coincide con el del store (cambió el algoritmo de LOD, o la
 * seed/versión del mundo que el caller mezcle en el hash), el archivo se
 * ignora como si no existiera y se reescribe en el próximo guardado.
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
    private final ConcurrentHashMap<ClaveRegion, RegionHeader> headers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ClaveRegion, Object> candados = new ConcurrentHashMap<>();

    private final ScheduledExecutorService hiloEscritura;

    /**
     * @param periodoEscrituraMs cada cuánto se bajan los pendientes a disco;
     *                           0 o menos desactiva el hilo (solo {@link #vaciar()} manual)
     */
    public RegionFileStore(Path directorioBase, long hashFuente, long periodoEscrituraMs) {
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
        // compute (no computeIfAbsent + put): atómico frente al remove de escribirRegion,
        // así un nodo nunca cae en un mapa que ya se está bajando a disco.
        pendientes.compute(region, (r, nodos) -> {
            ConcurrentHashMap<Long, byte[]> destino = nodos != null ? nodos : new ConcurrentHashMap<>();
            destino.put(claveNodo, datos);
            return destino;
        });
    }

    /** @return los bytes del nodo, o null si no está ni pendiente ni en disco. */
    public byte[] leer(ClaveRegion region, long claveNodo) {
        byte[] enMemoria = buscarEnMemoria(region, claveNodo);
        if (enMemoria != null) {
            return enMemoria;
        }
        synchronized (candado(region)) {
            // Re-chequear: una escritura pudo terminar mientras esperábamos el candado.
            enMemoria = buscarEnMemoria(region, claveNodo);
            if (enMemoria != null) {
                return enMemoria;
            }
            RegionHeader header = headerDe(region);
            if (header == null) {
                return null;
            }
            long[] ubicacion = header.buscarNodo(claveNodo);
            if (ubicacion == null) {
                return null;
            }
            return leerDeDisco(region, ubicacion[0], (int) ubicacion[1]);
        }
    }

    public boolean contiene(ClaveRegion region, long claveNodo) {
        if (buscarEnMemoria(region, claveNodo) != null) {
            return true;
        }
        synchronized (candado(region)) {
            RegionHeader header = headerDe(region);
            return header != null && header.tieneNodo(claveNodo);
        }
    }

    /**
     * Borra una región entera (disco y pendientes) — para la invalidación
     * ante cambios de bloques de la sección 10.
     */
    public void invalidarRegion(ClaveRegion region) {
        synchronized (candado(region)) {
            pendientes.remove(region);
            headers.remove(region);
            try {
                Files.deleteIfExists(archivoDe(region));
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

    Path archivoDe(ClaveRegion region) {
        return directorioBase
                .resolve("dim" + (region.dimensionId() & 0xFF))
                .resolve("r." + region.regionX() + "." + region.regionZ() + ".mlod");
    }

    private void escribirRegion(ClaveRegion region) throws IOException {
        synchronized (candado(region)) {
            ConcurrentHashMap<Long, byte[]> nuevos = pendientes.remove(region);
            if (nuevos == null || nuevos.isEmpty()) {
                return;
            }
            enEscritura.put(region, nuevos);
            try {
                // Fusionar con lo que ya hay en disco: una región se escribe
                // en varios lotes a medida que se generan sus nodos.
                Map<Long, byte[]> todos = new HashMap<>();
                RegionHeader existente = headerDe(region);
                if (existente != null) {
                    for (long clave : existente.claves()) {
                        long[] ubicacion = existente.buscarNodo(clave);
                        todos.put(clave, leerDeDisco(region, ubicacion[0], (int) ubicacion[1]));
                    }
                }
                todos.putAll(nuevos);

                RegionHeader header = new RegionHeader(
                        region.regionX(), region.regionZ(), region.dimensionId(), hashFuente);
                long offset = 0;
                for (Map.Entry<Long, byte[]> nodo : todos.entrySet()) {
                    header.registrarNodo(nodo.getKey(), offset, nodo.getValue().length);
                    offset += nodo.getValue().length;
                }
                byte[] bytesHeader = header.serializarHeader();

                Path destino = archivoDe(region);
                Files.createDirectories(destino.getParent());
                Path temporal = destino.resolveSibling(destino.getFileName() + ".tmp");
                try (FileChannel canal = FileChannel.open(temporal, StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    ByteBuffer largo = ByteBuffer.allocate(4).putInt(bytesHeader.length).flip();
                    escribirCompleto(canal, largo);
                    escribirCompleto(canal, ByteBuffer.wrap(bytesHeader));
                    // Mismo orden de iteración que al registrar los offsets.
                    for (Map.Entry<Long, byte[]> nodo : todos.entrySet()) {
                        escribirCompleto(canal, ByteBuffer.wrap(nodo.getValue()));
                    }
                }
                moverAtomico(temporal, destino);
                headers.put(region, header);
            } catch (IOException | RuntimeException e) {
                // Devolver los nodos a pendientes para reintentar, sin pisar
                // versiones más nuevas que hayan llegado mientras tanto.
                pendientes.compute(region, (r, llegados) -> {
                    if (llegados == null) {
                        return nuevos;
                    }
                    nuevos.forEach(llegados::putIfAbsent);
                    return llegados;
                });
                throw e;
            } finally {
                enEscritura.remove(region);
            }
        }
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

    /** Llamar con el candado de la región tomado. null si no hay archivo o está invalidado. */
    private RegionHeader headerDe(ClaveRegion region) {
        RegionHeader enCache = headers.get(region);
        if (enCache != null) {
            return enCache;
        }
        Path archivo = archivoDe(region);
        if (!Files.exists(archivo)) {
            return null;
        }
        try (FileChannel canal = FileChannel.open(archivo, StandardOpenOption.READ)) {
            ByteBuffer largo = ByteBuffer.allocate(4);
            leerCompleto(canal, largo, 0);
            byte[] bytesHeader = new byte[largo.flip().getInt()];
            leerCompleto(canal, ByteBuffer.wrap(bytesHeader), 4);
            RegionHeader header = RegionHeader.deserializarHeader(bytesHeader);
            if (header.hashFuente != hashFuente) {
                return null; // generado con otro algoritmo/fuente: se ignora y se regenera
            }
            headers.put(region, header);
            return header;
        } catch (IOException | RuntimeException e) {
            // Archivo corrupto o de otra versión de formato: se trata como ausente
            // y se sobreescribe en el próximo guardado, en vez de romper la carga.
            return null;
        }
    }

    private byte[] leerDeDisco(ClaveRegion region, long offsetEnDatos, int tamano) {
        try (FileChannel canal = FileChannel.open(archivoDe(region), StandardOpenOption.READ)) {
            ByteBuffer largo = ByteBuffer.allocate(4);
            leerCompleto(canal, largo, 0);
            long inicioDatos = 4L + largo.flip().getInt();
            byte[] datos = new byte[tamano];
            leerCompleto(canal, ByteBuffer.wrap(datos), inicioDatos + offsetEnDatos);
            return datos;
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer " + archivoDe(region), e);
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
