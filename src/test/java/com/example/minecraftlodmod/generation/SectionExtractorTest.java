package com.example.minecraftlodmod.generation;

import com.example.minecraftlodmod.core.OctreeNode;
import com.example.minecraftlodmod.core.SuperVoxel;
import com.example.minecraftlodmod.storage.OctreeNodeCodec;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SectionExtractorTest {

    private static final SuperVoxel.Material AIRE = SuperVoxel.Material.AIRE;
    private static final SuperVoxel.Material SOLIDO = SuperVoxel.Material.SOLIDO;

    /** Lector de prueba: un bloque por función, con contador de lecturas. */
    private static final class LectorFalso implements SectionExtractor.LectorSeccion {
        final boolean soloAire, homogenea;
        final java.util.function.IntUnaryOperator colorPorY; // color r por altura, -1 = aire
        final AtomicInteger lecturas = new AtomicInteger();

        LectorFalso(boolean soloAire, boolean homogenea, java.util.function.IntUnaryOperator colorPorY) {
            this.soloAire = soloAire;
            this.homogenea = homogenea;
            this.colorPorY = colorPorY;
        }

        @Override
        public boolean soloAire() {
            return soloAire;
        }

        @Override
        public boolean homogenea() {
            return homogenea;
        }

        @Override
        public SuperVoxel voxel(int x, int y, int z) {
            lecturas.incrementAndGet();
            int r = colorPorY.applyAsInt(y);
            SuperVoxel.Material m = r < 0 ? AIRE : SOLIDO;
            return new SuperVoxel((byte) Math.max(r, 0), (byte) 0, (byte) 0, (byte) y, m, (byte) 0);
        }
    }

    @Test
    void unaSeccionSoloAireSeDescarta() {
        assertNull(SectionExtractor.extraer(new LectorFalso(true, false, y -> 10), 0, 0, 0));
    }

    @Test
    void unaSeccionConSoloBloquesInvisiblesSeDescarta() {
        assertNull(SectionExtractor.extraer(new LectorFalso(false, false, y -> -1), 0, 0, 0));
        assertNull(SectionExtractor.extraer(new LectorFalso(false, true, y -> -1), 0, 0, 0));
    }

    @Test
    void unaSeccionHomogeneaSeLeeUnaSolaVez() {
        LectorFalso lector = new LectorFalso(false, true, y -> 50);
        SectionExtractor.SeccionExtraida s = SectionExtractor.extraer(lector, 1, 2, 3);

        assertNotNull(s);
        assertTrue(s.homogeneaEnOrigen());
        assertEquals(1, lector.lecturas.get(), "Una sección homogénea no debería recorrer los 4096 bloques");
        assertEquals(16 * 16 * 16, s.voxeles().length);
        // Todo bloque visible del nivel 0 está lleno (relleno = superficie en el borde de arriba).
        assertEquals(SuperVoxel.LLENO, s.voxeles()[0].relleno());
        assertEquals(SuperVoxel.LLENO, s.voxeles()[15 * 16].relleno());
    }

    @Test
    void elColapsoSoloAplicaDesdeElNivelIndicado() {
        SectionExtractor.SeccionExtraida s = SectionExtractor.extraer(new LectorFalso(false, true, y -> 50), 0, 0, 0);
        List<OctreeNode> niveles = SectionExtractor.generarNiveles(s, 2);

        assertEquals(SectionExtractor.NIVELES, niveles.size());
        assertFalse(niveles.get(0).esHomogeneo(), "Nivel 0 nunca colapsa si el preset colapsa desde el 2");
        assertFalse(niveles.get(1).esHomogeneo());
        assertTrue(niveles.get(2).esHomogeneo());
        assertTrue(niveles.get(2).voxelHomogeneo().esHomogeneo(), "El supervóxel colapsado lleva el flag");
        assertEquals(SuperVoxel.LLENO, niveles.get(2).voxelHomogeneo().relleno(), "Sección llena: llena hasta arriba");
    }

    @Test
    void cadaNivelTieneLaMitadDeLado() {
        // Terreno mixto: sólido abajo, aire arriba.
        SectionExtractor.SeccionExtraida s = SectionExtractor.extraer(
                new LectorFalso(false, false, y -> y < 8 ? 100 : -1), 0, 0, 0);
        List<OctreeNode> niveles = SectionExtractor.generarNiveles(s, 1);

        assertEquals(4096, niveles.get(0).voxeles().length);
        assertEquals(512, niveles.get(1).voxeles().length);
        assertEquals(64, niveles.get(2).voxeles().length);
        assertEquals(8, niveles.get(3).voxeles().length);
        for (int nivel = 0; nivel < 4; nivel++) {
            assertEquals(nivel, niveles.get(nivel).nivelLod());
            assertEquals(SectionExtractor.voxelesPorNodo(nivel), niveles.get(nivel).voxeles().length);
            assertEquals(16, niveles.get(nivel).tamanoMundo(), "Todos los niveles cubren la sección entera");
        }
    }

    @Test
    void losNivelesSeSerializanYVuelvenIguales() {
        SectionExtractor.SeccionExtraida s = SectionExtractor.extraer(
                new LectorFalso(false, false, y -> y < 5 ? 30 + y : -1), 0, 0, 0);
        List<OctreeNode> niveles = SectionExtractor.generarNiveles(s, 1);

        for (OctreeNode nodo : niveles) {
            int total = SectionExtractor.voxelesPorNodo(nodo.nivelLod());
            byte[] bytes = OctreeNodeCodec.serializar(nodo, total);
            OctreeNodeCodec.NodoDeserializado leido = OctreeNodeCodec.deserializar(bytes, 0, total);
            assertEquals(nodo.nivelLod(), leido.nivelLod());
            if (!nodo.esHomogeneo()) {
                assertArrayEquals(nodo.voxeles(), leido.voxeles());
            }
        }
    }

    @Test
    void laClaveDeNodoEsUnicaDentroDeUnaRegion() {
        Set<Long> claves = new HashSet<>();
        for (int nivel = 0; nivel < SectionExtractor.NIVELES; nivel++) {
            for (int x = 0; x < 32; x++) {
                for (int y = -4; y < 20; y++) {
                    for (int z = 0; z < 32; z++) {
                        assertTrue(claves.add(SectionExtractor.claveNodo(nivel, x, y, z)));
                    }
                }
            }
        }
    }

    @Test
    void lasCoordenadasNegativasCaenEnLaRegionCorrecta() {
        assertEquals(-1, SectionExtractor.regionDe(-1));
        assertEquals(-1, SectionExtractor.regionDe(-32));
        assertEquals(-2, SectionExtractor.regionDe(-33));
        assertEquals(0, SectionExtractor.regionDe(31));
        // X local de la sección -1 es 31: misma clave que la sección 31 de otra región.
        assertEquals(SectionExtractor.claveNodo(0, 31, 0, 0), SectionExtractor.claveNodo(0, -1, 0, 0));
    }
}
