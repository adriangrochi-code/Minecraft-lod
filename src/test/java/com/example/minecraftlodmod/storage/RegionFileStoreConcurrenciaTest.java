package com.example.minecraftlodmod.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Las lecturas no toman el candado de la región (el hilo de escritura lo tiene
 * mientras baja a disco): con escrituras, compactaciones y vaciados en curso,
 * cada lectura tiene que dar alguna versión válida del nodo, nunca null ni error.
 */
class RegionFileStoreConcurrenciaTest {

    private static final RegionFileStore.ClaveRegion REGION = new RegionFileStore.ClaveRegion((byte) 0, 1, 2);
    private static final int NODOS = 40;
    private static final int TAMANO = 30_000;

    /** Versión v del nodo: aleatorio (no se comprime) con el nodo y la versión en los primeros bytes. */
    private static byte[] datos(int nodo, int version) {
        byte[] b = new byte[TAMANO];
        new Random(nodo * 100_003L + version).nextBytes(b);
        b[0] = (byte) nodo;
        b[1] = (byte) version;
        return b;
    }

    @Test
    void leerMientrasSeEscribeYCompactaSiempreDaUnaVersionValida(@TempDir Path dir) throws Exception {
        try (RegionFileStore store = new RegionFileStore(dir, 7, 0, 1L << 20)) {
            for (int i = 0; i < NODOS; i++) {
                store.guardar(REGION, i, datos(i, 0));
            }
            store.vaciar();
            AtomicBoolean fin = new AtomicBoolean();
            AtomicReference<Throwable> error = new AtomicReference<>();
            List<Thread> lectores = new ArrayList<>();
            for (int t = 0; t < 3; t++) {
                int semilla = t;
                Thread lector = new Thread(() -> {
                    Random r = new Random(semilla);
                    try {
                        while (!fin.get()) {
                            int nodo = r.nextInt(NODOS);
                            byte[] leido = store.leer(REGION, nodo);
                            assertNotNull(leido, "nodo " + nodo + " desapareció");
                            assertEquals(nodo, leido[0]);
                            assertArrayEquals(datos(nodo, leido[1]), leido, "versión " + leido[1] + " corrupta");
                            assertTrue(store.contiene(REGION, nodo));
                        }
                    } catch (Throwable e) {
                        error.compareAndSet(null, e);
                    }
                });
                lector.start();
                lectores.add(lector);
            }
            // Reescribir todo varias veces: append, índice nuevo y compactaciones (generaciones nuevas).
            for (int version = 1; version <= 12; version++) {
                for (int i = 0; i < NODOS; i++) {
                    store.guardar(REGION, i, datos(i, version));
                }
                store.vaciar();
            }
            fin.set(true);
            for (Thread lector : lectores) {
                lector.join();
            }
            if (error.get() != null) {
                fail(error.get());
            }
            for (int i = 0; i < NODOS; i++) {
                assertArrayEquals(datos(i, 12), store.leer(REGION, i));
            }
        }
    }
}
