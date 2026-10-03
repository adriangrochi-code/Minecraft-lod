package com.example.minecraftlodmod.storage;

import com.example.minecraftlodmod.core.OctreeNode;
import com.example.minecraftlodmod.core.SuperVoxel;

import java.util.List;

/**
 * Serializa/deserializa un OctreeNode individual al formato binario de la
 * sección 5 del documento de arquitectura:
 *
 *   nivel_lod: 1 byte | flag_homogeneo: 1 byte | count: 2 bytes
 *   | data: RLE-encoded array de SuperVoxel
 *
 * Formato con paleta (0.26.34, bit 1 de {@code flag_homogeneo}): {@code count} es
 * la cantidad de vóxeles distintos, sigue la paleta (8 bytes por vóxel) y un
 * índice por vóxel (1 byte con hasta 256 en la paleta, si no 2). Se usa en los
 * nodos grandes y variados ({@link #usaPaleta}): con el Deflate del store
 * encima ocupa ~30% menos en terreno real (los vóxeles de una sección se
 * repiten muchísimo pero casi nunca en corridas largas). Los chicos o muy
 * uniformes siguen con RLE, donde la paleta agregaba más de lo que ahorraba.
 * Los datos guardados con RLE se siguen leyendo igual.
 *
 * Nota de diseño: acá solo se serializa el CONTENIDO de un nodo (sin
 * posición/tamaño en mundo) porque esa información ya la conoce quien pide
 * el nodo — tanto en disco (offset dentro de la región) como en red (el
 * cliente pidió explícitamente esa región+nivel). Guardar la posición acá
 * también sería redundante.
 */
public final class OctreeNodeCodec {

    private static final int HEADER_BYTES = 4; // nivel_lod(1) + flag_homogeneo(1) + count(2)
    /** Bits de {@code flag_homogeneo}. */
    static final int FLAG_HOMOGENEO = 1, FLAG_PALETA = 2;
    /** Vóxeles desde los que conviene la paleta (niveles 0 y 1 de sección, nodos grandes). */
    static final int VOXELES_MINIMOS_PALETA = 512;
    /** Con menos corridas que esto el RLE ya es chico: la paleta no gana. */
    static final int CORRIDAS_MINIMAS_PALETA = 16;

    /**
     * Medido sobre ~1,5 millones de nodos de tres mundos (servidor, benchmark y uno
     * casi todo aproximado), con el Deflate del store: esta regla queda a menos del
     * 1% del mejor formato elegido nodo por nodo.
     */
    static boolean usaPaleta(int voxeles, int corridas) {
        return voxeles >= VOXELES_MINIMOS_PALETA && corridas >= CORRIDAS_MINIMAS_PALETA;
    }

    private OctreeNodeCodec() {
    }

    /**
     * Serializa el nodo. Para nodos homogéneos, se codifica como si fuera
     * un array de 1 elemento (simplifica el formato: no hace falta un caso
     * especial al leer, "count=1 + flag_homogeneo=1" ya lo distingue).
     */
    public static byte[] serializar(OctreeNode nodo, int tamanoTotalNodo3D) {
        List<RunLengthCodec.Run> runs;

        if (nodo.esHomogeneo()) {
            runs = List.of(new RunLengthCodec.Run(nodo.voxelHomogeneo(), tamanoTotalNodo3D));
        } else {
            SuperVoxel[] voxeles = nodo.voxeles();
            runs = RunLengthCodec.codificar(voxeles);
            if (usaPaleta(voxeles.length, runs.size())) {
                return serializarConPaleta(nodo.nivelLod(), voxeles, runs);
            }
        }

        int totalBytes = HEADER_BYTES + RunLengthCodec.bytesNecesarios(runs);
        byte[] buffer = new byte[totalBytes];

        buffer[0] = (byte) nodo.nivelLod();
        buffer[1] = (byte) (nodo.esHomogeneo() ? 1 : 0);
        buffer[2] = (byte) ((runs.size() >> 8) & 0xFF);
        buffer[3] = (byte) (runs.size() & 0xFF);

        RunLengthCodec.escribirRuns(runs, buffer, HEADER_BYTES);
        return buffer;
    }

    private static byte[] serializarConPaleta(int nivelLod, SuperVoxel[] voxeles, List<RunLengthCodec.Run> runs) {
        // Paleta en orden de aparición (las corridas ya agrupan lo contiguo).
        java.util.HashMap<SuperVoxel, Integer> indices = new java.util.HashMap<>();
        java.util.ArrayList<SuperVoxel> paleta = new java.util.ArrayList<>();
        for (RunLengthCodec.Run r : runs) {
            if (indices.putIfAbsent(r.voxel(), paleta.size()) == null) {
                paleta.add(r.voxel());
            }
        }
        int distintos = paleta.size();
        boolean ancho = distintos > 256;
        byte[] buffer = new byte[HEADER_BYTES + distintos * SuperVoxel.BYTES + voxeles.length * (ancho ? 2 : 1)];
        buffer[0] = (byte) nivelLod;
        buffer[1] = (byte) FLAG_PALETA;
        buffer[2] = (byte) (distintos >> 8);
        buffer[3] = (byte) distintos;
        int cursor = HEADER_BYTES;
        for (SuperVoxel v : paleta) {
            v.escribirEn(buffer, cursor);
            cursor += SuperVoxel.BYTES;
        }
        for (RunLengthCodec.Run r : runs) {
            int i = indices.get(r.voxel());
            for (int k = 0; k < r.longitud(); k++) {
                if (ancho) {
                    buffer[cursor++] = (byte) (i >> 8);
                }
                buffer[cursor++] = (byte) i;
            }
        }
        return buffer;
    }

    /** Resultado de deserializar: los datos crudos, sin reconstruir un OctreeNode completo
     *  (reconstruir el árbol con posiciones de mundo es responsabilidad de quien orquesta
     *  la carga — storage/ solo se ocupa de bytes <-> vóxeles). */
    public record NodoDeserializado(int nivelLod, boolean homogeneo, SuperVoxel[] voxeles) {
    }

    /**
     * true si los bytes pueden ser un nodo (al menos la cabecera). Las marcas del
     * store (arreglos de 0 o 1 byte) no lo son: una marca vieja de "chunk extraído"
     * comparte clave con un nodo aproximado fino y, leída como nodo, rompía el armado.
     */
    public static boolean esNodo(byte[] bytes) {
        return bytes != null && bytes.length >= HEADER_BYTES;
    }

    public static NodoDeserializado deserializar(byte[] buffer, int offset, int totalVoxelesEsperados) {
        int nivelLod = buffer[offset] & 0xFF;
        int flags = buffer[offset + 1] & 0xFF;
        boolean homogeneo = (flags & FLAG_HOMOGENEO) != 0;
        int countRuns = ((buffer[offset + 2] & 0xFF) << 8) | (buffer[offset + 3] & 0xFF);
        if ((flags & FLAG_PALETA) != 0) {
            return new NodoDeserializado(nivelLod, false,
                    leerConPaleta(buffer, offset + HEADER_BYTES, countRuns, totalVoxelesEsperados));
        }

        SuperVoxel[] voxeles = RunLengthCodec.leerYDecodificar(
                buffer, offset + HEADER_BYTES, countRuns, totalVoxelesEsperados);
        return new NodoDeserializado(nivelLod, homogeneo, voxeles);
    }

    private static SuperVoxel[] leerConPaleta(byte[] buffer, int offset, int distintos, int total) {
        if (distintos <= 0) {
            throw new IllegalStateException("Nodo con paleta vacía");
        }
        SuperVoxel[] paleta = new SuperVoxel[distintos];
        int cursor = offset;
        for (int i = 0; i < distintos; i++) {
            paleta[i] = SuperVoxel.leerDe(buffer, cursor);
            cursor += SuperVoxel.BYTES;
        }
        boolean ancho = distintos > 256;
        if (buffer.length - cursor < (long) total * (ancho ? 2 : 1)) {
            throw new IllegalStateException("Nodo con paleta cortado: se esperaban " + total + " vóxeles");
        }
        SuperVoxel[] resultado = new SuperVoxel[total];
        for (int k = 0; k < total; k++) {
            int i = ancho ? ((buffer[cursor] & 0xFF) << 8) | (buffer[cursor + 1] & 0xFF) : buffer[cursor] & 0xFF;
            cursor += ancho ? 2 : 1;
            if (i >= distintos) {
                throw new IllegalStateException("Índice de paleta fuera de rango: " + i);
            }
            resultado[k] = paleta[i];
        }
        return resultado;
    }

    /** Tamaño en bytes que ocuparía este nodo ya serializado, sin serializarlo (para la tabla de offsets). */
    public static int tamanoSerializado(OctreeNode nodo, int tamanoTotalNodo3D) {
        if (nodo.esHomogeneo()) {
            return HEADER_BYTES + RunLengthCodec.BYTES_POR_RUN;
        }
        List<RunLengthCodec.Run> runs = RunLengthCodec.codificar(nodo.voxeles());
        if (usaPaleta(nodo.voxeles().length, runs.size())) {
            return serializarConPaleta(nodo.nivelLod(), nodo.voxeles(), runs).length;
        }
        return HEADER_BYTES + RunLengthCodec.bytesNecesarios(runs);
    }
}
