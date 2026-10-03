package com.example.minecraftlodmod.generation;

import com.example.minecraftlodmod.storage.RegionFileStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarcaExtraidoTest {

    @Test
    void laMarcaNoChocaConNingunaClaveAproximadaFina() {
        long marca = GeneradorLocal.claveMarca(5, 7);
        Set<Long> aproximadas = new HashSet<>();
        for (int nivel = TerrenoAproximado.NIVEL_FINO_MIN; nivel < TerrenoAproximado.NIVEL_MIN; nivel++) {
            for (int sy = -64; sy < 128; sy++) {
                aproximadas.add(TerrenoAproximado.claveNodo(nivel, 5, sy, 7));
            }
            aproximadas.add(TerrenoAproximado.claveMarcaFina(nivel, 5, 7));
        }
        assertFalse(aproximadas.contains(marca), "La marca de extraído no puede caer en una clave aproximada");
        // La vieja sí chocaba (el bug): el nodo fino de nivel 2 de la sección 0.
        assertTrue(aproximadas.contains(GeneradorLocal.claveMarcaVieja(5, 7)));
        assertNotEquals(marca, GeneradorLocal.claveMarcaVieja(5, 7));
    }

    @Test
    void enLaClaveViejaSoloCuentaLaMarcaDeUnByte(@TempDir Path dir) {
        RegionFileStore store = new RegionFileStore(dir, 1L, 1000);
        var region = GeneradorLocal.claveRegion((byte) 0, 3, 4);
        // Un nodo aproximado (más largo) en la clave vieja: no es una marca.
        store.guardar(region, GeneradorLocal.claveMarcaVieja(3, 4), new byte[]{2, 0, 1, 0, 9, 9, 9, 9});
        assertFalse(GeneradorLocal.tieneMarca(store, region, 3, 4));
        // La marca vieja de verdad (datos de antes): sigue valiendo.
        store.guardar(region, GeneradorLocal.claveMarcaVieja(3, 4), new byte[]{GeneradorLocal.VERSION_MARCA});
        assertTrue(GeneradorLocal.tieneMarca(store, region, 3, 4));
        // La nueva.
        store.guardar(region, GeneradorLocal.claveMarca(8, 9), new byte[]{GeneradorLocal.VERSION_MARCA});
        assertTrue(GeneradorLocal.tieneMarca(store, GeneradorLocal.claveRegion((byte) 0, 8, 9), 8, 9));
    }
}
