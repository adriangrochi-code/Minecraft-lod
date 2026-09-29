package com.example.minecraftlodmod.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LodSelectorTest {

    private static SuperVoxel voxelDePrueba() {
        return new SuperVoxel((byte) 100, (byte) 150, (byte) 80, (byte) 64,
                SuperVoxel.Material.SOLIDO, (byte) 0);
    }

    @Test
    void unNodoLejanoYChicoNoSeSubdivideConFovNormal() {
        // Nodo chico (16 bloques) muy lejos (2000 bloques): el error de
        // pantalla debería ser mínimo, y quedarse como está (sin hijos igual).
        OctreeNode raiz = OctreeNode.homogeneo(0, 0, 0, 2000, 16, voxelDePrueba());
        LodSelector selector = new LodSelector(2.0);

        List<OctreeNode> visibles = selector.seleccionarNodosVisibles(
                raiz, 0, 0, 0, Math.toRadians(70), 1080);

        assertEquals(1, visibles.size());
        assertEquals(raiz, visibles.get(0));
    }

    @Test
    void hacerZoomAumentaElErrorDePantallaPercibido() {
        // Mismo nodo, misma distancia — comparamos el error de pantalla con
        // FOV normal vs. FOV de zoom (más chico). El error debe ser mayor
        // con zoom, que es justamente el comportamiento que buscamos: al
        // hacer zoom, el mismo terreno lejano "pide" más detalle.
        double tamanoNodo = 64;
        double distancia = 500;
        double alturaPantalla = 1080;

        double errorNormal = LodSelector.errorDePantalla(
                tamanoNodo, distancia, Math.toRadians(70), alturaPantalla);
        double errorConZoom = LodSelector.errorDePantalla(
                tamanoNodo, distancia, Math.toRadians(10), alturaPantalla);

        assertTrue(errorConZoom > errorNormal,
                "El error de pantalla con zoom (FOV chico) debería ser mayor que sin zoom");
    }

    @Test
    void unNodoConHijosSeSubdivideSiElErrorSuperaElUmbral() {
        // Nodo grande y relativamente cerca: con un umbral estricto, debería
        // preferir bajar a los hijos en vez de usar el nodo grueso.
        SuperVoxel v = voxelDePrueba();
        OctreeNode raiz = OctreeNode.mixto(1, 0, 0, 0, 128, new SuperVoxel[]{v});

        OctreeNode[] hijos = new OctreeNode[8];
        int mitad = 64;
        int i = 0;
        for (int ox = 0; ox <= mitad; ox += mitad) {
            for (int oy = 0; oy <= mitad; oy += mitad) {
                for (int oz = 0; oz <= mitad; oz += mitad) {
                    hijos[i++] = OctreeNode.homogeneo(0, ox, oy, oz, mitad, v);
                }
            }
        }
        raiz.asignarHijos(hijos);

        LodSelector selectorEstricto = new LodSelector(0.5); // umbral muy exigente

        List<OctreeNode> visibles = selectorEstricto.seleccionarNodosVisibles(
                raiz, 0, 0, 100, Math.toRadians(70), 1080);

        assertTrue(visibles.size() > 1,
                "Con un umbral estricto y hijos disponibles, debería subdividir en vez de usar el nodo grueso");
    }
}
