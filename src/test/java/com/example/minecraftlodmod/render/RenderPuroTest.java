package com.example.minecraftlodmod.render;

import com.example.minecraftlodmod.core.SuperVoxel;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RenderPuroTest {

    private static SuperVoxel solido(int luz) {
        return new SuperVoxel((byte) 200, (byte) 100, (byte) 50, (byte) 0, SuperVoxel.Material.SOLIDO, (byte) 0)
                .conLuzHorneada(luz);
    }

    @Test
    void unVoxelSueltoDaUnCuboDeSeisCaras() {
        GeometriaLod g = new GeometriaLod();
        int quads = g.agregarSeccion(new SuperVoxel[]{solido(15)}, 1, 32, 64, 16, 16);

        assertEquals(6, quads);
        assertEquals(24, g.vertices());
        for (int i = 0; i < g.vertices(); i++) {
            assertTrue(g.x(i) == 32 || g.x(i) == 48, "x en el cubo escalado: " + g.x(i));
            assertTrue(g.y(i) == 64 || g.y(i) == 80, "y en el cubo escalado: " + g.y(i));
            assertTrue(g.z(i) == 16 || g.z(i) == 32, "z en el cubo escalado: " + g.z(i));
        }
    }

    @Test
    void laCaraDeArribaEsLaMasClaraYLaDeAbajoLaMasOscura() {
        assertTrue(GeometriaLod.sombraDeCara(generationEje("Y"), true) > GeometriaLod.sombraDeCara(generationEje("Z"), true));
        assertTrue(GeometriaLod.sombraDeCara(generationEje("Y"), false) < GeometriaLod.sombraDeCara(generationEje("X"), true));
    }

    @Test
    void laLuzHorneadaOscureceSinLlegarANegro() {
        int claro = GeometriaLod.color(solido(15), 1f, 15);
        int oscuro = GeometriaLod.color(solido(0), 1f, 0);
        assertEquals(200, (claro >> 16) & 0xFF);
        assertEquals(Math.round(200 * GeometriaLod.LUZ_MINIMA), (oscuro >> 16) & 0xFF);
        assertEquals(0xFF, (oscuro >>> 24), "Opaco");
    }

    @Test
    void elAireNoGeneraGeometria() {
        SuperVoxel aire = new SuperVoxel((byte) 0, (byte) 0, (byte) 0, (byte) 0, SuperVoxel.Material.AIRE, (byte) 0);
        SuperVoxel[] grid = new SuperVoxel[8];
        Arrays.fill(grid, aire);
        GeometriaLod g = new GeometriaLod();
        assertEquals(0, g.agregarSeccion(grid, 2, 0, 0, 0, 8));
    }

    @Test
    void unBloqueUniformeSeFusionaEnSeisQuads() {
        SuperVoxel[] grid = new SuperVoxel[16 * 16 * 16];
        Arrays.fill(grid, solido(15));
        GeometriaLod g = new GeometriaLod();
        assertEquals(6, g.agregarSeccion(grid, 16, 0, 0, 0, 1), "El greedy mesher fusiona cada cara entera");
    }

    @Test
    void elNivelCreceConLaDistanciaYBajaConZoom() {
        double fov = Math.toRadians(70), alto = 720, umbral = 4;
        // Proyección: 720 / (2 tan 35°) ≈ 514 px por unidad; nivel n cumple si 2^n * 514 / d <= 4.
        int cerca = PlanCeldas.nivelPara(50, fov, alto, umbral);
        int medio = PlanCeldas.nivelPara(600, fov, alto, umbral);
        int lejos = PlanCeldas.nivelPara(5000, fov, alto, umbral);
        int medioConZoom = PlanCeldas.nivelPara(600, Math.toRadians(10), alto, umbral);

        assertEquals(0, cerca, "Ni el nivel 0 cumple: máximo detalle");
        assertEquals(2, medio, "4 * 514 / 600 = 3.4 px <= 4; 8 * 514 / 600 = 6.9 px > 4");
        assertEquals(PlanCeldas.NIVEL_MAXIMO, lejos);
        assertTrue(medioConZoom < medio, "Con zoom el mismo lugar pide más detalle");
    }

    @Test
    void elPlanOmiteLoQueDibujaVanillaYLoQueQuedaFueraDelRadio() {
        List<PlanCeldas.Celda> plan = PlanCeldas.planificar(8, 8, 32, 8, Math.toRadians(70), 720, 4);

        assertFalse(plan.isEmpty());
        for (PlanCeldas.Celda c : plan) {
            assertFalse(c.todoOmitido(), "Una celda sin nada que dibujar no entra en el plan");
            for (int dx = 0; dx < PlanCeldas.LADO_CELDA; dx++) {
                for (int dz = 0; dz < PlanCeldas.LADO_CELDA; dz++) {
                    int chunkX = c.celdaX() * PlanCeldas.LADO_CELDA + dx;
                    int chunkZ = c.celdaZ() * PlanCeldas.LADO_CELDA + dz;
                    double d = Math.hypot(chunkX, chunkZ);
                    if (!c.omitido(dx, dz)) {
                        assertTrue(d >= 8 && d <= 32, "Chunk dibujado fuera de rango: " + chunkX + "," + chunkZ);
                    }
                }
            }
        }
        // La celda de la cámara queda entera dentro de vanilla.
        assertTrue(plan.stream().noneMatch(c -> c.celdaX() == 0 && c.celdaZ() == 0));
    }

    private static com.example.minecraftlodmod.generation.Quad.Eje generationEje(String nombre) {
        return com.example.minecraftlodmod.generation.Quad.Eje.valueOf(nombre);
    }

    @Test
    void lasCarasDeBordeTapadasNoSeDibujan() {
        SuperVoxel[] grid = new SuperVoxel[8];
        Arrays.fill(grid, solido(15));
        GeometriaLod todas = new GeometriaLod();
        GeometriaLod sinLaterales = new GeometriaLod();

        assertEquals(6, todas.agregarSeccion(grid, 2, 0, 0, 0, 8));
        int todosLosLados = GeometriaLod.OMITIR_X_NEG | GeometriaLod.OMITIR_X_POS
                | GeometriaLod.OMITIR_Z_NEG | GeometriaLod.OMITIR_Z_POS;
        assertEquals(2, sinLaterales.agregarSeccion(grid, 2, 0, 0, 0, 8, todosLosLados),
                "Quedan solo arriba y abajo");
    }

    @Test
    void lasCarasTexturizadasLlevanSuRectanguloYElColorQueCorresponde() {
        SuperVoxel pasto = new SuperVoxel((byte) 80, (byte) 160, (byte) 40, (byte) 0,
                SuperVoxel.Material.SOLIDO, (byte) 0, (short) 9).conLuzHorneada(15);
        GeometriaLod g = new GeometriaLod();
        // Arriba: textura teñida (usa el color del vóxel). Costados: tierra sin tinte.
        g.usarTexturas((estado, eje, positivo) -> eje == com.example.minecraftlodmod.generation.Quad.Eje.Y && positivo
                ? new GeometriaLod.Cara(0.5f, 0.25f, 0.01f, 0.02f, 0x7F7F7F, true)
                : new GeometriaLod.Cara(0.1f, 0.1f, 0.01f, 0.01f, 0x86603F, false));
        g.agregarSeccion(new SuperVoxel[]{pasto}, 1, 0, 0, 0, 1);

        boolean vioArriba = false, vioCostado = false;
        for (int i = 0; i < g.vertices(); i++) {
            assertTrue(g.texturizado(i));
            if (g.normalY(i) > 0) {
                vioArriba = true;
                assertEquals(0.5f, g.u0(i));
                assertEquals(0x7F7F7F, g.promedioTextura(i));
                assertEquals(160, (g.color(i) >> 8) & 0xFF, "Arriba manda el color del vóxel (con tinte)");
            } else if (g.normalX(i) != 0) {
                vioCostado = true;
                assertEquals(0x86 * 6 / 10, (g.color(i) >> 16) & 0xFF, 1,
                        "El costado usa el promedio de su propia textura, con sombra 0.6");
            }
        }
        assertTrue(vioArriba && vioCostado);
    }

    @Test
    void sinEstadoOSinFuenteQuedaConColorPlano() {
        GeometriaLod g = new GeometriaLod();
        g.usarTexturas((estado, eje, positivo) -> new GeometriaLod.Cara(0, 0, 0.01f, 0.01f, 0, true));
        g.agregarSeccion(new SuperVoxel[]{solido(15)}, 1, 0, 0, 0, 1); // solido() no tiene estado
        for (int i = 0; i < g.vertices(); i++) {
            assertFalse(g.texturizado(i));
        }
    }
}
