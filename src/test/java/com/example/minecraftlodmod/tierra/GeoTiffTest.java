package com.example.minecraftlodmod.tierra;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GeoTiffTest {

    @TempDir
    Path dir;

    private static float valor(int c, int f) {
        return (float) (c * 7.25 - f * 3.5 + Math.sin(c * 0.3) * 100);
    }

    @Test
    void leeFlotanteEnTeselasConPredictor3EnLosDosOrdenes() throws IOException {
        for (ByteOrder orden : new ByteOrder[]{ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN}) {
            Path p = dir.resolve("f" + orden + ".tif");
            // 40×37 con teselas de 16: bordes incompletos en las dos direcciones
            TiffDePrueba.flotanteEnTeselas(p, orden, 40, 37, 16, 75.0, 30.0, 0.25, GeoTiffTest::valor);
            try (GeoTiff t = GeoTiff.abrir(p)) {
                assertEquals(40, t.ancho);
                assertEquals(37, t.alto);
                assertEquals(75.125, t.lonOrigen, 1e-12, "centro del primer píxel (PixelIsArea)");
                assertEquals(29.875, t.latOrigen, 1e-12);
                float[] v = new float[40 * 37];
                t.leerVentana(0, 0, 40, 37, v);
                for (int f = 0; f < 37; f++) {
                    for (int c = 0; c < 40; c++) {
                        assertEquals(valor(c, f), v[f * 40 + c], 0f, "(" + c + ", " + f + ") " + orden);
                    }
                }
            }
        }
    }

    @Test
    void ventanaQueSaleDeLaImagenRepiteElBorde() throws IOException {
        Path p = dir.resolve("b.tif");
        TiffDePrueba.flotanteEnTeselas(p, ByteOrder.LITTLE_ENDIAN, 20, 20, 16, 0, 0, 1, GeoTiffTest::valor);
        try (GeoTiff t = GeoTiff.abrir(p)) {
            float[] v = new float[4 * 4];
            t.leerVentana(18, -2, 4, 4, v);
            assertEquals(valor(18, 0), v[0]);
            assertEquals(valor(19, 0), v[3], "columna 21 → borde 19");
            assertEquals(valor(19, 1), v[3 * 4 + 3]);
        }
    }

    @Test
    void leeEnteroEnTirasConPredictor2() throws IOException {
        Path p = dir.resolve("i.tif");
        TiffDePrueba.enteroEnTiras(p, ByteOrder.BIG_ENDIAN, 11, 8, -180, 90, 1.0, (c, f) -> c * 1000 - f * 4000);
        try (GeoTiff t = GeoTiff.abrir(p)) {
            float[] v = new float[11 * 8];
            t.leerVentana(0, 0, 11, 8, v);
            for (int f = 0; f < 8; f++) {
                for (int c = 0; c < 11; c++) {
                    assertEquals((short) (c * 1000 - f * 4000), v[f * 11 + c], 0f);
                }
            }
        }
    }

    @Test
    void leeByteConLzwCambiandoDeAnchoDeCodigo() throws IOException {
        Path p = dir.resolve("k.tif");
        // 300×40 con valores variados: la tabla pasa de 511 entradas (códigos de 9 y 10 bits)
        TiffDePrueba.byteLzw(p, ByteOrder.LITTLE_ENDIAN, 300, 40, -180, 90, 1 / 120.0,
                (c, f) -> (c * 7 + f * 13 + (c * f) % 11) % 31);
        try (GeoTiff t = GeoTiff.abrir(p)) {
            float[] v = new float[300 * 40];
            t.leerVentana(0, 0, 300, 40, v);
            for (int f = 0; f < 40; f++) {
                for (int c = 0; c < 300; c++) {
                    assertEquals((c * 7 + f * 13 + (c * f) % 11) % 31, v[f * 300 + c], 0f, "(" + c + ", " + f + ")");
                }
            }
        }
    }

    @Test
    void lzwRepiteCadenaRecienAgregada() throws IOException {
        // "aaaa…": el caso del código que todavía no está en la tabla (KwKwK)
        byte[] datos = new byte[1000];
        java.util.Arrays.fill(datos, (byte) 7);
        byte[] salida = new byte[1000];
        GeoTiff.descomprimirLzw(TiffDePrueba.lzw(datos), salida);
        assertArrayEquals(datos, salida);
    }

    @Test
    void rechazaLoQueNoEsTiff() throws IOException {
        Path p = dir.resolve("x.tif");
        Files.write(p, new byte[]{'P', 'K', 3, 4, 0, 0, 0, 0});
        assertThrows(IOException.class, () -> GeoTiff.abrir(p));
    }
}
