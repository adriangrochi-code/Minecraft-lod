package com.example.minecraftlodmod.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** RAM llena primero con lo cercano: precarga alrededor del jugador y desalojo de lo lejano. */
class PrecargaTest {

    @Test
    void desmezclarEsLaInversaExacta() {
        Random r = new Random(1);
        for (int i = 0; i < 10_000; i++) {
            long k = r.nextLong();
            assertEquals(k, BoundedRegionCache.desmezclar(BoundedRegionCache.mezclar(k)));
        }
    }

    @Test
    void alDesalojarSeVaPrimeroLoNoProtegido() {
        BoundedRegionCache cache = new BoundedRegionCache(1000);
        cache.protegerSi(clave -> clave < 100); // "cercanas"
        cache.poner(1, new byte[400]);   // protegida, la más vieja
        cache.poner(500, new byte[400]); // lejana
        cache.poner(2, new byte[400]);   // protegida: hay que desalojar 1 entrada
        assertTrue(cache.contiene(1), "la cercana sobrevive aunque sea la menos usada");
        assertTrue(cache.contiene(2));
        assertFalse(cache.contiene(500), "se fue la lejana");
        cache.poner(3, new byte[400]); // todas protegidas: LRU puro entre ellas
        assertTrue(cache.bytesUsados() <= 1000);
    }

    @Test
    void laPrecargaLlenaElCacheConLasRegionesCercanas(@TempDir Path dir) throws Exception {
        Random r = new Random(2);
        try (RegionFileStore escritura = new RegionFileStore(dir, 9, 0)) {
            for (int rx = -3; rx <= 3; rx++) {
                for (int nodo = 0; nodo < 20; nodo++) {
                    byte[] datos = new byte[1000];
                    r.nextBytes(datos);
                    escritura.guardar(new RegionFileStore.ClaveRegion((byte) 0, rx, 0), nodo, datos);
                }
            }
            escritura.vaciar();
        }
        // Cache para ~3 regiones (20 KB cada una, comprimido ≈ igual: datos aleatorios).
        try (RegionFileStore store = new RegionFileStore(dir, 9, 0, 4 * 90_000)) {
            assertEquals(0, store.bytesEnCache());
            store.ponerCentro((byte) 0, 0, 0, 3);
            long limite = System.nanoTime() + 5_000_000_000L;
            while (store.bytesEnCache() < 40_000 && System.nanoTime() < limite) {
                Thread.sleep(20);
            }
            Thread.sleep(200);
            long enCache = store.bytesEnCache();
            assertTrue(enCache >= 40_000, "precargó: " + enCache);
            assertTrue(enCache <= 4 * 90_000 * 3 / 4, "sin pasar del cache: " + enCache);
            // Lo cercano está en RAM: leerlo no toca el disco (sigue igual el cache).
            assertNotNull(store.leer(new RegionFileStore.ClaveRegion((byte) 0, 0, 0), 5));
        }
    }
}
