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
}
