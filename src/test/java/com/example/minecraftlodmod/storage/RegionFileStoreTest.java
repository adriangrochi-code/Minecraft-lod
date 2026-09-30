package com.example.minecraftlodmod.storage;

import com.example.minecraftlodmod.core.OctreeNode;
import com.example.minecraftlodmod.core.SuperVoxel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RegionFileStoreTest {

    private static final long HASH = 0x1234_5678_9ABC_DEF0L;
    private static final RegionFileStore.ClaveRegion REGION = new RegionFileStore.ClaveRegion((byte) 0, 3, -7);

    @TempDir
    Path dir;

    private RegionFileStore storeSinHilo() {
        return new RegionFileStore(dir, HASH, 0);
    }

    private static byte[] datos(int semilla, int largo) {
        byte[] b = new byte[largo];
        for (int i = 0; i < largo; i++) {
            b[i] = (byte) (semilla * 31 + i);
        }
        return b;
    }

    @Test
    void loGuardadoSeLeeAntesDeBajarADisco() throws IOException {
        try (RegionFileStore store = storeSinHilo()) {
            store.guardar(REGION, 1L, datos(1, 10));

            assertArrayEquals(datos(1, 10), store.leer(REGION, 1L));
            assertFalse(Files.exists(store.archivoDe(REGION)), "Write-behind: todavía no debería tocar disco");
        }
    }

    @Test
    void persisteEntreInstancias() throws IOException {
        try (RegionFileStore store = storeSinHilo()) {
            store.guardar(REGION, 1L, datos(1, 10));
            store.guardar(REGION, 2L, datos(2, 300));
        } // close() vacía

        try (RegionFileStore store = storeSinHilo()) {
            assertArrayEquals(datos(1, 10), store.leer(REGION, 1L));
            assertArrayEquals(datos(2, 300), store.leer(REGION, 2L));
            assertNull(store.leer(REGION, 3L));
        }
    }

    @Test
    void variosLotesSeFusionanSinPerderNodosAnteriores() throws IOException {
        try (RegionFileStore store = storeSinHilo()) {
            store.guardar(REGION, 1L, datos(1, 10));
            store.vaciar();
            store.guardar(REGION, 2L, datos(2, 20));
            store.guardar(REGION, 1L, datos(9, 5)); // reemplaza al anterior
            store.vaciar();
        }

        try (RegionFileStore store = storeSinHilo()) {
            assertArrayEquals(datos(9, 5), store.leer(REGION, 1L));
            assertArrayEquals(datos(2, 20), store.leer(REGION, 2L));
        }
    }

    @Test
    void regionesYDimensionesDistintasNoSeMezclan() throws IOException {
        RegionFileStore.ClaveRegion otraRegion = new RegionFileStore.ClaveRegion((byte) 0, 4, -7);
        RegionFileStore.ClaveRegion otraDimension = new RegionFileStore.ClaveRegion((byte) 1, 3, -7);
        try (RegionFileStore store = storeSinHilo()) {
            store.guardar(REGION, 1L, datos(1, 10));
            store.guardar(otraRegion, 1L, datos(2, 10));
            store.guardar(otraDimension, 1L, datos(3, 10));
        }

        try (RegionFileStore store = storeSinHilo()) {
            assertArrayEquals(datos(1, 10), store.leer(REGION, 1L));
            assertArrayEquals(datos(2, 10), store.leer(otraRegion, 1L));
            assertArrayEquals(datos(3, 10), store.leer(otraDimension, 1L));
        }
    }

    @Test
    void unHashFuenteDistintoInvalidaElArchivo() throws IOException {
        try (RegionFileStore store = storeSinHilo()) {
            store.guardar(REGION, 1L, datos(1, 10));
        }

        try (RegionFileStore store = new RegionFileStore(dir, HASH + 1, 0)) {
            assertNull(store.leer(REGION, 1L));
            assertFalse(store.contiene(REGION, 1L));

            store.guardar(REGION, 2L, datos(2, 10));
            store.vaciar(); // reescribe con el hash nuevo, sin arrastrar nodos viejos
        }

        try (RegionFileStore store = new RegionFileStore(dir, HASH + 1, 0)) {
            assertNull(store.leer(REGION, 1L));
            assertArrayEquals(datos(2, 10), store.leer(REGION, 2L));
        }
    }

    @Test
    void loEscritoDespuesDeUnaConsultaSinIndiceSeLee() throws IOException {
        // indiceDe recuerda "sin índice": la escritura siguiente tiene que reemplazar esa marca.
        try (RegionFileStore store = storeSinHilo()) {
            assertNull(store.leer(REGION, 1L));
            assertFalse(store.contiene(REGION, 1L));
            store.guardar(REGION, 1L, datos(1, 10));
            store.vaciar();
            assertTrue(store.contiene(REGION, 1L));
            assertArrayEquals(datos(1, 10), store.leer(REGION, 1L));
            store.guardar(REGION, 2L, datos(2, 20));
            store.vaciar(); // segundo lote: copia el índice anterior sin perder nodos
            assertArrayEquals(datos(1, 10), store.leer(REGION, 1L));
            assertArrayEquals(datos(2, 20), store.leer(REGION, 2L));
        }
    }

    @Test
    void unArchivoCorruptoSeTrataComoAusente() throws IOException {
        try (RegionFileStore store = storeSinHilo()) {
            Files.createDirectories(store.archivoDe(REGION).getParent());
            Files.write(store.archivoDe(REGION), new byte[]{0, 0, 0, 3, 'x', 'y', 'z'});

            assertNull(store.leer(REGION, 1L));

            store.guardar(REGION, 1L, datos(1, 10));
            store.vaciar();
        }
        try (RegionFileStore store = storeSinHilo()) {
            assertArrayEquals(datos(1, 10), store.leer(REGION, 1L));
        }
    }

    @Test
    void invalidarRegionBorraDiscoYPendientes() throws IOException {
        try (RegionFileStore store = storeSinHilo()) {
            store.guardar(REGION, 1L, datos(1, 10));
            store.vaciar();
            store.guardar(REGION, 2L, datos(2, 10));

            store.invalidarRegion(REGION);

            assertNull(store.leer(REGION, 1L));
            assertNull(store.leer(REGION, 2L));
            assertFalse(Files.exists(store.archivoDe(REGION)));
        }
    }

    private static SuperVoxel voxel(int r) {
        return new SuperVoxel((byte) r, (byte) 10, (byte) 20, (byte) 64,
                SuperVoxel.Material.SOLIDO, (byte) 0);
    }

    @Test
    void guardaNodosSerializadosConElCodecReal() throws IOException {
        OctreeNode nodo = OctreeNode.homogeneo(2, 0, 0, 0, 4, voxel(42));
        byte[] serializado = OctreeNodeCodec.serializar(nodo, 64);
        long clave = RegionHeader.claveNodo(2, 1, 0, 1);

        try (RegionFileStore store = storeSinHilo()) {
            store.guardar(REGION, clave, serializado);
        }
        try (RegionFileStore store = storeSinHilo()) {
            assertArrayEquals(serializado, store.leer(REGION, clave));
        }
    }

    @Test
    @Timeout(5)
    void elHiloDeEscrituraBajaLosPendientesSolo() throws Exception {
        try (RegionFileStore store = new RegionFileStore(dir, HASH, 20)) {
            store.guardar(REGION, 1L, datos(1, 10));
            while (store.regionesPendientes() > 0 || !Files.exists(store.archivoDe(REGION))) {
                Thread.sleep(5);
            }
        }
        try (RegionFileStore store = storeSinHilo()) {
            assertArrayEquals(datos(1, 10), store.leer(REGION, 1L));
        }
    }

    @Test
    @Timeout(10)
    void escriturasConcurrentesNoPierdenNodos() throws Exception {
        int nodosPorHilo = 200;
        try (RegionFileStore store = new RegionFileStore(dir, HASH, 1)) {
            Thread[] hilos = new Thread[4];
            for (int h = 0; h < hilos.length; h++) {
                int base = h * nodosPorHilo;
                hilos[h] = new Thread(() -> {
                    for (int i = 0; i < nodosPorHilo; i++) {
                        store.guardar(REGION, base + i, datos(base + i, 8));
                    }
                });
                hilos[h].start();
            }
            for (Thread hilo : hilos) {
                hilo.join();
            }
        }
        try (RegionFileStore store = storeSinHilo()) {
            for (int i = 0; i < 4 * nodosPorHilo; i++) {
                assertArrayEquals(datos(i, 8), store.leer(REGION, i), "Falta el nodo " + i);
            }
        }
    }
}
