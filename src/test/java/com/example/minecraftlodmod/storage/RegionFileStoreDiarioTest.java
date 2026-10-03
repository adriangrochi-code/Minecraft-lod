package com.example.minecraftlodmod.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** Índice append-only: cada lote agrega un bloque de diario en vez de reescribir el índice entero. */
class RegionFileStoreDiarioTest {

    private static final RegionFileStore.ClaveRegion REGION = new RegionFileStore.ClaveRegion((byte) 0, -2, 5);

    private static byte[] datos(int semilla) {
        byte[] b = new byte[200];
        new Random(semilla).nextBytes(b);
        return b;
    }

    @Test
    void cadaLoteAgregaSoloSuBloqueYSeLeeAlReabrir(@TempDir Path dir) throws Exception {
        long tamanoFoto;
        try (RegionFileStore store = new RegionFileStore(dir, 3, 0)) {
            for (int i = 0; i < 1000; i++) {
                store.guardar(REGION, i, datos(i));
            }
            store.vaciar();
            tamanoFoto = Files.size(store.archivoDe(REGION));
            store.guardar(REGION, 5000, datos(5000));
            store.guardar(REGION, 7, datos(-7)); // reemplazo: el diario manda sobre la foto
            store.vaciar();
            long tamano = Files.size(store.archivoDe(REGION));
            assertEquals(tamanoFoto + 4 + 2 * 24 + 4, tamano, "solo el bloque nuevo, no el índice de 1000 nodos");
        }
        try (RegionFileStore reabierto = new RegionFileStore(dir, 3, 0)) {
            assertArrayEquals(datos(5000), reabierto.leer(REGION, 5000));
            assertArrayEquals(datos(-7), reabierto.leer(REGION, 7));
            assertArrayEquals(datos(999), reabierto.leer(REGION, 999));
            assertTrue(reabierto.contiene(REGION, 5000));
        }
    }

    @Test
    void unBloqueCortadoSeIgnoraYSeRecortaAlEscribir(@TempDir Path dir) throws Exception {
        Path indice;
        try (RegionFileStore store = new RegionFileStore(dir, 3, 0)) {
            for (int i = 0; i < 10; i++) {
                store.guardar(REGION, i, datos(i));
            }
            store.vaciar();
            store.guardar(REGION, 100, datos(100));
            store.vaciar();
            indice = store.archivoDe(REGION);
        }
        // Corte de luz a mitad de un bloque: cantidad escrita, entradas a medias.
        Files.write(indice, new byte[]{0, 0, 0, 3, 1, 2, 3, 4, 5}, StandardOpenOption.APPEND);
        long conBasura = Files.size(indice);
        try (RegionFileStore store = new RegionFileStore(dir, 3, 0)) {
            assertArrayEquals(datos(100), store.leer(REGION, 100), "lo de antes del corte vale");
            assertArrayEquals(datos(3), store.leer(REGION, 3));
            store.guardar(REGION, 200, datos(200));
            store.vaciar();
            assertTrue(Files.size(indice) < conBasura + 4 + 24 + 4, "la basura se recortó antes de agregar");
        }
        try (RegionFileStore store = new RegionFileStore(dir, 3, 0)) {
            assertArrayEquals(datos(200), store.leer(REGION, 200), "lo agregado después del recorte se lee");
            assertArrayEquals(datos(100), store.leer(REGION, 100));
        }
    }

    @Test
    void unBloqueConCrcMalTerminaElDiario(@TempDir Path dir) throws Exception {
        Path indice;
        long antesDelBloque;
        try (RegionFileStore store = new RegionFileStore(dir, 3, 0)) {
            store.guardar(REGION, 1, datos(1));
            store.vaciar();
            indice = store.archivoDe(REGION);
            antesDelBloque = Files.size(indice);
            store.guardar(REGION, 2, datos(2));
            store.vaciar();
        }
        byte[] bytes = Files.readAllBytes(indice);
        bytes[(int) antesDelBloque + 10] ^= 0x55; // dentro de la entrada del bloque
        Files.write(indice, bytes);
        try (RegionFileStore store = new RegionFileStore(dir, 3, 0)) {
            assertArrayEquals(datos(1), store.leer(REGION, 1));
            assertNull(store.leer(REGION, 2), "un bloque dañado no se aplica (el nodo se regenera)");
        }
    }

    @Test
    void muchosLotesJuntanElDiarioEnUnaFotoSinPerderNada(@TempDir Path dir) throws Exception {
        try (RegionFileStore store = new RegionFileStore(dir, 3, 0)) {
            for (int lote = 0; lote < 60; lote++) {
                for (int i = 0; i < 100; i++) {
                    store.guardar(REGION, lote * 100L + i, datos(lote * 100 + i));
                }
                store.vaciar();
            }
            // 6000 entradas: el diario pasó el mínimo y se juntó en una foto al menos una vez.
            long tamano = Files.size(store.archivoDe(REGION));
            // Sin juntar serían la foto del primer lote + 59 bloques (8 bytes de cantidad y CRC cada uno).
            long sinJuntar = 8 + 26 + 6000L * 24 + 59 * 8;
            assertTrue(tamano < sinJuntar, "el diario se juntó en una foto: " + tamano + " < " + sinJuntar);
        }
        try (RegionFileStore store = new RegionFileStore(dir, 3, 0)) {
            for (int i = 0; i < 6000; i += 37) {
                assertArrayEquals(datos(i), store.leer(REGION, i));
            }
        }
    }
}
