package com.example.minecraftlodmod.storage;

import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

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

    /**
     * clave de nodo -> offset (40 bits altos) | tamaño (24 bits bajos), en un mapa de
     * long a long sin objetos (fastutil, viene con Minecraft). Se consulta en cada
     * lectura y en cada {@code contiene} de la generación: con {@code Map<Long, long[]>}
     * cada consulta creaba un Long, y cargar un índice de 65 000 nodos creaba 65 000
     * arreglos (235 ms con el candado de la región tomado, en el perfil).
     */
    private final Long2LongOpenHashMap tablaOffsets;
    /** Suma de los tamaños registrados, llevada al día en cada registro (antes se recorría la tabla). */
    private long bytesVivos;

    static final int BITS_TAMANO = 24;
    static final long MAX_TAMANO = (1L << BITS_TAMANO) - 1;
    static final long MAX_OFFSET = (1L << (64 - BITS_TAMANO)) - 1;

    private static long empaquetar(long offset, long tamano) {
        if (offset < 0 || offset > MAX_OFFSET || tamano < 0 || tamano > MAX_TAMANO) {
            throw new IllegalArgumentException("Nodo fuera de rango: offset " + offset + ", tamaño " + tamano);
        }
        return offset << BITS_TAMANO | tamano;
    }

    public RegionHeader(int regionX, int regionZ, byte dimensionId, long hashFuente) {
        this.regionX = regionX;
        this.regionZ = regionZ;
        this.dimensionId = dimensionId;
        this.hashFuente = hashFuente;
        this.tablaOffsets = new Long2LongOpenHashMap();
        this.tablaOffsets.defaultReturnValue(-1);
    }

    private RegionHeader(RegionHeader origen) {
        this.regionX = origen.regionX;
        this.regionZ = origen.regionZ;
        this.dimensionId = origen.dimensionId;
        this.hashFuente = origen.hashFuente;
        this.tablaOffsets = new Long2LongOpenHashMap(origen.tablaOffsets);
        this.tablaOffsets.defaultReturnValue(-1);
        this.bytesVivos = origen.bytesVivos;
    }

    public void registrarNodo(long claveNodo, long offset, long tamano) {
        long anterior = tablaOffsets.put(claveNodo, empaquetar(offset, tamano));
        bytesVivos += tamano - (anterior < 0 ? 0 : anterior & MAX_TAMANO);
    }

    /** @return {offset, tamaño} o null si el nodo no está cacheado en esta región. */
    public long[] buscarNodo(long claveNodo) {
        long ubicacion = tablaOffsets.get(claveNodo);
        return ubicacion < 0 ? null : new long[]{ubicacion >>> BITS_TAMANO, ubicacion & MAX_TAMANO};
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

        for (Long2LongMap.Entry entrada : tablaOffsets.long2LongEntrySet()) {
            buf.putLong(entrada.getLongKey());
            buf.putLong(entrada.getLongValue() >>> BITS_TAMANO); // offset
            buf.putLong(entrada.getLongValue() & MAX_TAMANO); // tamaño
        }

        return buf.array();
    }

    /**
     * Lo mismo que {@link #serializarHeader()} pero escrito por partes: un índice de
     * 65 000 nodos son 1,5 MB, y armarlo entero en un arreglo en cada lote de escritura
     * era una asignación "humongous" del GC (pausas) por región y por lote.
     */
    public void escribirEn(java.nio.channels.WritableByteChannel canal) throws java.io.IOException {
        ByteBuffer buf = ByteBuffer.allocate(64 * 1024).order(ByteOrder.BIG_ENDIAN);
        buf.put(MAGIC);
        buf.put((byte) VERSION_ACTUAL);
        buf.putInt(regionX);
        buf.putInt(regionZ);
        buf.put(dimensionId);
        buf.putLong(hashFuente);
        buf.putInt(tablaOffsets.size());
        for (Long2LongMap.Entry entrada : tablaOffsets.long2LongEntrySet()) {
            if (buf.remaining() < 24) {
                vaciar(buf, canal);
            }
            buf.putLong(entrada.getLongKey());
            buf.putLong(entrada.getLongValue() >>> BITS_TAMANO);
            buf.putLong(entrada.getLongValue() & MAX_TAMANO);
        }
        vaciar(buf, canal);
    }

    private static void vaciar(ByteBuffer buf, java.nio.channels.WritableByteChannel canal) throws java.io.IOException {
        buf.flip();
        while (buf.hasRemaining()) {
            canal.write(buf);
        }
        buf.clear();
    }

    /** Lee lo que escribió {@link #escribirEn} (o {@link #serializarHeader()}), por partes. */
    public static RegionHeader leerDe(java.io.DataInput entrada) throws java.io.IOException {
        byte[] magicLeido = new byte[4];
        entrada.readFully(magicLeido);
        for (int i = 0; i < 4; i++) {
            if (magicLeido[i] != MAGIC[i]) {
                throw new IllegalArgumentException("Archivo de región inválido: magic incorrecto");
            }
        }
        int version = entrada.readUnsignedByte();
        if (version != VERSION_ACTUAL) {
            throw new IllegalArgumentException(
                    "Versión de formato no soportada: " + version + " (esperada " + VERSION_ACTUAL + ")");
        }
        RegionHeader header = new RegionHeader(entrada.readInt(), entrada.readInt(), entrada.readByte(),
                entrada.readLong());
        int cantidadEntradas = entrada.readInt();
        if (cantidadEntradas < 0) {
            throw new IllegalArgumentException("Cantidad de nodos inválida: " + cantidadEntradas);
        }
        header.tablaOffsets.ensureCapacity(cantidadEntradas);
        for (int i = 0; i < cantidadEntradas; i++) {
            long clave = entrada.readLong();
            long offset = entrada.readLong();
            long tamano = entrada.readLong();
            header.registrarNodo(clave, offset, tamano);
        }
        return header;
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
        header.tablaOffsets.ensureCapacity(cantidadEntradas);
        for (int i = 0; i < cantidadEntradas; i++) {
            long clave = buf.getLong();
            long offset = buf.getLong();
            long tamano = buf.getLong();
            header.registrarNodo(clave, offset, tamano);
        }
        return header;
    }

    /** Copia de la tabla (arreglos planos): mucho más barata que volver a registrar cada nodo. */
    public RegionHeader copia() {
        return new RegionHeader(this);
    }

    /** Suma de los tamaños de todos los nodos registrados (los bytes vivos del archivo). */
    public long bytesVivos() {
        return bytesVivos;
    }

    /** Bytes que ocupa este header serializado ({@link #serializarHeader()} / {@link #escribirEn}). */
    public long bytesSerializados() {
        return 4 + 1 + 4 + 4 + 1 + 8 + 4 + (long) tablaOffsets.size() * 24;
    }

    // ------------------------------------------------------------------ diario

    /*
     * Diario del índice (append-only): después del header, cero o más bloques
     *
     *   cantidad: 4 bytes | cantidad × (clave: 8 | offset: 8 | tamaño: 8) | crc32: 4 bytes
     *
     * con el CRC32 de las entradas. Cada lote de escritura agrega un bloque en
     * vez de reescribir el header entero (que con regiones grandes era 1,5 MB
     * por región y por lote). Un bloque cortado por un corte de luz, o con el
     * CRC mal, termina el diario: lo anterior vale, eso y lo que siga se
     * ignora (y quien escribe después lo recorta). Un header sin bloques es el
     * formato de antes: se lee igual.
     */

    /** Entradas por bloque: bloques chicos (≤ 48 KB) aunque un lote traiga miles de nodos. */
    static final int ENTRADAS_POR_BLOQUE = 2048;
    private static final int MAXIMO_POR_BLOQUE_LEIDO = 1 << 20;

    /**
     * Escribe las entradas {@code [0, n)} como bloques de diario.
     *
     * @return bytes escritos
     */
    public static long escribirDiario(java.nio.channels.WritableByteChannel canal, long[] claves, long[] offsets,
                                      long[] tamanos, int n) throws java.io.IOException {
        long escritos = 0;
        for (int desde = 0; desde < n; desde += ENTRADAS_POR_BLOQUE) {
            int cuantas = Math.min(ENTRADAS_POR_BLOQUE, n - desde);
            ByteBuffer buf = ByteBuffer.allocate(4 + cuantas * 24 + 4).order(ByteOrder.BIG_ENDIAN);
            buf.putInt(cuantas);
            for (int i = desde; i < desde + cuantas; i++) {
                empaquetar(offsets[i], tamanos[i]); // valida el rango antes de escribir nada
                buf.putLong(claves[i]).putLong(offsets[i]).putLong(tamanos[i]);
            }
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(buf.array(), 4, cuantas * 24);
            buf.putInt((int) crc.getValue());
            escritos += buf.capacity();
            vaciar(buf, canal);
        }
        return escritos;
    }

    /** Resultado de leer un diario: bytes válidos (sin el resto cortado) y entradas aplicadas. */
    public record DiarioLeido(long bytes, int entradas) {
    }

    /**
     * Aplica a este header los bloques de diario que siguen en {@code entrada}, hasta el
     * final o hasta el primer bloque incompleto o con el CRC mal.
     */
    public DiarioLeido leerDiario(java.io.InputStream entrada) throws java.io.IOException {
        long bytes = 0;
        int entradas = 0;
        byte[] cabecera = new byte[4];
        while (true) {
            if (entrada.readNBytes(cabecera, 0, 4) < 4) {
                break;
            }
            int cuantas = ByteBuffer.wrap(cabecera).getInt();
            if (cuantas <= 0 || cuantas > MAXIMO_POR_BLOQUE_LEIDO) {
                break;
            }
            byte[] cuerpo = new byte[cuantas * 24 + 4];
            if (entrada.readNBytes(cuerpo, 0, cuerpo.length) < cuerpo.length) {
                break;
            }
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(cuerpo, 0, cuantas * 24);
            ByteBuffer b = ByteBuffer.wrap(cuerpo).order(ByteOrder.BIG_ENDIAN);
            if (b.getInt(cuantas * 24) != (int) crc.getValue()) {
                break;
            }
            for (int i = 0; i < cuantas; i++) {
                long offset = b.getLong(i * 24 + 8), tamano = b.getLong(i * 24 + 16);
                if (offset < 0 || offset > MAX_OFFSET || tamano < 0 || tamano > MAX_TAMANO) {
                    return new DiarioLeido(bytes, entradas); // CRC bien pero datos imposibles: el bloque entero no
                }
            }
            for (int i = 0; i < cuantas; i++) {
                registrarNodo(b.getLong(i * 24), b.getLong(i * 24 + 8), b.getLong(i * 24 + 16));
            }
            bytes += 4 + cuerpo.length;
            entradas += cuantas;
        }
        return new DiarioLeido(bytes, entradas);
    }

    /** Claves de todos los nodos registrados (copia, para iterar sin exponer la tabla interna). */
    public long[] claves() {
        return tablaOffsets.keySet().toLongArray();
    }

    public int cantidadNodos() {
        return tablaOffsets.size();
    }
}
