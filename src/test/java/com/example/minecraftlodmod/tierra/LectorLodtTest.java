package com.example.minecraftlodmod.tierra;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LectorLodtTest {

    @TempDir
    Path dir;

    static short elev(int c, int f) {
        return (short) ((c * 37 - f * 53) % 12000 - 6000);
    }

    static byte bioma(int c, int f) {
        return (byte) ((c / 7 + f / 5) & 0xFF);
    }

    /** Grilla sintética de ancho × alto con teselas de {@code lado}. */
    static Path escribir(Path archivo, int ancho, int alto, int lado) throws IOException {
        FormatoLodt.Cabecera cab = new FormatoLodt.Cabecera(ancho, alto, lado, -179.5, 89.5, 1.0);
        EscritorLodt.escribir(archivo, cab, (col0, fila0, a, h, e, b, nv) -> {
            for (int f = 0; f < h; f++) {
                for (int c = 0; c < a; c++) {
                    e[f * a + c] = elev(col0 + c, fila0 + f);
                    b[f * a + c] = bioma(col0 + c, fila0 + f);
                }
            }
        });
        return archivo;
    }

    @Test
    void idaYVueltaConTeselasDeBordeIncompletas() throws IOException {
        Path p = escribir(dir.resolve("a.lodt"), 100, 70, 32);
        assertFalse(Files.exists(dir.resolve("a.lodt.parcial")), "no queda el temporal");
        try (LectorLodt l = LectorLodt.abrir(p, 1 << 24)) {
            assertEquals(100, l.cabecera.ancho());
            assertEquals(4 * 3, l.cabecera.cantidadTeselas());
            for (int f = 0; f < 70; f++) {
                for (int c = 0; c < 100; c++) {
                    assertEquals(elev(c, f), l.elevacion(c, f));
                    assertEquals(bioma(c, f), l.bioma(c, f));
                }
            }
        }
    }

    @Test
    void laCacheRespetaElTopeDeBytes() throws IOException {
        Path p = escribir(dir.resolve("c.lodt"), 256, 256, 32); // 64 teselas de ~3 KB
        long porTesela = 32 * 32 * 3 + 64;
        try (LectorLodt l = LectorLodt.abrir(p, porTesela * 5)) {
            for (int f = 0; f < 256; f += 32) {
                for (int c = 0; c < 256; c += 32) {
                    assertEquals(elev(c, f), l.elevacion(c, f));
                }
            }
            assertTrue(l.bytesEnCache() <= porTesela * 5, "bytes: " + l.bytesEnCache());
            assertTrue(l.teselasEnCache() <= 5);
            // La más usada sobrevive al desalojo (LRU)
            for (int k = 0; k < 20; k++) {
                l.elevacion(0, 0);
                l.elevacion(32 * (k % 8), 128);
            }
            LectorLodt.Tesela t = l.tesela(0, 0);
            l.elevacion(250, 250);
            assertTrue(t == l.tesela(0, 0), "la tesela usada seguido no se desaloja");
        }
    }

    @Test
    void lecturaDesdeVariosHilos() throws Exception {
        Path p = escribir(dir.resolve("h.lodt"), 512, 256, 64);
        try (LectorLodt l = LectorLodt.abrir(p, 64L * 64 * 3 * 6)) {
            AtomicInteger errores = new AtomicInteger();
            List<Thread> hilos = new ArrayList<>();
            for (int h = 0; h < 8; h++) {
                int semilla = h;
                Thread t = new Thread(() -> {
                    java.util.Random r = new java.util.Random(semilla);
                    for (int k = 0; k < 20000; k++) {
                        int c = r.nextInt(512), f = r.nextInt(256);
                        if (l.elevacion(c, f) != elev(c, f)) errores.incrementAndGet();
                    }
                });
                hilos.add(t);
                t.start();
            }
            for (Thread t : hilos) t.join();
            assertEquals(0, errores.get());
        }
    }

    @Test
    void rechazaArchivoAjenoOTruncado() throws IOException {
        Path ajeno = dir.resolve("x.lodt");
        Files.write(ajeno, new byte[64]);
        assertThrows(IOException.class, () -> LectorLodt.abrir(ajeno, 1 << 20));

        Path p = escribir(dir.resolve("t.lodt"), 64, 64, 32);
        try (RandomAccessFile raf = new RandomAccessFile(p.toFile(), "rw")) {
            raf.setLength(raf.length() - 10);
        }
        assertThrows(IOException.class, () -> LectorLodt.abrir(p, 1 << 20));
    }

    @Test
    void cabeceraGlobal() {
        assertTrue(new FormatoLodt.Cabecera(43200, 21600, 256, -179.99583333333334, 89.99583333333334, 1 / 120.0).global());
        assertFalse(new FormatoLodt.Cabecera(1200, 1200, 256, 80.004, 29.996, 1 / 120.0).global());
    }
}
