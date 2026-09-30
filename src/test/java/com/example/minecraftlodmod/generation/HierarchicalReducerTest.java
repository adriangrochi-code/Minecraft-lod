package com.example.minecraftlodmod.generation;

import com.example.minecraftlodmod.core.SuperVoxel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HierarchicalReducerTest {

    private static SuperVoxel voxel(int r, SuperVoxel.Material material) {
        return new SuperVoxel((byte) r, (byte) 0, (byte) 0, (byte) 100, material, (byte) 0);
    }

    @Test
    void reducirUnCuboUniformeDaElMismoColorPromedio() {
        int lado = 4; // 4^3 = 64 supervóxeles, todos iguales
        SuperVoxel[] entrada = new SuperVoxel[lado * lado * lado];
        SuperVoxel referencia = voxel(80, SuperVoxel.Material.SOLIDO);
        java.util.Arrays.fill(entrada, referencia);

        SuperVoxel[] salida = HierarchicalReducer.reducir(entrada, lado);

        assertEquals(2 * 2 * 2, salida.length, "4^3 reducido a 2x2x2 debería dar 2^3 elementos");
        for (SuperVoxel v : salida) {
            assertEquals(referencia.r(), v.r());
            assertEquals(SuperVoxel.Material.SOLIDO, v.material());
        }
    }

    @Test
    void elMaterialDominanteGanaLaVotacion() {
        // Bloque 2x2x2 con 5 SOLIDO y 3 AGUA: debería ganar SOLIDO.
        SuperVoxel[] entrada = {
                voxel(1, SuperVoxel.Material.SOLIDO), voxel(1, SuperVoxel.Material.SOLIDO),
                voxel(1, SuperVoxel.Material.SOLIDO), voxel(1, SuperVoxel.Material.AGUA),
                voxel(1, SuperVoxel.Material.SOLIDO), voxel(1, SuperVoxel.Material.AGUA),
                voxel(1, SuperVoxel.Material.SOLIDO), voxel(1, SuperVoxel.Material.AGUA),
        };

        SuperVoxel[] salida = HierarchicalReducer.reducir(entrada, 2);

        assertEquals(1, salida.length);
        assertEquals(SuperVoxel.Material.SOLIDO, salida[0].material());
    }

    @Test
    void detectaBloqueHomogeneoCorrectamente() {
        SuperVoxel[] entrada = new SuperVoxel[8];
        java.util.Arrays.fill(entrada, voxel(50, SuperVoxel.Material.SOLIDO));

        assertTrue(HierarchicalReducer.esBloqueHomogeneo(entrada, 2, 0, 0, 0));

        entrada[7] = voxel(99, SuperVoxel.Material.AGUA);
        assertTrue(!HierarchicalReducer.esBloqueHomogeneo(entrada, 2, 0, 0, 0));
    }

    @Test
    void elAireNoOscureceElColorNiPierdeLaLuz() {
        SuperVoxel aire = new SuperVoxel((byte) 0, (byte) 0, (byte) 0, (byte) 0, SuperVoxel.Material.AIRE, (byte) 0);
        SuperVoxel nieve = new SuperVoxel((byte) 250, (byte) 250, (byte) 250, (byte) 7,
                SuperVoxel.Material.SOLIDO, (byte) 0).conLuzHorneada(15);
        // Superficie: la mitad de abajo sólida, la de arriba aire (índice (x*2+y)*2+z, y=1 es arriba).
        SuperVoxel[] entrada = new SuperVoxel[8];
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 2; y++) {
                for (int z = 0; z < 2; z++) {
                    entrada[(x * 2 + y) * 2 + z] = y == 0 ? nieve : aire;
                }
            }
        }

        SuperVoxel r = HierarchicalReducer.reducir(entrada, 2)[0];

        assertEquals(SuperVoxel.Material.SOLIDO, r.material(), "Con la mitad visible, la superficie no desaparece");
        assertEquals(250, r.r() & 0xFF, "El aire no entra en el promedio de color");
        assertEquals(15, r.luzHorneada(), "La luz horneada se conserva al reducir");
    }

    @Test
    void conMenosDeLaMitadVisibleQuedaAire() {
        SuperVoxel aire = new SuperVoxel((byte) 0, (byte) 0, (byte) 0, (byte) 0, SuperVoxel.Material.AIRE, (byte) 0);
        SuperVoxel[] entrada = new SuperVoxel[8];
        java.util.Arrays.fill(entrada, aire);
        entrada[0] = voxel(10, SuperVoxel.Material.SOLIDO);
        entrada[1] = voxel(10, SuperVoxel.Material.SOLIDO);
        entrada[2] = voxel(10, SuperVoxel.Material.SOLIDO);

        assertEquals(SuperVoxel.Material.AIRE, HierarchicalReducer.reducir(entrada, 2)[0].material());
    }

    private static SuperVoxel lleno() {
        return voxel(10, SuperVoxel.Material.SOLIDO).conRelleno(SuperVoxel.LLENO);
    }

    @Test
    void elRellenoEsLaAlturaMediaDeLoSolido() {
        SuperVoxel aire = new SuperVoxel((byte) 0, (byte) 0, (byte) 0, (byte) 0, SuperVoxel.Material.AIRE, (byte) 0);
        SuperVoxel[] entrada = new SuperVoxel[8];
        java.util.Arrays.fill(entrada, aire);
        // Fila de abajo llena (y = 0 en los índices (x*2+y)*2+z: 0, 1, 4, 5), arriba aire.
        for (int i : new int[]{0, 1, 4, 5}) {
            entrada[i] = lleno();
        }
        assertEquals(128, HierarchicalReducer.reducir(entrada, 2)[0].relleno(), "Lleno hasta la mitad");
        // Una columna con el hijo de arriba a medio llenar: (1 + 0,5) en vez de 1.
        entrada[2] = lleno().conRelleno(128);
        assertEquals(Math.round((1 + 1 + 1 + 1 + 128 / 255.0) / 4 / 2 * 255),
                HierarchicalReducer.reducir(entrada, 2)[0].relleno());
    }

    @Test
    void tresColumnasLlenasHastaLaMitadYaNoSePierden() {
        SuperVoxel aire = new SuperVoxel((byte) 0, (byte) 0, (byte) 0, (byte) 0, SuperVoxel.Material.AIRE, (byte) 0);
        SuperVoxel[] entrada = new SuperVoxel[8];
        java.util.Arrays.fill(entrada, aire);
        for (int i : new int[]{0, 1, 4}) {
            entrada[i] = lleno();
        }
        SuperVoxel r = HierarchicalReducer.reducir(entrada, 2)[0];
        assertEquals(SuperVoxel.Material.SOLIDO, r.material(), "Borde de una meseta: 3 de 4 columnas");
        assertEquals(96, r.relleno(), "Tres cuartos de medio alto");
    }

    @Test
    void laSuperficieDecideColorYEstadoAunqueAbajoHayaMasVolumen() {
        SuperVoxel pasto = new SuperVoxel((byte) 90, (byte) 160, (byte) 60, (byte) 1,
                SuperVoxel.Material.SOLIDO, (byte) 0, (short) 9).conLuzHorneada(15);
        SuperVoxel tierra = new SuperVoxel((byte) 120, (byte) 85, (byte) 60, (byte) 0,
                SuperVoxel.Material.SOLIDO, (byte) 0, (short) 10).conLuzHorneada(0);
        SuperVoxel[] entrada = new SuperVoxel[8];
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 2; y++) {
                for (int z = 0; z < 2; z++) {
                    entrada[(x * 2 + y) * 2 + z] = y == 1 ? pasto : tierra;
                }
            }
        }

        SuperVoxel r = HierarchicalReducer.reducir(entrada, 2)[0];

        assertEquals(9, r.idEstado(), "La textura es la del pasto de arriba");
        assertEquals(160, r.g() & 0xFF, "El color es el de la superficie");
        assertEquals(15, r.luzHorneada(), "Y la luz, la de la superficie");
    }

    @Test
    void elColorNoMezclaBloquesDistintosSoloTintesDelMismoBloque() {
        // Superficie: 3 columnas de pasto (dos tintes de bioma) y 1 de piedra.
        SuperVoxel pastoA = new SuperVoxel((byte) 80, (byte) 160, (byte) 40, (byte) 0,
                SuperVoxel.Material.SOLIDO, (byte) 0, (short) 9).conLuzHorneada(15);
        SuperVoxel pastoB = new SuperVoxel((byte) 100, (byte) 180, (byte) 60, (byte) 0,
                SuperVoxel.Material.SOLIDO, (byte) 0, (short) 9).conLuzHorneada(15);
        SuperVoxel piedra = new SuperVoxel((byte) 125, (byte) 125, (byte) 125, (byte) 0,
                SuperVoxel.Material.SOLIDO, (byte) 0, (short) 1).conLuzHorneada(15);
        SuperVoxel[] entrada = new SuperVoxel[8];
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 2; y++) {
                for (int z = 0; z < 2; z++) {
                    SuperVoxel arriba = x == 1 && z == 1 ? piedra : (x == 0 ? pastoA : pastoB);
                    entrada[(x * 2 + y) * 2 + z] = arriba;
                }
            }
        }

        SuperVoxel r = HierarchicalReducer.reducir(entrada, 2)[0];

        assertEquals(9, r.idEstado());
        // Pasto: A, A, B → promedio (80+80+100)/3 = 86; con la piedra hubiera dado 96.
        assertEquals(86, r.r() & 0xFF, "La piedra no ensucia el color del pasto");
        assertEquals((160 + 160 + 180) / 3, r.g() & 0xFF, "El degradé de tinte sí se promedia");
    }

    @Test
    void laNieveSigueSiCubreAlMenosLaMitadDeLaSuperficie() {
        SuperVoxel[] entrada = new SuperVoxel[8];
        java.util.Arrays.fill(entrada, voxel(80, SuperVoxel.Material.SOLIDO));
        // Índice (x*2 + y)*2 + z (ver HierarchicalReducer.indice): arriba es y=1.
        entrada[2] = entrada[2].conNevado(true);  // x=0 z=0
        entrada[6] = entrada[6].conNevado(true);  // x=1 z=0
        assertTrue(HierarchicalReducer.reducir(entrada, 2)[0].nevado(), "2 de 4 columnas nevadas");

        entrada[6] = entrada[6].conNevado(false);
        assertFalse(HierarchicalReducer.reducir(entrada, 2)[0].nevado(), "1 de 4 columnas nevadas");
    }
}
