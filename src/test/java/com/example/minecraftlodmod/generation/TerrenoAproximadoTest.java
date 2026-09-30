package com.example.minecraftlodmod.generation;

import com.example.minecraftlodmod.core.SuperVoxel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrenoAproximadoTest {

    private static final SuperVoxel PASTO = new SuperVoxel((byte) 90, (byte) 160, (byte) 60, (byte) 0,
            SuperVoxel.Material.SOLIDO, (byte) 0, (short) 9).conLuzHorneada(15);
    private static final SuperVoxel PIEDRA = new SuperVoxel((byte) 120, (byte) 120, (byte) 120, (byte) 0,
            SuperVoxel.Material.SOLIDO, (byte) 0, (short) 1);
    private static final SuperVoxel AGUA = new SuperVoxel((byte) 40, (byte) 70, (byte) 200, (byte) 0,
            SuperVoxel.Material.AGUA, (byte) 0, (short) 80).conLuzHorneada(15);

    private static TerrenoAproximado.Columna[] columnas(int altura) {
        TerrenoAproximado.Columna c = new TerrenoAproximado.Columna(altura, PASTO, PIEDRA);
        return new TerrenoAproximado.Columna[]{c, c, c, c};
    }

    private static SuperVoxel en(SuperVoxel[] g, int x, int y, int z) {
        return g[(x * 2 + y) * 2 + z];
    }

    @Test
    void laSuperficieVaEnElSolidoMasAltoYDebajoElSubsuelo() {
        // Terreno hasta y = 70 (sección 4 = y 64..79): vóxel y 64-71 sólido con superficie.
        SuperVoxel[] s4 = TerrenoAproximado.grillaNivel3(4, columnas(70), AGUA, 63);
        assertEquals(PASTO.conRelleno(223), en(s4, 0, 0, 0), "64-71: 7 de 8 bloques llenos, y es el más alto");
        assertEquals(TerrenoAproximado.AIRE, en(s4, 0, 1, 0), "72-79: vacío");
        SuperVoxel[] s3 = TerrenoAproximado.grillaNivel3(3, columnas(70), AGUA, 63);
        assertEquals(PIEDRA.conRelleno(SuperVoxel.LLENO), en(s3, 1, 1, 1), "56-63 queda debajo de la superficie");
    }

    @Test
    void bajoElNivelDelMarSinTerrenoEsAgua() {
        // Fondo del mar en y = 40, mar hasta 62: la sección 3 (48..63) es agua.
        SuperVoxel[] s3 = TerrenoAproximado.grillaNivel3(3, columnas(40), AGUA, 63);
        assertEquals(AGUA.conRelleno(SuperVoxel.LLENO), en(s3, 0, 0, 0));
        assertEquals(AGUA.conRelleno(223), en(s3, 0, 1, 0), "56-63: el mar llega hasta 62 (7 de 8 bloques)");
    }

    @Test
    void unaColumnaQueApenasEntraEnElVoxelYaLoHaceVisibleConSuAltura() {
        // Terreno hasta y = 64: el vóxel 64-71 tiene un solo bloque sólido (antes quedaba aire).
        SuperVoxel v = en(TerrenoAproximado.grillaNivel3(4, columnas(64), AGUA, 63), 0, 0, 0);
        assertEquals(PASTO.idEstado(), v.idEstado());
        assertEquals(32, v.relleno(), "1 de 8 bloques");
    }

    @Test
    void unaSeccionSoloDeAireNoSeGuarda() {
        assertNull(TerrenoAproximado.grillaNivel3(10, columnas(70), AGUA, 63));
    }

    @Test
    void elNivel4SaleDeReducirElNivel3() {
        SuperVoxel[] s4 = TerrenoAproximado.grillaNivel3(4, columnas(75), AGUA, 63);
        SuperVoxel[] n4 = TerrenoAproximado.grillaNivel4(s4);
        assertNotNull(n4);
        assertEquals(1, n4.length);
        assertEquals(PASTO.idEstado(), n4[0].idEstado(), "La superficie manda en el nivel reducido");
    }

    @Test
    void lasClavesAproximadasNoChocanConLasReales() {
        assertEquals(11, TerrenoAproximado.nivelGuardado(3));
        assertEquals(12, TerrenoAproximado.nivelGuardado(4));
        assertThrows(IllegalArgumentException.class, () -> TerrenoAproximado.nivelGuardado(2));
    }

    // ------------------------------------------------------------------ horizonte por región

    @Test
    void cercaNoHayRegionYLejosElNivelCreceConLaDistancia() {
        assertNull(TerrenoAproximado.nivelDeRegion(0, 0, 0, 0), "Al lado del jugador: chunk por chunk");
        int[] r5 = TerrenoAproximado.nivelDeRegion(20, 0, 0, 0); // centro a ~656 chunks
        assertEquals(5, r5[0]);
        int[] r6 = TerrenoAproximado.nivelDeRegion(40, 0, 0, 0); // ~1300 chunks
        assertEquals(6, r6[0]);
        assertEquals(20, r6[1]);
        int[] r7 = TerrenoAproximado.nivelDeRegion(200, 0, 0, 0); // ~6400 chunks
        assertEquals(7, r7[0]);
        assertEquals(50, r7[1]);
    }

    @Test
    void losHermanosDeUnNodoAproximadoCaenEnElMismoNodo() {
        // Los 4×4 nodos de nivel 5 de un nodo de nivel 7 lejano piden el mismo nodo: sin zonas a medias.
        int[] primero = TerrenoAproximado.nivelDeRegion(200, 40, 0, 0);
        for (int dx = 0; dx < 4; dx++) {
            for (int dz = 0; dz < 4; dz++) {
                assertArrayEquals(primero, TerrenoAproximado.nivelDeRegion(200 + dx, 40 + dz, 0, 0));
            }
        }
    }

    @Test
    void laGrillaGrandePoneLaSuperficieALaAlturaReal() {
        TerrenoAproximado.Columna[] columnas = new TerrenoAproximado.Columna[256];
        java.util.Arrays.fill(columnas, new TerrenoAproximado.Columna(70, PASTO, PIEDRA));
        // Nivel 5 (vóxeles de 32), banda 0: y 0..511. Terreno hasta 70: vóxel 64-95 con 7 bloques.
        SuperVoxel[] g = TerrenoAproximado.grillaGrande(5, 0, columnas, AGUA, 63);
        assertNotNull(g);
        SuperVoxel superficie = g[(3 * 16 + 2) * 16 + 5];
        assertEquals(PASTO.idEstado(), superficie.idEstado());
        assertEquals(Math.round(7 * 255 / 32f), superficie.relleno());
        assertEquals(PIEDRA.idEstado(), g[(3 * 16 + 1) * 16 + 5].idEstado(), "Debajo, el subsuelo lleno");
        assertEquals(SuperVoxel.Material.AIRE, g[(3 * 16 + 3) * 16 + 5].material());
        assertNull(TerrenoAproximado.grillaGrande(5, 1, columnas, AGUA, 63), "Banda de arriba: todo aire");
    }

    @Test
    void laSeccionSaleDelVoxelGrandeConElRellenoPartido() {
        TerrenoAproximado.Columna[] columnas = new TerrenoAproximado.Columna[256];
        java.util.Arrays.fill(columnas, new TerrenoAproximado.Columna(83, PASTO, PIEDRA)); // 20 bloques en 64-95
        SuperVoxel[] g = TerrenoAproximado.grillaGrande(5, 0, columnas, AGUA, 63);
        // Vóxel de 32 (y 64-95) = secciones 4 y 5: la 4 llena, la 5 con 4 de 16 bloques.
        SuperVoxel s4 = TerrenoAproximado.seccionDe(g, 5, 3, 4, 7);
        SuperVoxel s5 = TerrenoAproximado.seccionDe(g, 5, 3, 5, 7);
        assertEquals(SuperVoxel.LLENO, s4.relleno());
        assertEquals(PASTO.idEstado(), s5.idEstado());
        assertEquals(64, s5.relleno(), 1);
        assertEquals(SuperVoxel.Material.AIRE, TerrenoAproximado.seccionDe(g, 5, 3, 6, 7).material());
    }

    @Test
    void lasClavesDeRegionNoChocanEntreNivelesNiConLasDeChunk() {
        java.util.Set<Long> claves = new java.util.HashSet<>();
        for (int nivel = 5; nivel <= 7; nivel++) {
            for (int y = -1; y <= 1; y++) {
                assertTrue(claves.add(TerrenoAproximado.claveGrande(nivel, 0, y, 0)));
            }
            assertTrue(claves.add(TerrenoAproximado.claveMarcaGrande(nivel, 0, 0)));
        }
        assertTrue(claves.add(GeneradorAproximado.claveMarca(0, 0)), "La marca de chunk es otra");
    }
}
