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
 * Nota de diseño: acá solo se serializa el CONTENIDO de un nodo (sin
 * posición/tamaño en mundo) porque esa información ya la conoce quien pide
 * el nodo — tanto en disco (offset dentro de la región) como en red (el
 * cliente pidió explícitamente esa región+nivel). Guardar la posición acá
 * también sería redundante.
 */
public final class OctreeNodeCodec {

    private static final int HEADER_BYTES = 4; // nivel_lod(1) + flag_homogeneo(1) + count(2)

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

    /** Resultado de deserializar: los datos crudos, sin reconstruir un OctreeNode completo
     *  (reconstruir el árbol con posiciones de mundo es responsabilidad de quien orquesta
     *  la carga — storage/ solo se ocupa de bytes <-> vóxeles). */
    public record NodoDeserializado(int nivelLod, boolean homogeneo, SuperVoxel[] voxeles) {
    }

    public static NodoDeserializado deserializar(byte[] buffer, int offset, int totalVoxelesEsperados) {
        int nivelLod = buffer[offset] & 0xFF;
        boolean homogeneo = buffer[offset + 1] != 0;
        int countRuns = ((buffer[offset + 2] & 0xFF) << 8) | (buffer[offset + 3] & 0xFF);

        SuperVoxel[] voxeles = RunLengthCodec.leerYDecodificar(
                buffer, offset + HEADER_BYTES, countRuns, totalVoxelesEsperados);
        return new NodoDeserializado(nivelLod, homogeneo, voxeles);
    }

    /** Tamaño en bytes que ocuparía este nodo ya serializado, sin serializarlo (para la tabla de offsets). */
    public static int tamanoSerializado(OctreeNode nodo, int tamanoTotalNodo3D) {
        if (nodo.esHomogeneo()) {
            return HEADER_BYTES + RunLengthCodec.BYTES_POR_RUN;
        }
        List<RunLengthCodec.Run> runs = RunLengthCodec.codificar(nodo.voxeles());
        return HEADER_BYTES + RunLengthCodec.bytesNecesarios(runs);
    }
}
