package com.example.minecraftlodmod.tierra;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MascaraAguaTest {

    @TempDir
    Path dir;

    /** Shapefile de un polígono con anillos dados como {lon, lat, lon, lat, ...}. */
    private Path shapefile(double[]... anillos) throws IOException {
        int puntos = 0;
        for (double[] a : anillos) puntos += a.length / 2;
        int contenido = 44 + 4 * anillos.length + 16 * puntos;
        ByteBuffer b = ByteBuffer.allocate(100 + 8 + contenido);
        b.order(ByteOrder.BIG_ENDIAN).putInt(0, 9994).putInt(24, (100 + 8 + contenido) / 2);
        b.order(ByteOrder.LITTLE_ENDIAN).putInt(28, 1000).putInt(32, 5);
        b.order(ByteOrder.BIG_ENDIAN).putInt(100, 1).putInt(104, contenido / 2);
        b.order(ByteOrder.LITTLE_ENDIAN).position(108);
        b.putInt(5).putDouble(0).putDouble(0).putDouble(0).putDouble(0).putInt(anillos.length).putInt(puntos);
        int desde = 0;
        for (double[] a : anillos) {
            b.putInt(desde);
            desde += a.length / 2;
        }
        for (double[] a : anillos) for (double v : a) b.putDouble(v);
        Path p = dir.resolve("agua.shp");
        Files.write(p, b.array());
        return p;
    }

    @Test
    void lagoConIslaEnElMedio() throws IOException {
        // Grilla de 20×20 muestras de 1°, centros en lon 0.5..19.5, lat 19.5..0.5
        FormatoLodt.Cabecera cab = new FormatoLodt.Cabecera(20, 20, 8, 0.5, 19.5, 1.0);
        Path shp = shapefile(
                new double[]{2, 2, 2, 12, 12, 12, 12, 2, 2, 2},   // lago de lon 2..12, lat 2..12
                new double[]{5, 5, 9, 5, 9, 9, 5, 9, 5, 5});      // isla de lon 5..9, lat 5..9
        List<MascaraAgua.Poligono> poligonos = MascaraAgua.leerShapefile(shp);
        assertEquals(1, poligonos.size());
        assertEquals(2, poligonos.get(0).anillos().size());
        long[] mascara = new long[(20 * 20 + 63) / 64];
        long marcadas = MascaraAgua.rasterizar(poligonos, cab, mascara);
        // Lago: 10×10 centros, menos la isla: 4×4 centros
        assertEquals(100 - 16, marcadas);
        assertTrue(dentro(mascara, cab, 2.5, 2.5));
        assertTrue(dentro(mascara, cab, 11.5, 11.5));
        assertFalse(dentro(mascara, cab, 7.5, 7.5), "isla");
        assertFalse(dentro(mascara, cab, 12.5, 7.5), "fuera");
        assertFalse(dentro(mascara, cab, 1.5, 7.5), "fuera");
    }

    private static boolean dentro(long[] m, FormatoLodt.Cabecera cab, double lon, double lat) {
        int c = (int) Math.round((lon - cab.lonOrigen()) / cab.paso());
        int f = (int) Math.round((cab.latOrigen() - lat) / cab.paso());
        long i = (long) f * cab.ancho() + c;
        return (m[(int) (i >>> 6)] & (1L << (i & 63))) != 0;
    }

    @Test
    void rechazaLoQueNoEsShapefile() throws IOException {
        Path p = dir.resolve("x.shp");
        Files.write(p, new byte[200]);
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> MascaraAgua.leerShapefile(p));
    }
}
