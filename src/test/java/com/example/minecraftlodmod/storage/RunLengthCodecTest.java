package com.example.minecraftlodmod.storage;

import com.example.minecraftlodmod.core.SuperVoxel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RunLengthCodecTest {

    private static SuperVoxel voxel(int r) {
        return new SuperVoxel((byte) r, (byte) 0, (byte) 0, (byte) 64,
                SuperVoxel.Material.SOLIDO, (byte) 0);
    }

    @Test
    void codificaYDecodificaSinPerderDatos() {
        SuperVoxel[] original = {
                voxel(1), voxel(1), voxel(1), voxel(2), voxel(2), voxel(3)
        };

        List<RunLengthCodec.Run> runs = RunLengthCodec.codificar(original);
        assertEquals(3, runs.size(), "Deberían formarse 3 corridas: 1x3, 2x2, 3x1");

        SuperVoxel[] reconstruido = RunLengthCodec.decodificar(runs, original.length);
        assertArrayEquals(original, reconstruido);
    }

    @Test
    void serializacionBinariaEsRoundTrip() {
        SuperVoxel[] original = {voxel(5), voxel(5), voxel(7)};
        List<RunLengthCodec.Run> runs = RunLengthCodec.codificar(original);

        byte[] buffer = new byte[RunLengthCodec.bytesNecesarios(runs)];
        RunLengthCodec.escribirRuns(runs, buffer, 0);

        List<RunLengthCodec.Run> leidos = RunLengthCodec.leerRuns(buffer, 0, runs.size());
        SuperVoxel[] reconstruido = RunLengthCodec.decodificar(leidos, original.length);

        assertArrayEquals(original, reconstruido);
    }

    @Test
    void leerYDecodificarDaLoMismoYRechazaDeMas() {
        SuperVoxel[] original = {voxel(5), voxel(5), voxel(7), voxel(7), voxel(7), voxel(1)};
        List<RunLengthCodec.Run> runs = RunLengthCodec.codificar(original);
        byte[] buffer = new byte[3 + RunLengthCodec.bytesNecesarios(runs)];
        RunLengthCodec.escribirRuns(runs, buffer, 3);

        assertArrayEquals(original, RunLengthCodec.leerYDecodificar(buffer, 3, runs.size(), original.length));
        assertThrows(IllegalStateException.class,
                () -> RunLengthCodec.leerYDecodificar(buffer, 3, runs.size(), original.length - 1));
        assertThrows(IllegalStateException.class,
                () -> RunLengthCodec.leerYDecodificar(buffer, 3, runs.size(), original.length + 1));
    }
}
