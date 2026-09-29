package com.example.minecraftlodmod.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class RegionFileStoreAppendTest {

    private static final RegionFileStore.ClaveRegion REGION = new RegionFileStore.ClaveRegion((byte) 0, 3, -4);

    /** Datos que no se comprimen (aleatorios): el tamaño en disco es predecible. */
    private static byte[] datos(int semilla, int tamano) {
        byte[] b = new byte[tamano];
        new Random(semilla).nextBytes(b);
        return b;
    }

    private static long tamanoDatos(Path dir) throws Exception {
        try (var archivos = Files.list(dir.resolve("dim0"))) {
            return archivos.filter(p -> p.toString().endsWith(".mlod")).mapToLong(p -> p.toFile().length()).sum();
        }
    }

    @Test
    void unVaciadoAgregaSoloLoNuevo(@TempDir Path dir) throws Exception {
        try (RegionFileStore store = new RegionFileStore(dir, 1, 0)) {
            for (int i = 0; i < 50; i++) {
                store.guardar(REGION, i, datos(i, 1000));
            }
            store.vaciar();
            long antes = tamanoDatos(dir);

            store.guardar(REGION, 999, datos(999, 1000));
            store.vaciar();
            long despues = tamanoDatos(dir);

            assertTrue(despues - antes < 1200, "Creció solo lo del nodo nuevo (~1 KB comprimido), no la región entera: "
                    + antes + " -> " + despues);
            for (int i = 0; i < 50; i += 7) {
                assertArrayEquals(datos(i, 1000), store.leer(REGION, i));
            }
            assertArrayEquals(datos(999, 1000), store.leer(REGION, 999));
        }
    }

    @Test
    void reescribirMuchoCompactaYConservaTodo(@TempDir Path dir) throws Exception {
        try (RegionFileStore store = new RegionFileStore(dir, 1, 0)) {
            // 40 nodos de ~50 KB = ~2 MB vivos; reescribirlos 3 veces deja >50% muerto.
            for (int vuelta = 0; vuelta < 4; vuelta++) {
                for (int i = 0; i < 40; i++) {
                    store.guardar(REGION, i, datos(vuelta * 1000 + i, 50_000));
                }
                store.vaciar();
            }
            long enDisco = tamanoDatos(dir);
            // Invariante: después de cada vaciado, lo muerto nunca supera a lo vivo (+ cabeceras de compresión).
            assertTrue(enDisco <= 40L * 50_000 * 2 * 101 / 100, "Lo muerto no puede superar a lo vivo: " + enDisco);
            try (var archivos = Files.list(dir.resolve("dim0"))) {
                assertTrue(archivos.anyMatch(p -> p.getFileName().toString().matches("r\\.3\\.-4\\.[1-9]\\d*\\.mlod")),
                        "Tiene que haber compactado al menos una vez (generación > 0)");
            }
            for (int i = 0; i < 40; i += 5) {
                assertArrayEquals(datos(3000 + i, 50_000), store.leer(REGION, i), "Queda la última versión");
            }
        }
        try (RegionFileStore reabierto = new RegionFileStore(dir, 1, 0)) {
            assertArrayEquals(datos(3000 + 7, 50_000), reabierto.leer(REGION, 7), "El índice apunta a la generación nueva");
        }
    }

    @Test
    void bytesHuerfanosAlFinalNoRompenNada(@TempDir Path dir) throws Exception {
        try (RegionFileStore store = new RegionFileStore(dir, 1, 0)) {
            store.guardar(REGION, 1, datos(1, 500));
            store.vaciar();
        }
        // Simula un corte después de escribir datos y antes de actualizar el índice.
        try (var archivos = Files.list(dir.resolve("dim0"))) {
            Path datosRegion = archivos.filter(p -> p.toString().endsWith(".mlod")).findFirst().orElseThrow();
            Files.write(datosRegion, datos(77, 300), StandardOpenOption.APPEND);
        }
        try (RegionFileStore reabierto = new RegionFileStore(dir, 1, 0)) {
            assertArrayEquals(datos(1, 500), reabierto.leer(REGION, 1));
            reabierto.guardar(REGION, 2, datos(2, 500));
            reabierto.vaciar();
            assertArrayEquals(datos(2, 500), reabierto.leer(REGION, 2), "Se sigue agregando después de los huérfanos");
            assertArrayEquals(datos(1, 500), reabierto.leer(REGION, 1));
        }
    }
}
