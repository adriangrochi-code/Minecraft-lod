package com.example.minecraftlodmod.generation;

import com.example.minecraftlodmod.core.SuperVoxel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GreedyMesherTest {

    private static SuperVoxel solido() {
        return new SuperVoxel((byte) 100, (byte) 100, (byte) 100, (byte) 0,
                SuperVoxel.Material.SOLIDO, (byte) 0);
    }

    private static SuperVoxel aire() {
        return new SuperVoxel((byte) 0, (byte) 0, (byte) 0, (byte) 0,
                SuperVoxel.Material.AIRE, (byte) 0);
    }

    @Test
    void unCuboSolidoUniformeDaSeisQuadsGrandes() {
        // Un cubo 4x4x4 completamente sólido y del mismo color: cada una de
        // las 6 caras exteriores debería fusionarse en UN solo quad grande
        // (4x4), no 16 quads chicos por cara.
        int lado = 4;
        SuperVoxel[] grid = new SuperVoxel[lado * lado * lado];
        java.util.Arrays.fill(grid, solido());

        List<Quad> quads = GreedyMesher.mallar(grid, lado);

        assertEquals(6, quads.size(), "Un cubo uniforme debería generar exactamente 6 quads (uno por cara)");
        for (Quad q : quads) {
            assertEquals(4, q.ancho());
            assertEqual4Alto(q);
        }
    }

    private void assertEqual4Alto(Quad q) {
        assertEquals(4, q.alto());
    }

    @Test
    void doMaterialesDistintosNoSeFusionanEnLaMismaCara() {
        // Capa 2x1x1: mitad sólido, mitad agua, expuestos hacia arriba (eje Y positivo).
        // No deberían fusionarse en un solo quad porque son materiales distintos.
        int lado = 2;
        SuperVoxel[] grid = new SuperVoxel[lado * lado * lado];
        java.util.Arrays.fill(grid, aire());

        // y=0 es la capa "de superficie"; x=0 sólido, x=1 agua.
        grid[indice(0, 0, 0, lado)] = solido();
        grid[indice(1, 0, 0, lado)] = new SuperVoxel((byte) 0, (byte) 0, (byte) 255, (byte) 0,
                SuperVoxel.Material.AGUA, (byte) 0);

        List<Quad> quads = GreedyMesher.mallar(grid, lado);

        long quadsHaciaArriba = quads.stream()
                .filter(q -> q.eje() == Quad.Eje.Y && q.positivo())
                .count();

        assertTrue(quadsHaciaArriba >= 2,
                "Materiales distintos en la misma capa no deberían fusionarse en un solo quad");
    }

    private static int indice(int x, int y, int z, int lado) {
        return (x * lado + y) * lado + z;
    }

    @Test
    void lasCarasDelBordeTapadasPorElVecinoNoSeGeneran() {
        int lado = 2;
        SuperVoxel[] lleno = new SuperVoxel[lado * lado * lado];
        java.util.Arrays.fill(lleno, solido());
        SuperVoxel[] vacio = new SuperVoxel[lado * lado * lado];
        java.util.Arrays.fill(vacio, aire());

        // Sin vecinos: un cubo cerrado, 6 caras.
        assertEquals(6, GreedyMesher.mallar(lleno, lado).size());
        // Con otra sección llena arriba y abajo (como secciones apiladas): solo los 4 costados.
        GreedyMesher.Vecinos apilado = GreedyMesher.Vecinos.deGrillas(lado, null, null, lleno, lleno, null, null);
        List<Quad> quads = GreedyMesher.mallar(lleno, lado, apilado);
        assertEquals(4, quads.size());
        assertTrue(quads.stream().noneMatch(q -> q.eje() == Quad.Eje.Y), "La costura entre secciones no se dibuja");
        // Un vecino de aire no tapa nada.
        GreedyMesher.Vecinos conAire = GreedyMesher.Vecinos.deGrillas(lado, vacio, vacio, vacio, vacio, vacio, vacio);
        assertEquals(6, GreedyMesher.mallar(lleno, lado, conAire).size());
    }

    @Test
    void elVecinoTapaSoloDondeEsSolido() {
        int lado = 2;
        SuperVoxel[] lleno = new SuperVoxel[lado * lado * lado];
        java.util.Arrays.fill(lleno, solido());
        // Vecino +X con solo la columna z=0 sólida: la cara +X queda expuesta en z=1.
        SuperVoxel[] medio = new SuperVoxel[lado * lado * lado];
        for (int x = 0; x < lado; x++) {
            for (int y = 0; y < lado; y++) {
                for (int z = 0; z < lado; z++) {
                    medio[(x * lado + y) * lado + z] = z == 0 ? solido() : aire();
                }
            }
        }
        GreedyMesher.Vecinos vecinos = GreedyMesher.Vecinos.deGrillas(lado, null, medio, null, null, null, null);
        List<Quad> caraXPos = GreedyMesher.mallar(lleno, lado, vecinos).stream()
                .filter(q -> q.eje() == Quad.Eje.X && q.positivo()).toList();
        assertEquals(1, caraXPos.size());
        assertEquals(1, caraXPos.get(0).z(), "Queda solo la mitad sin tapar");
        assertEquals(1, caraXPos.get(0).ancho());
    }

    /** Piso de 3x3 (y = 0) con bloques encima según {@code arriba[x][z]}. */
    private static SuperVoxel[] pisoCon(boolean[][] arriba) {
        int lado = 3;
        SuperVoxel[] g = new SuperVoxel[lado * lado * lado];
        for (int x = 0; x < lado; x++) {
            for (int y = 0; y < lado; y++) {
                for (int z = 0; z < lado; z++) {
                    boolean lleno = y == 0 || (y == 1 && arriba[x][z]);
                    g[(x * lado + y) * lado + z] = lleno ? solido() : aire();
                }
            }
        }
        return g;
    }

    private static Quad techoEn(List<Quad> quads, int x, int z) {
        return quads.stream()
                .filter(q -> q.eje() == Quad.Eje.Y && q.positivo() && q.y() == 0)
                .filter(q -> x >= q.x() && x < q.x() + q.ancho() && z >= q.z() && z < q.z() + q.alto())
                .findFirst().orElseThrow();
    }

    @Test
    void laOclusionOscureceLasEsquinasJuntoAUnBloque() {
        boolean[][] arriba = new boolean[3][3];
        arriba[0][1] = true; // un bloque sobre el piso, al lado (-x) de la celda (1, 1)
        List<Quad> quads = GreedyMesher.mallar(pisoCon(arriba), 3, null, true);

        Quad junto = techoEn(quads, 1, 1);
        assertEquals(1, junto.ancho(), "No se fusiona con caras de otra oclusión");
        assertTrue(junto.oclusionEn(false, false) < 3 && junto.oclusionEn(false, true) < 3,
                "Las esquinas del lado del bloque se oscurecen");
        assertEquals(3, junto.oclusionEn(true, false), "Las del otro lado no");
        assertEquals(3, techoEn(quads, 2, 0).oclusionEn(true, false), "Lejos del bloque no hay oclusión");
    }

    @Test
    void unRinconCerradoQuedaEnCero() {
        boolean[][] arriba = new boolean[3][3];
        arriba[0][1] = true; // -x de (1, 1)
        arriba[1][0] = true; // -z de (1, 1)
        Quad rincon = techoEn(GreedyMesher.mallar(pisoCon(arriba), 3, null, true), 1, 1);
        assertEquals(0, rincon.oclusionEn(false, false), "Dos costados ocluyendo: rincón cerrado");
    }

    @Test
    void sinOclusionTodoQuedaComoAntes() {
        boolean[][] arriba = new boolean[3][3];
        arriba[0][1] = true;
        SuperVoxel[] g = pisoCon(arriba);
        List<Quad> sin = GreedyMesher.mallar(g, 3, null, false);
        assertEquals(GreedyMesher.mallar(g, 3).size(), sin.size());
        assertTrue(sin.stream().allMatch(q -> q.oclusion() == Quad.SIN_OCLUSION));
        assertTrue(GreedyMesher.mallar(g, 3, null, true).size() >= sin.size(),
                "Con oclusión se fusiona menos, nunca más");
    }

    @Test
    void elFondoBajoElAguaTieneCarasYNoSeFusionaConLoDeAire() {
        SuperVoxel agua = new SuperVoxel((byte) 40, (byte) 70, (byte) 200, (byte) 0, SuperVoxel.Material.AGUA, (byte) 0);
        // Columna de 2: arena abajo, agua arriba.
        SuperVoxel[] g = new SuperVoxel[8];
        for (int x = 0; x < 2; x++) {
            for (int z = 0; z < 2; z++) {
                g[(x * 2) * 2 + z] = solido();
                g[(x * 2 + 1) * 2 + z] = agua;
            }
        }
        List<Quad> quads = GreedyMesher.mallar(g, 2);
        Quad fondo = quads.stream().filter(q -> q.eje() == Quad.Eje.Y && q.positivo() && q.y() == 0)
                .findFirst().orElseThrow(() -> new AssertionError("El fondo bajo el agua tiene que tener cara"));
        assertTrue(fondo.bajoAgua(), "Marcada como bajo agua (el descarte de cuevas no la saca)");
        Quad superficie = quads.stream().filter(q -> q.eje() == Quad.Eje.Y && q.positivo() && q.y() == 1)
                .findFirst().orElseThrow();
        assertTrue(!superficie.bajoAgua(), "La superficie del agua da contra aire");
    }
}
