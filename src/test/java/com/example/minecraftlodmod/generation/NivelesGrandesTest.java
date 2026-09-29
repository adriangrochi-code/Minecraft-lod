package com.example.minecraftlodmod.generation;

import com.example.minecraftlodmod.core.SuperVoxel;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class NivelesGrandesTest {

    /** Mundo falso: secciones de nivel 4 sólidas bajo una altura, y los nodos grandes guardados. */
    private static final class MundoFalso implements NivelesGrandes.Acceso {
        final Map<List<Integer>, SuperVoxel[]> grandes = new HashMap<>();
        final int seccionesSolidas; // secciones Y [0, seccionesSolidas) son piedra
        int lecturasSeccion;

        MundoFalso(int seccionesSolidas) {
            this.seccionesSolidas = seccionesSolidas;
        }

        @Override
        public SuperVoxel[] seccion(int nivel, int x, int y, int z) {
            lecturasSeccion++;
            if (y < 0 || y >= seccionesSolidas) {
                return null;
            }
            return new SuperVoxel[]{new SuperVoxel((byte) 100, (byte) 100, (byte) 100, (byte) 15,
                    SuperVoxel.Material.SOLIDO, (byte) 0, (short) 1).conLuzHorneada(15)};
        }

        @Override
        public SuperVoxel[] grande(int nivel, int x, int y, int z) {
            return grandes.get(List.of(nivel, x, y, z));
        }

        @Override
        public void guardarGrande(int nivel, int x, int y, int z, SuperVoxel[] grilla) {
            grandes.put(List.of(nivel, x, y, z), grilla);
        }
    }

    @Test
    void unChunkSucioConstruyeSuCadenaDeNivelesHastaElMaximo() {
        MundoFalso mundo = new MundoFalso(4); // piedra de y=0 a y=63
        int guardados = NivelesGrandes.actualizar(Set.of(NivelesGrandes.empaquetar(5, 7)), -4, 20, mundo);

        for (int nivel = NivelesGrandes.NIVEL_MIN; nivel <= NivelesGrandes.NIVEL_MAX; nivel++) {
            assertNotNull(mundo.grande(nivel, 0, 0, 0), "Falta el nodo de nivel " + nivel);
        }
        assertEquals(NivelesGrandes.NIVEL_MAX - NivelesGrandes.NIVEL_MIN + 1, guardados,
                "Uno por nivel: los nodos con Y negativa quedan vacíos y no se guardan");
    }

    @Test
    void elNivelCincoTieneVoxelesDe32Bloques() {
        MundoFalso mundo = new MundoFalso(4); // 64 bloques de piedra = 2 vóxeles de 32
        NivelesGrandes.actualizar(Set.of(NivelesGrandes.empaquetar(0, 0)), 0, 24, mundo);
        SuperVoxel[] n5 = mundo.grande(5, 0, 0, 0);

        assertEquals(16 * 16 * 16, n5.length);
        for (int y = 0; y < 16; y++) {
            SuperVoxel v = n5[(3 * 16 + y) * 16 + 9];
            assertEquals(y < 2 ? SuperVoxel.Material.SOLIDO : SuperVoxel.Material.AIRE, v.material(), "y=" + y);
        }
        assertEquals(1, n5[(3 * 16) * 16 + 9].idEstado(), "El estado de bloque sube por los niveles");
    }

    @Test
    void elNivelSeisSaleDeSusHijos() {
        MundoFalso mundo = new MundoFalso(8); // 128 bloques = 2 vóxeles de 64
        NivelesGrandes.actualizar(Set.of(NivelesGrandes.empaquetar(0, 0)), 0, 24, mundo);
        SuperVoxel[] n6 = mundo.grande(6, 0, 0, 0);

        // Solo el hijo (0,0,0) existe: la mitad x<8, z<8 del nodo tiene terreno, el resto aire.
        assertEquals(SuperVoxel.Material.SOLIDO, n6[(2 * 16) * 16 + 2].material());
        assertEquals(SuperVoxel.Material.AIRE, n6[(12 * 16) * 16 + 12].material());
        assertEquals(SuperVoxel.Material.AIRE, n6[(2 * 16 + 5) * 16 + 2].material(), "Arriba del terreno, aire");
    }

    @Test
    void lasCoordenadasNegativasCaenEnElNodoCorrecto() {
        assertEquals(-1, NivelesGrandes.nodoDe(5, -1));
        assertEquals(-1, NivelesGrandes.nodoDe(5, -32));
        assertEquals(-2, NivelesGrandes.nodoDe(5, -33));
        assertEquals(-1, NivelesGrandes.nodoDe(8, -1));
        long p = NivelesGrandes.empaquetar(-3, 7);
        assertEquals(-3, NivelesGrandes.x(p));
        assertEquals(7, NivelesGrandes.z(p));
    }

    @Test
    void lasClavesNoChocanConLasDeSeccionNiEntreNiveles() {
        long seccion = SectionExtractor.claveNodo(4, 0, 0, 0);
        long n5 = NivelesGrandes.clave(5, 0, 0, 0);
        long n6 = NivelesGrandes.clave(6, 0, 0, 0);
        assertNotEquals(seccion, n5);
        assertNotEquals(n5, n6);
        assertEquals(NivelesGrandes.regionDe(6, 1), 2, "Un nodo de nivel 6 cubre 2 regiones");
    }
}
