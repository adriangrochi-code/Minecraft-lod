package com.example.minecraftlodmod.tierra;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PreparadorDatosTest {

    @TempDir
    Path dir;

    private static float valor(int c, int f) {
        return c * 10.25f - f * 20.5f;
    }

    @Test
    void reduceYRecortaAlineadoALaGrilla() throws IOException {
        Path tif = dir.resolve("e.tif");
        // 15° × 15° a 0,25°: 60 × 60 píxeles, esquina NO en (75 E, 30 N)
        TiffDePrueba.flotanteEnTeselas(tif, ByteOrder.LITTLE_ENDIAN, 60, 60, 16, 75, 30, 0.25, PreparadorDatosTest::valor);
        Path salida = dir.resolve("e.lodt");
        FormatoLodt.Cabecera cab = PreparadorDatos.preparar(tif, salida, 2,
                new PreparadorDatos.Recorte(20, 25, 80, 90), 8);
        assertEquals(20, cab.ancho()); // 10° / 0,5°
        assertEquals(10, cab.alto());
        assertEquals(0.5, cab.paso(), 1e-12);
        assertEquals(80.25, cab.lonOrigen(), 1e-12);
        assertEquals(24.75, cab.latOrigen(), 1e-12);
        try (LectorLodt l = LectorLodt.abrir(salida, 1 << 20)) {
            // recorte: columna de origen 20 (80 E), fila de origen 20 (25 N)
            for (int f = 0; f < 10; f++) {
                for (int c = 0; c < 20; c++) {
                    int c0 = 20 + 2 * c, f0 = 20 + 2 * f;
                    double media = (valor(c0, f0) + valor(c0 + 1, f0) + valor(c0, f0 + 1) + valor(c0 + 1, f0 + 1)) / 4;
                    assertEquals(Math.round(media), l.elevacion(c, f), "(" + c + ", " + f + ")");
                }
            }
        }
    }

    @Test
    void recorteFueraDelArchivoFalla() throws IOException {
        Path tif = dir.resolve("e.tif");
        TiffDePrueba.flotanteEnTeselas(tif, ByteOrder.LITTLE_ENDIAN, 8, 8, 8, 0, 8, 1, PreparadorDatosTest::valor);
        assertThrows(IOException.class, () -> PreparadorDatos.preparar(tif, dir.resolve("x.lodt"), 1,
                new PreparadorDatos.Recorte(-5, 5, 0, 4), 8));
    }
}
