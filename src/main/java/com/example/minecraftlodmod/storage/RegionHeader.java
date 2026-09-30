package com.example.minecraftlodmod.storage;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

/**
 * Header de un archivo de región (ver sección 5 del documento de
 * arquitectura):
 *
 *   magic: 4 bytes ("MLOD") | version: 1 byte | region_x,z: 4 bytes c/u
 *   | dimension_id: 1 byte | hash_fuente: 8 bytes
 *   | tabla_offsets: N x 8 bytes (offset:4 + tamaño:4, por nodo)
 *
 * La tabla de offsets es lo que permite leer un solo nodo del archivo sin
 * cargar la región completa — clave para aprovechar bien un SSD (muchas
 * lecturas chicas con seek barato) en vez de leer todo el archivo siempre.
 *
 * La clave de cada entrada de la tabla es un identificador compacto de nodo
 * (nivel + posición relativa dentro de la región) — acá se modela como un
 * long armado por el caller; este codec no conoce la jerarquía del octree,
 * solo mapea claves a offsets.
 */
public final class RegionHeader {

    private static final byte[] MAGIC = {'M', 'L', 'O', 'D'};
    private static final int VERSION_ACTUAL = 1;

    public final int regionX, regionZ;
    public final byte dimensionId;
    public final long hashFuente;

    /** clave de nodo -> [offsetEnArchivo, tamanoEnBytes] */
    private final Map<Long, long[]> tablaOffsets = new HashMap<>();

    public RegionHeader(int regionX, int regionZ, byte dimensionId, long hashFuente) {
        this.regionX = regionX;
        this.regionZ = regionZ;
        this.dimensionId = dimensionId;
        this.hashFuente = hashFuente;
    }

    public void registrarNodo(long claveNodo, long offset, long tamano) {
        tablaOffsets.put(claveNodo, new long[]{offset, tamano});
    }

    /** @return {offset, tamaño} o null si el nodo no está cacheado en esta región. */
    public long[] buscarNodo(long claveNodo) {
        return tablaOffsets.get(claveNodo);
    }

    public boolean tieneNodo(long claveNodo) {
        return tablaOffsets.containsKey(claveNodo);
    }

    /**
     * Construye la clave compacta de un nodo a partir de su nivel de LOD y
     * su posición relativa dentro de la región (coordenadas de nodo, no de
     * bloque). Empaqueta todo en un long para poder usarlo como clave de
     * mapa sin generar objetos por búsqueda.
     */
    public static long claveNodo(int nivelLod, int relX, int relY, int relZ) {
        // 8 bits nivel + 18 bits por eje (suficiente para regiones grandes)
        return ((long) (nivelLod & 0xFF) << 54)
                | ((long) (relX & 0x3FFFF) << 36)
                | ((long) (relY & 0x3FFFF) << 18)
                | ((long) (relZ & 0x3FFFF));
    }

    public byte[] serializarHeader() {
        int cantidadEntradas = tablaOffsets.size();
        int tamano = MAGIC.length + 1 + 4 + 4 + 1 + 8 + 4 + cantidadEntradas * (8 + 8 + 8);
        ByteBuffer buf = ByteBuffer.allocate(tamano).order(ByteOrder.BIG_ENDIAN);

        buf.put(MAGIC);
        buf.put((byte) VERSION_ACTUAL);
        buf.putInt(regionX);
        buf.putInt(regionZ);
        buf.put(dimensionId);
        buf.putLong(hashFuente);
        buf.putInt(cantidadEntradas);

        for (Map.Entry<Long, long[]> entrada : tablaOffsets.entrySet()) {
            buf.putLong(entrada.getKey());
            buf.putLong(entrada.getValue()[0]); // offset
            buf.putLong(entrada.getValue()[1]); // tamaño
        }

        return buf.array();
    }

    public static RegionHeader deserializarHeader(byte[] datos) {
        ByteBuffer buf = ByteBuffer.wrap(datos).order(ByteOrder.BIG_ENDIAN);

        byte[] magicLeido = new byte[4];
        buf.get(magicLeido);
        for (int i = 0; i < 4; i++) {
            if (magicLeido[i] != MAGIC[i]) {
                throw new IllegalArgumentException("Archivo de región inválido: magic incorrecto");
            }
        }

        int version = buf.get() & 0xFF;
        if (version != VERSION_ACTUAL) {
            throw new IllegalArgumentException(
                    "Versión de formato no soportada: " + version + " (esperada " + VERSION_ACTUAL + ")");
        }

        int regionX = buf.getInt();
        int regionZ = buf.getInt();
        byte dimensionId = buf.get();
        long hashFuente = buf.getLong();
        int cantidadEntradas = buf.getInt();

        RegionHeader header = new RegionHeader(regionX, regionZ, dimensionId, hashFuente);
        for (int i = 0; i < cantidadEntradas; i++) {
            long clave = buf.getLong();
            long offset = buf.getLong();
            long tamano = buf.getLong();
            header.tablaOffsets.put(clave, new long[]{offset, tamano});
        }
        return header;
    }

    /**
     * Copia con la misma tabla (las ubicaciones {offset, tamaño} se comparten:
     * nunca se modifican después de registrarse). Mucho más barata que
     * recorrer {@link #claves()} y volver a registrar cada nodo.
     */
    public RegionHeader copia() {
        RegionHeader copia = new RegionHeader(regionX, regionZ, dimensionId, hashFuente);
        copia.tablaOffsets.putAll(tablaOffsets);
        return copia;
    }

    /** Suma de los tamaños de todos los nodos registrados (los bytes vivos del archivo). */
    public long bytesVivos() {
        long total = 0;
        for (long[] ubicacion : tablaOffsets.values()) {
            total += ubicacion[1];
        }
        return total;
    }

    /** Claves de todos los nodos registrados (copia, para iterar sin exponer la tabla interna). */
    public java.util.Set<Long> claves() {
        return java.util.Set.copyOf(tablaOffsets.keySet());
    }

    public int cantidadNodos() {
        return tablaOffsets.size();
    }
}
