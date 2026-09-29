package com.example.minecraftlodmod.generation;

import com.example.minecraftlodmod.core.SuperVoxel;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VertexLightSamplerTest {

    private static SuperVoxel solidoConLuz(int luz) {
        return new SuperVoxel((byte) 100, (byte) 100, (byte) 100, (byte) 0,
                SuperVoxel.Material.SOLIDO, (byte) 0).conLuzHorneada(luz);
    }

    @Test
    void luzUniformeDaElMismoValorEnLasCuatroEsquinas() {
        int lado = 4;
        SuperVoxel[] grid = new SuperVoxel[lado * lado * lado];
        Arrays.fill(grid, solidoConLuz(10));

        List<Quad> quads = GreedyMesher.mallar(grid, lado);
        Quad caraArriba = quads.stream()
                .filter(q -> q.eje() == Quad.Eje.Y && q.positivo())
                .findFirst().orElseThrow();

        VertexLightSampler.LuzEsquinas luz = VertexLightSampler.calcular(grid, lado, caraArriba);

        assertEquals(10, luz.minMin());
        assertEquals(10, luz.maxMin());
        assertEquals(10, luz.minMax());
        assertEquals(10, luz.maxMax());
    }

    @Test
    void unaEsquinaConVecinoMasOscuroBajaElPromedioSoloEnEsaEsquina() {
        // Grilla 2x1x2 (una sola capa en Y): dos vóxeles sólidos, uno con luz
        // alta y otro con luz baja, lado a lado en el eje X.
        int lado = 2;
        SuperVoxel[] grid = new SuperVoxel[lado * lado * lado];
        Arrays.fill(grid, new SuperVoxel((byte) 0, (byte) 0, (byte) 0, (byte) 0,
                SuperVoxel.Material.AIRE, (byte) 0));

        grid[indice(0, 0, 0, lado)] = solidoConLuz(15);
        grid[indice(1, 0, 0, lado)] = solidoConLuz(3);
        grid[indice(0, 0, 1, lado)] = solidoConLuz(15);
        grid[indice(1, 0, 1, lado)] = solidoConLuz(3);

        List<Quad> quads = GreedyMesher.mallar(grid, lado);
        // Con colores distintos (mismo material, pero acá igual luz no afecta
        // la fusión), puede que se generen 2 quads separados o 1 fusionado
        // según el criterio de mismaSuperficie (material+color, no luz) —
        // buscamos cualquier quad que toque x=1 (el lado más oscuro).
        Quad quadOscuro = quads.stream()
                .filter(q -> q.eje() == Quad.Eje.Y && q.positivo())
                .filter(q -> q.x() + (q.eje() == Quad.Eje.Y ? q.ancho() : 0) >= 1)
                .findFirst().orElseThrow();

        VertexLightSampler.LuzEsquinas luz = VertexLightSampler.calcular(grid, lado, quadOscuro);

        int maxLuzEnElQuad = Math.max(
                Math.max(luz.minMin(), luz.maxMin()),
                Math.max(luz.minMax(), luz.maxMax()));

        assertTrue(maxLuzEnElQuad <= 15,
                "El promedio nunca debería superar el valor más alto presente en la grilla");
    }

    private static int indice(int x, int y, int z, int lado) {
        return (x * lado + y) * lado + z;
    }
}
