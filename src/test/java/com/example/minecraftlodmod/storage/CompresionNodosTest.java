package com.example.minecraftlodmod.storage;

import com.example.minecraftlodmod.core.OctreeNode;
import com.example.minecraftlodmod.core.SuperVoxel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class CompresionNodosTest {

    /** Nodo de nivel 0 parecido a terreno: capas por altura con algo de ruido, mitad de arriba aire. */
    private static byte[] nodoDeTerreno(long semilla) {
        Random azar = new Random(semilla);
        SuperVoxel[] v = new SuperVoxel[4096];
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    boolean aire = y > 8 + azar.nextInt(2);
                    int gris = 110 + azar.nextInt(4);
                    v[(x * 16 + y) * 16 + z] = aire
                            ? new SuperVoxel((byte) 0, (byte) 0, (byte) 0, (byte) y, SuperVoxel.Material.AIRE, (byte) 0)
                            : new SuperVoxel((byte) gris, (byte) gris, (byte) gris, (byte) y,
                            SuperVoxel.Material.SOLIDO, (byte) 0).conLuzHorneada(y == 8 ? 15 : 0);
                }
            }
        }
        return OctreeNodeCodec.serializar(OctreeNode.mixto(0, 0, 0, 0, 16, v), 4096);
    }

    @Test
    void idaYVueltaSinPerdida() {
        byte[] original = nodoDeTerreno(1);
        assertArrayEquals(original, CompresionNodos.descomprimir(CompresionNodos.comprimir(original)));
    }

    @Test
    void comprimeTerrenoVariasVeces() {
        byte[] original = nodoDeTerreno(2);
        byte[] comprimido = CompresionNodos.comprimir(original);
        // Desde 0.26.34 un nodo así va con paleta (un byte por vóxel): ya llega compacto y
        // Deflate gana menos en proporción, aunque el resultado sea más chico que con RLE.
        assertTrue(comprimido.length * 2 < original.length,
                "Esperaba al menos 2x: " + original.length + " -> " + comprimido.length);
    }

    @Test
    void rechazaDatosCorruptos() {
        byte[] comprimido = CompresionNodos.comprimir(nodoDeTerreno(3));
        comprimido[comprimido.length / 2] ^= 0x5A;
        comprimido[comprimido.length / 2 + 1] ^= 0x3C;
        assertThrows(IllegalArgumentException.class, () -> CompresionNodos.descomprimir(comprimido));
    }

    @Test
    void elStoreDevuelveLoGuardadoDesdeMemoriaYDesdeDisco(@TempDir Path dir) throws Exception {
        byte[] nodo = nodoDeTerreno(4);
        RegionFileStore.ClaveRegion region = new RegionFileStore.ClaveRegion((byte) 0, 1, -2);
        try (RegionFileStore store = new RegionFileStore(dir, 7, 0, 1 << 20)) {
            store.guardar(region, 42, nodo);
            store.guardar(region, 43, new byte[0]);
            assertArrayEquals(nodo, store.leer(region, 42), "Desde pendientes");
            store.vaciar();
            assertEquals(0, store.bytesPendientes());
            assertArrayEquals(nodo, store.leer(region, 42), "Desde disco");
            assertArrayEquals(nodo, store.leer(region, 42), "Desde el cache de lectura");
            assertTrue(store.bytesEnCache() > 0);
            assertArrayEquals(new byte[0], store.leer(region, 43), "La marca vacía sigue vacía");
        }
        try (RegionFileStore reabierto = new RegionFileStore(dir, 7, 0, 1 << 20)) {
            assertArrayEquals(nodo, reabierto.leer(region, 42), "Persistido comprimido y legible al reabrir");
        }
    }

    @Test
    void superarLaRamPendienteVaciaADiscoSinEsperarAlPeriodo(@TempDir Path dir) throws Exception {
        RegionFileStore.ClaveRegion region = new RegionFileStore.ClaveRegion((byte) 0, 0, 0);
        // Período de 1 hora: solo el límite de RAM puede disparar la escritura.
        try (RegionFileStore store = new RegionFileStore(dir, 7, 3_600_000, 64 * 1024)) {
            for (int i = 0; i < 200; i++) {
                store.guardar(region, i, nodoDeTerreno(100 + i));
            }
            long limite = System.currentTimeMillis() + 10_000;
            while (store.bytesPendientes() > 16 * 1024 && System.currentTimeMillis() < limite) {
                Thread.sleep(50);
            }
            assertTrue(store.bytesPendientes() <= 16 * 1024 + 8 * 1024,
                    "Pendientes debería volver bajo el límite (1/4 de 64 KB): " + store.bytesPendientes());
            for (int i = 0; i < 200; i += 37) {
                assertArrayEquals(nodoDeTerreno(100 + i), store.leer(region, i));
            }
        }
    }

    @Test
    void laClaveDeCacheSeparaDimensionesYRegiones() {
        long a = RegionFileStore.claveCache(new RegionFileStore.ClaveRegion((byte) 0, -1, 5), 99);
        long b = RegionFileStore.claveCache(new RegionFileStore.ClaveRegion((byte) 1, -1, 5), 99);
        long c = RegionFileStore.claveCache(new RegionFileStore.ClaveRegion((byte) 0, 5, -1), 99);
        assertNotEquals(a, b);
        assertNotEquals(a, c);
        assertEquals(RegionFileStore.SIN_CACHE,
                RegionFileStore.claveCache(new RegionFileStore.ClaveRegion((byte) 0, 9000, 0), 99));
    }
}
