package com.example.minecraftlodmod.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BoundedRegionCacheTest {

    @Test
    void guardaYRecuperaDentroDelPresupuesto() {
        BoundedRegionCache cache = new BoundedRegionCache(1000);
        byte[] datos = new byte[100];

        cache.poner(1L, datos);

        assertArrayEquals(datos, cache.obtener(1L));
        assertEquals(100, cache.bytesUsados());
    }

    @Test
    void desalojaLoMenosUsadoRecientementeAlSuperarElPresupuesto() {
        BoundedRegionCache cache = new BoundedRegionCache(250); // espacio para ~2 entradas de 100 bytes

        cache.poner(1L, new byte[100]);
        cache.poner(2L, new byte[100]);
        cache.poner(3L, new byte[100]); // esto debería empujar al 1 (el más viejo) afuera

        assertNull(cache.obtener(1L), "La entrada 1 debería haber sido desalojada por LRU");
        assertNotNull(cache.obtener(2L));
        assertNotNull(cache.obtener(3L));
    }

    @Test
    void accederAUnaEntradaLaPreservaFrenteAUnaMasNuevaMenosUsada() {
        BoundedRegionCache cache = new BoundedRegionCache(250);

        cache.poner(1L, new byte[100]);
        cache.poner(2L, new byte[100]);
        cache.obtener(1L); // acceder a 1L la vuelve la "más recientemente usada"

        cache.poner(3L, new byte[100]); // ahora debería desalojar a 2L, no a 1L

        assertNotNull(cache.obtener(1L), "1L fue accedida recientemente, no debería desalojarse");
        assertNull(cache.obtener(2L), "2L es la menos usada recientemente, debería desalojarse");
    }

    @Test
    void reemplazarUnaEntradaActualizaElConteoDeBytesCorrectamente() {
        BoundedRegionCache cache = new BoundedRegionCache(1000);

        cache.poner(1L, new byte[50]);
        cache.poner(1L, new byte[200]); // reemplaza, no debería duplicar el conteo

        assertEquals(200, cache.bytesUsados());
    }

    @Test
    void desdeMbConvierteCorrectamenteAlPresupuestoEnBytes() {
        BoundedRegionCache cache = BoundedRegionCache.desdeMb(1);
        assertEquals(1024L * 1024L, cache.presupuestoBytes());
    }

    @Test
    void rechazaPresupuestoNoPositivo() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedRegionCache(0));
        assertThrows(IllegalArgumentException.class, () -> new BoundedRegionCache(-10));
    }

    @Test
    void limpiarVaciaElCacheYReseteaElContador() {
        BoundedRegionCache cache = new BoundedRegionCache(1000);
        cache.poner(1L, new byte[100]);

        cache.limpiar();

        assertEquals(0, cache.bytesUsados());
        assertEquals(0, cache.cantidadEntradas());
        assertNull(cache.obtener(1L));
    }
}
