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
                ? new GeometriaLod.Cara(7, 0x7F7F7F, true)
                : new GeometriaLod.Cara(8, 0x86603F, false));
        g.agregarSeccion(new SuperVoxel[]{pasto}, 1, 0, 0, 0, 1);

        boolean vioArriba = false, vioCostado = false;
        for (int i = 0; i < g.vertices(); i++) {
            assertTrue(g.texturizado(i));
            if (g.cara(i) == 3) {
                vioArriba = true;
                assertEquals(7, g.sprite(i));
                assertEquals(160, (g.color(i) >> 8) & 0xFF, "Arriba manda el color del vóxel (con tinte)");
            } else if (g.cara(i) <= 1) {
                assertEquals(8, g.sprite(i));
                vioCostado = true;
                assertEquals(0x86 * 6 / 10, (g.color(i) >> 16) & 0xFF, 1,
                        "El costado usa el promedio de su propia textura, con sombra 0.6");
            }
        }
        assertTrue(vioArriba && vioCostado);
    }

    @Test
    void elFormatoCompactoOcupaDoceBytesPorVerticeYConservaTodo() {
        SuperVoxel piedra = new SuperVoxel((byte) 100, (byte) 110, (byte) 120, (byte) 0,
                SuperVoxel.Material.SOLIDO, (byte) 0, (short) 1).conLuzHorneada(15);
        GeometriaLod g = new GeometriaLod();
        g.usarTexturas((estado, eje, positivo) -> new GeometriaLod.Cara(40000, 0x646E78, true));
        g.agregarSeccion(new SuperVoxel[]{piedra}, 1, 3000, -64, 48, 16);
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(g.vertices() * GeometriaLod.BYTES_COMPACTO);
        g.escribirCompacto(b);
        assertEquals(b.capacity(), b.position(), "12 bytes por vértice, ni uno más");
        b.flip().order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < g.vertices(); i++) {
            assertEquals((int) g.x(i), b.getShort());
            assertEquals((int) g.y(i), b.getShort());
            assertEquals((int) g.z(i), b.getShort());
            assertEquals(40000, b.getShort() & 0xFFFF, "El sprite se lee sin signo");
            assertEquals((g.color(i) >> 16) & 0xFF, b.get() & 0xFF);
            assertEquals((g.color(i) >> 8) & 0xFF, b.get() & 0xFF);
            assertEquals(g.color(i) & 0xFF, b.get() & 0xFF);
            assertEquals(g.cara(i), b.get());
        }
    }

    @Test
    void unaPosicionQueNoEntraEnUnShortNoSeCorrompeEnSilencio() {
        GeometriaLod g = new GeometriaLod();
        g.agregarSeccion(new SuperVoxel[]{solido(15)}, 1, 40000, 0, 0, 1);
        assertThrows(IllegalStateException.class,
                () -> g.escribirCompacto(java.nio.ByteBuffer.allocate(g.vertices() * GeometriaLod.BYTES_COMPACTO)));
    }

    @Test
    void losTexelesDelSpriteEmpaquetanRectanguloYPromedio() {
        int[] t = GeometriaLod.texelesSprite(1024, 3, 16, 32, 0x112233);
        assertEquals(1024, t[0] & 0xFFFF);
        assertEquals(3, t[0] >>> 16);
        assertEquals(16, t[1] & 0xFFFF);
        assertEquals(32, t[1] >>> 16);
        assertEquals(0x11, t[2] & 0xFF, "R en el byte bajo, como NativeImage");
        assertEquals(0x22, (t[2] >> 8) & 0xFF);
        assertEquals(0x33, (t[2] >> 16) & 0xFF);
    }

    @Test
    void sinEstadoOSinFuenteQuedaConColorPlano() {
        GeometriaLod g = new GeometriaLod();
        g.usarTexturas((estado, eje, positivo) -> new GeometriaLod.Cara(1, 0, true));
        g.agregarSeccion(new SuperVoxel[]{solido(15)}, 1, 0, 0, 0, 1); // solido() no tiene estado
        for (int i = 0; i < g.vertices(); i++) {
            assertFalse(g.texturizado(i));
        }
    }

    @Test
    void elQuadtreeCubreElRadioSinSuperponerYLejosUsaNivelesGrandes() {
        double camX = 100, camZ = -50;
        int radioChunks = 400; // 6.4 km
        int vanilla = 8;
        List<PlanCeldas.Celda> plan = PlanCeldas.planificarConGrandes(camX, camZ, radioChunks, vanilla,
                Math.toRadians(70), 720, 4);

        assertTrue(plan.stream().anyMatch(PlanCeldas.Celda::esGrande), "A km de distancia tiene que haber teselas grandes");
        assertTrue(plan.stream().anyMatch(c -> !c.esGrande()), "Cerca, celdas finas");
        assertTrue(plan.size() < 3000, "Muchas menos piezas que celdas de 4×4 chunks en 6 km: " + plan.size());

        // Cada chunk dentro del radio (y fuera de vanilla) cubierto exactamente una vez.
        int camChunkX = (int) Math.floor(camX / 16), camChunkZ = (int) Math.floor(camZ / 16);
        java.util.Map<Long, Integer> cobertura = new java.util.HashMap<>();
        for (PlanCeldas.Celda c : plan) {
            int ladoChunks = c.ladoEnBloques() / 16;
            for (int dx = 0; dx < ladoChunks; dx++) {
                for (int dz = 0; dz < ladoChunks; dz++) {
                    if (!c.esGrande() && c.omitido(dx, dz)) {
                        continue;
                    }
                    long chunk = ((long) (c.origenX() / 16 + dx) << 32) | ((c.origenZ() / 16 + dz) & 0xFFFFFFFFL);
                    cobertura.merge(chunk, 1, Integer::sum);
                }
            }
        }
        assertTrue(cobertura.values().stream().allMatch(n -> n == 1), "Ningún chunk en dos piezas");
        for (int x = -radioChunks + 20; x < radioChunks - 20; x += 37) {
            for (int z = -radioChunks + 20; z < radioChunks - 20; z += 41) {
                long ddx = x, ddz = z;
                if (ddx * ddx + ddz * ddz > (long) (radioChunks - 20) * (radioChunks - 20) || ddx * ddx + ddz * ddz < 100) {
                    continue;
                }
                long chunk = ((long) (camChunkX + x) << 32) | ((camChunkZ + z) & 0xFFFFFFFFL);
                assertTrue(cobertura.containsKey(chunk), "Hueco en el chunk " + (camChunkX + x) + "," + (camChunkZ + z));
            }
        }
    }

    @Test
    void lasCarasSinLuzSeDescartanSoloSiSePide() {
        SuperVoxel enterrado = solido(0);
        GeometriaLod todas = new GeometriaLod();
        GeometriaLod sinCuevas = new GeometriaLod();
        sinCuevas.descartarCarasSinLuz(true);

        assertEquals(6, todas.agregarSeccion(new SuperVoxel[]{enterrado}, 1, 0, 0, 0, 16));
        assertEquals(0, sinCuevas.agregarSeccion(new SuperVoxel[]{enterrado}, 1, 0, 0, 0, 16));
        assertEquals(6, sinCuevas.agregarSeccion(new SuperVoxel[]{solido(8)}, 1, 0, 0, 0, 16), "Con luz se dibuja");
    }

    @Test
    void todasLasCarasSonAntihorariasVistasDesdeAfuera() {
        GeometriaLod g = new GeometriaLod();
        g.agregarSeccion(new SuperVoxel[]{solido(15)}, 1, 0, 0, 0, 4);
        assertEquals(24, g.vertices());
        for (int q = 0; q < g.vertices(); q += 4) {
            double[] a = {g.x(q), g.y(q), g.z(q)}, b = {g.x(q + 1), g.y(q + 1), g.z(q + 1)},
                    c = {g.x(q + 2), g.y(q + 2), g.z(q + 2)};
            double[] e1 = {b[0] - a[0], b[1] - a[1], b[2] - a[2]}, e2 = {c[0] - a[0], c[1] - a[1], c[2] - a[2]};
            double[] n = {e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0]};
            int cara = g.cara(q);
            int eje = cara >> 1;
            double signo = (cara & 1) == 1 ? 1 : -1;
            assertTrue(n[eje] * signo > 0, "La normal por orden de vértices apunta hacia afuera en la cara " + cara);
        }
    }

    @Test
    void cadaDireccionTieneSuGrupoConSusPlanos() {
        GeometriaLod g = new GeometriaLod();
        g.agregarSeccion(new SuperVoxel[]{solido(15)}, 1, 16, 32, 48, 8); // cubo de 16..24, 32..40, 48..56
        for (int cara = 0; cara < GeometriaLod.CARAS; cara++) {
            assertEquals(4, g.verticesDeCara(cara));
        }
        assertEquals(24f, g.planoMin(1), "+X en x = 24");
        assertEquals(16f, g.planoMax(0), "-X en x = 16");
        assertEquals(40f, g.planoMin(3), "+Y en y = 40");

        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(4 * GeometriaLod.BYTES_COMPACTO);
        g.escribirCompacto(b, 3);
        assertEquals(b.capacity(), b.position(), "Solo los 4 vértices de arriba");
        assertEquals(3, b.get(GeometriaLod.BYTES_COMPACTO - 1), "Y todos son de la cara +Y");
    }

    @Test
    void losGruposQueMiranParaElOtroLadoSeSaltean() {
        // Techo en y = 40: se ve desde arriba, no desde abajo.
        assertTrue(GeometriaLod.caraVisible(3, 100, 40, 40));
        assertFalse(GeometriaLod.caraVisible(3, 20, 40, 40));
        // Caras -Y (debajo de salientes) entre y 10 y 60: visibles si la cámara está debajo de alguna.
        assertTrue(GeometriaLod.caraVisible(2, 30, 10, 60));
        assertFalse(GeometriaLod.caraVisible(2, 70, 10, 60));
        // Cámara exactamente en el plano: de canto, no se ve.
        assertFalse(GeometriaLod.caraVisible(1, 24, 24, 24));
    }

    @Test
    void laDiagonalDelQuadUneLasEsquinasMasClaras() {
        // Esquina 0 oscura: la diagonal 0-2 la estiraría; se rota para usar 1-3.
        int[] orden = GeometriaLod.ordenEsquinas(true, new int[]{0, 3, 3, 3});
        assertEquals(List.of(1, 2, 3, 0), Arrays.stream(orden).boxed().toList());
        // Sin diferencia no se rota, y el sentido inverso se conserva.
        assertEquals(List.of(0, 1, 2, 3), Arrays.stream(GeometriaLod.ordenEsquinas(true, new int[]{3, 3, 3, 3}))
                .boxed().toList());
        assertEquals(List.of(3, 2, 1, 0), Arrays.stream(GeometriaLod.ordenEsquinas(false, new int[]{3, 3, 3, 3}))
                .boxed().toList());
    }

    @Test
    void conOclusionElRinconSaleMasOscuro() {
        // Dos vóxeles: piso (y = 0) y un bloque encima en x = 0; la cara de arriba del
        // piso en x = 1 tiene su esquina junto al bloque más oscura.
        int lado = 2;
        SuperVoxel[] g = new SuperVoxel[lado * lado * lado];
        SuperVoxel aire = new SuperVoxel((byte) 0, (byte) 0, (byte) 0, (byte) 0, SuperVoxel.Material.AIRE, (byte) 0);
        Arrays.fill(g, aire);
        for (int x = 0; x < lado; x++) {
            for (int z = 0; z < lado; z++) {
                g[(x * lado) * lado + z] = solido(15);
            }
        }
        g[(0 * lado + 1) * lado] = solido(15);
        g[(0 * lado + 1) * lado + 1] = solido(15);
        GeometriaLod con = new GeometriaLod();
        con.usarOclusionAmbiental(true);
        con.agregarSeccion(g, lado, 0, 0, 0, 1);
        int minimo = 255, maximo = 0;
        for (int i = 0; i < con.vertices(); i++) {
            if (con.cara(i) == 3 && con.y(i) == 1) { // techo del piso
                int rojo = (con.color(i) >> 16) & 0xFF;
                minimo = Math.min(minimo, rojo);
                maximo = Math.max(maximo, rojo);
            }
        }
        assertTrue(minimo < maximo, "El techo junto al bloque tiene vértices más oscuros");
    }

    @Test
    void laProyeccionConservaElBalanceoDeCamaraYCambiaSoloLaProfundidad() {
        float fov = (float) Math.toRadians(70), aspecto = 16f / 9f;
        org.joml.Matrix4f balanceo = new org.joml.Matrix4f().translation(0.02f, -0.05f, 0f)
                .rotateZ(0.03f).rotateX(0.02f);
        org.joml.Matrix4f vanilla = new org.joml.Matrix4f().perspective(fov, aspecto, 0.05f, 512f).mul(balanceo);
        org.joml.Matrix4f esperada = new org.joml.Matrix4f().perspective(fov, aspecto, 16f, 5000f).mul(balanceo);

        org.joml.Matrix4f lod = PlanCeldas.conPlanosDeProfundidad(new org.joml.Matrix4f(vanilla), 16f, 5000f);

        assertTrue(lod.equals(esperada, 1e-4f), "Misma matriz que armarla con el balanceo incluido:\n" + lod + esperada);
    }

    @Test
    void unChunkQueVanillaTodaviaNoCargoLoSigueDibujandoElLod() {
        // Cámara en el chunk (0, 0), distancia vanilla 8: vanilla tiene todo menos el chunk (3, 0).
        long faltante = PlanCeldas.claveChunk(3, 0);
        List<PlanCeldas.Celda> plan = PlanCeldas.planificarConGrandes(8, 8, 64, 8, clave -> clave != faltante,
                null, Math.toRadians(70), 1080, 2.5);
        PlanCeldas.Celda celda = plan.stream().filter(c -> !c.esGrande() && c.celdaX() == 0 && c.celdaZ() == 0)
                .findFirst().orElseThrow(() -> new AssertionError("La celda del chunk faltante tiene que estar en el plan"));
        assertFalse(celda.omitido(3, 0), "El chunk sin cargar lo dibuja el LOD");
        assertTrue(celda.omitido(2, 0), "Los que vanilla ya tiene se le dejan a vanilla");
    }

    @Test
    void conZoomSoloLoQueSeMiraPideMasDetalle() {
        double normal = Math.toRadians(70), zoom = Math.toRadians(10);
        // Mirando a +X, cono de ±15°.
        PlanCeldas.Vista vista = new PlanCeldas.Vista(1, 0, normal, Math.toRadians(15));
        List<PlanCeldas.Celda> sinZoom = PlanCeldas.planificarConGrandes(8, 8, 96, 8, c -> true, vista, normal, 1080, 2.5);
        List<PlanCeldas.Celda> conZoom = PlanCeldas.planificarConGrandes(8, 8, 96, 8, c -> true, vista, zoom, 1080, 2.5);

        java.util.function.Function<List<PlanCeldas.Celda>, java.util.Map<Long, Integer>> niveles = plan -> {
            java.util.Map<Long, Integer> m = new java.util.HashMap<>();
            for (PlanCeldas.Celda c : plan) if (!c.esGrande()) m.put(((long) c.celdaX() << 32) ^ (c.celdaZ() & 0xFFFFFFFFL), c.nivel());
            return m;
        };
        var antes = niveles.apply(sinZoom);
        var despues = niveles.apply(conZoom);
        // Adelante, a ~1000 bloques (celda x=15): más fino con zoom.
        long adelante = (15L << 32);
        long atras = ((long) -16 << 32);
        assertTrue(despues.get(adelante) < antes.get(adelante), "Adelante, el zoom afina el nivel");
        assertEquals(antes.get(atras), despues.get(atras), "Atrás, el zoom no cambia nada");
    }

    @Test
    void elFramebufferDeFsrEsLaFraccionDeLaPantallaSinPasarseNiQuedarEnCero() {
        assertEquals(1478, EscaladoFsr.tamanoEscalado(1920, 0.77));
        assertEquals(540, EscaladoFsr.tamanoEscalado(1080, 0.5));
        assertEquals(1920, EscaladoFsr.tamanoEscalado(1920, 1.2), "Nunca más grande que la pantalla");
        assertEquals(1, EscaladoFsr.tamanoEscalado(1, 0.5), "Nunca cero");
    }
}
