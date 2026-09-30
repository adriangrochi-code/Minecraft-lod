package com.example.minecraftlodmod.storage;

import com.example.minecraftlodmod.core.SuperVoxel;

import java.util.ArrayList;
import java.util.List;

/**
 * Codificador RLE (run-length encoding) para arrays de SuperVoxel.
 * Ver sección 5 del documento de arquitectura: cada "run" es un SuperVoxel
 * (8 bytes) + una longitud de repetición (2 bytes) = 10 bytes por run.
 *
 * Terreno natural tiene corridas largas de vóxeles idénticos (mismo bioma,
 * misma altura), así que esto comprime bien sin necesitar Deflate por nodo.
 */
public final class RunLengthCodec {

    public static final int BYTES_POR_RUN = SuperVoxel.BYTES + 2; // voxel + run_length (short)
    private static final int MAX_RUN_LENGTH = 0xFFFF;

    private RunLengthCodec() {
    }

    /** Representa un tramo de N supervóxeles idénticos consecutivos. */
    public record Run(SuperVoxel voxel, int longitud) {
    }

    public static List<Run> codificar(SuperVoxel[] voxeles) {
        List<Run> runs = new ArrayList<>();
        if (voxeles.length == 0) return runs;

        SuperVoxel actual = voxeles[0];
        int longitud = 1;

        for (int i = 1; i < voxeles.length; i++) {
            if (voxeles[i].equals(actual) && longitud < MAX_RUN_LENGTH) {
                longitud++;
            } else {
                runs.add(new Run(actual, longitud));
                actual = voxeles[i];
                longitud = 1;
            }
        }
        runs.add(new Run(actual, longitud));
        return runs;
    }

    public static SuperVoxel[] decodificar(List<Run> runs, int totalEsperado) {
        SuperVoxel[] resultado = new SuperVoxel[totalEsperado];
        int cursor = 0;
        for (Run run : runs) {
            for (int i = 0; i < run.longitud(); i++) {
                resultado[cursor++] = run.voxel();
            }
        }
        if (cursor != totalEsperado) {
            throw new IllegalStateException(
                    "RLE decodificó " + cursor + " vóxeles, se esperaban " + totalEsperado);
        }
        return resultado;
    }

    public static void escribirRuns(List<Run> runs, byte[] destino, int offset) {
        int cursor = offset;
        for (Run run : runs) {
            run.voxel().escribirEn(destino, cursor);
            cursor += SuperVoxel.BYTES;
            destino[cursor] = (byte) ((run.longitud() >> 8) & 0xFF);
            destino[cursor + 1] = (byte) (run.longitud() & 0xFF);
            cursor += 2;
        }
    }

    public static List<Run> leerRuns(byte[] origen, int offset, int cantidadRuns) {
        List<Run> runs = new ArrayList<>(cantidadRuns);
        int cursor = offset;
        for (int i = 0; i < cantidadRuns; i++) {
            SuperVoxel voxel = SuperVoxel.leerDe(origen, cursor);
            cursor += SuperVoxel.BYTES;
            int longitud = ((origen[cursor] & 0xFF) << 8) | (origen[cursor + 1] & 0xFF);
            cursor += 2;
            runs.add(new Run(voxel, longitud));
        }
        return runs;
    }

    /**
     * {@link #leerRuns} + {@link #decodificar} de una sola pasada, sin crear un
     * {@link Run} por corrida ni la lista: es lo que corre por cada nodo que se
     * lee para mallar.
     */
    public static SuperVoxel[] leerYDecodificar(byte[] origen, int offset, int cantidadRuns, int totalEsperado) {
        SuperVoxel[] resultado = new SuperVoxel[totalEsperado];
        int cursor = offset;
        int escritos = 0;
        for (int i = 0; i < cantidadRuns; i++) {
            SuperVoxel voxel = SuperVoxel.leerDe(origen, cursor);
            cursor += SuperVoxel.BYTES;
            int longitud = ((origen[cursor] & 0xFF) << 8) | (origen[cursor + 1] & 0xFF);
            cursor += 2;
            if (longitud > totalEsperado - escritos) {
                throw new IllegalStateException(
                        "RLE decodificó más de " + totalEsperado + " vóxeles");
            }
            java.util.Arrays.fill(resultado, escritos, escritos + longitud, voxel);
            escritos += longitud;
        }
        if (escritos != totalEsperado) {
            throw new IllegalStateException(
                    "RLE decodificó " + escritos + " vóxeles, se esperaban " + totalEsperado);
        }
        return resultado;
    }

    public static int bytesNecesarios(List<Run> runs) {
        return runs.size() * BYTES_POR_RUN;
    }
}
