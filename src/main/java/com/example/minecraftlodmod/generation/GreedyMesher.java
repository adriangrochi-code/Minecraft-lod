package com.example.minecraftlodmod.generation;

import com.example.minecraftlodmod.core.SuperVoxel;

import java.util.ArrayList;
import java.util.List;

/**
 * Greedy mesher: recorre una grilla cúbica de supervóxeles y fusiona caras
 * visibles coplanares (mismo material y mismo color, cara expuesta a un
 * vóxel "aire" o al borde del nodo) en la menor cantidad posible de quads
 * rectangulares. Ver sección 2 (arquitectura) y 6 (render).
 *
 * Implementación basada en el algoritmo clásico de "greedy meshing" para
 * vóxeles (variante simplificada del de Mikola Lysenko), adaptada para
 * trabajar sobre SuperVoxel en vez de bloques individuales.
 *
 * Oclusión ambiental por vértice (sección 25, punto 5): por cada cara de
 * vóxel se miran los 3 vecinos de cada esquina en la capa de enfrente (dos
 * costados y la diagonal), como el "smooth lighting" de vanilla. Solo se
 * fusionan caras con la MISMA oclusión en sus 4 esquinas: si no, un quad
 * grande interpolaría el oscurecimiento de un rincón a lo largo de toda la
 * cara. Solo en caras de ARRIBA: en todas las caras costaba ~+42% de
 * vértices (medido en el mundo de benchmark); en las de arriba, que son las
 * que más se ven del terreno (pasto junto a paredes, bajo árboles), ~+12%
 * guardados / ~+23% dibujados.
 *
 * Nota: esta clase resuelve la geometría; NO sube nada a GPU — eso es
 * responsabilidad de render/, que today no existe todavía en este esqueleto.
 */
public final class GreedyMesher {

    private GreedyMesher() {
    }

    /**
     * Vóxeles de las grillas vecinas, para no emitir caras del borde tapadas
     * por ellas (idea de las máscaras de vecinos de Voxy — sección 25, sin
     * su código). Sin esto, cada sección es una caja cerrada: entre dos
     * secciones apiladas quedan caras internas que nadie ve.
     */
    @FunctionalInterface
    public interface Vecinos {
        /**
         * @param x, y, z coordenada en la grilla, con UNA de ellas fuera de
         *           [0, lado) por un paso (-1 o lado): la celda vecina de la cara
         * @return el vóxel vecino, o null si se desconoce (la cara se dibuja)
         */
        SuperVoxel en(int x, int y, int z);

        /**
         * Vecinos a partir de las 6 grillas adyacentes del MISMO lado (mismo
         * nivel); una grilla null = vecino desconocido, esa cara se dibuja.
         */
        static Vecinos deGrillas(int lado, SuperVoxel[] xNeg, SuperVoxel[] xPos, SuperVoxel[] yNeg,
                                 SuperVoxel[] yPos, SuperVoxel[] zNeg, SuperVoxel[] zPos) {
            return (x, y, z) -> {
                SuperVoxel[] g;
                if (x < 0) { g = xNeg; x = lado - 1; }
                else if (x >= lado) { g = xPos; x = 0; }
                else if (y < 0) { g = yNeg; y = lado - 1; }
                else if (y >= lado) { g = yPos; y = 0; }
                else if (z < 0) { g = zNeg; z = lado - 1; }
                else { g = zPos; z = 0; }
                return g == null ? null : g[(x * lado + y) * lado + z];
            };
        }
    }

    /**
     * @param grid grilla plana de supervóxeles, indexada por (x*lado+y)*lado+z
     * @param lado longitud de arista de la grilla cúbica
     */
    public static List<Quad> mallar(SuperVoxel[] grid, int lado) {
        return mallar(grid, lado, null);
    }

    /** @param vecinos vóxeles del otro lado del borde; null = todo borde expuesto */
    public static List<Quad> mallar(SuperVoxel[] grid, int lado, Vecinos vecinos) {
        return mallar(grid, lado, vecinos, false);
    }

    /** @param conOclusion calcular oclusión ambiental por esquina (si no, {@link Quad#SIN_OCLUSION}) */
    public static List<Quad> mallar(SuperVoxel[] grid, int lado, Vecinos vecinos, boolean conOclusion) {
        List<Quad> quads = new ArrayList<>();
        for (Quad.Eje eje : Quad.Eje.values()) {
            quads.addAll(mallarEje(grid, lado, eje, true, vecinos, conOclusion && eje == Quad.Eje.Y));
            quads.addAll(mallarEje(grid, lado, eje, false, vecinos, false));
        }
        return quads;
    }

    private static boolean esAgua(SuperVoxel v) {
        return v != null && v.material() == SuperVoxel.Material.AGUA;
    }

    /** Color y estado de bloque de la cara de arriba de un vóxel nevado; los fija el cliente. */
    private static volatile int rgbNieve = 0xF4FBFB;
    private static volatile short estadoNieve = SuperVoxel.SIN_ESTADO;

    /**
     * Lo llama el cliente al cargar recursos: color promedio y estado de la
     * capa de nieve (para dibujar su textura). Sin llamarlo, la nieve va con
     * un blanco fijo y sin textura.
     */
    public static void definirNieve(int rgb, int estado) {
        rgbNieve = rgb;
        estadoNieve = estado < 0 || estado > 0xFFFF ? SuperVoxel.SIN_ESTADO : (short) estado;
    }

    /** La cara de arriba de un vóxel nevado: nieve, con la luz y el material del vóxel. */
    static SuperVoxel superficieNevada(SuperVoxel v) {
        int rgb = rgbNieve;
        return new SuperVoxel((byte) (rgb >> 16), (byte) (rgb >> 8), (byte) rgb, v.alturaLocal(), v.material(),
                v.flags(), estadoNieve);
    }

    private static boolean esAire(SuperVoxel v) {
        return v == null || v.material() == SuperVoxel.Material.AIRE;
    }

    /**
     * true si dos vóxeles se pueden fusionar en el mismo quad: mismo
     * material, color y estado de bloque (distinto estado = distinta
     * textura, no se pueden dibujar como una sola cara).
     */
    private static boolean mismaSuperficie(SuperVoxel a, SuperVoxel b) {
        if (a == null || b == null) return false;
        return a.material() == b.material() && a.estado() == b.estado()
                && a.r() == b.r() && a.g() == b.g() && a.b() == b.b();
    }

    private static List<Quad> mallarEje(SuperVoxel[] grid, int lado, Quad.Eje eje, boolean positivo,
                                        Vecinos vecinos, boolean conOclusion) {
        List<Quad> resultado = new ArrayList<>();
        // Una sola vez por eje (antes, tres matrices nuevas por capa): se limpian entre capas.
        SuperVoxel[][] mascara = new SuperVoxel[lado][lado];
        int[][] oclusion = new int[lado][lado];
        boolean[][] visitado = new boolean[lado][lado];

        // Recorremos capa por capa a lo largo del eje principal.
        for (int capa = 0; capa < lado; capa++) {
            // Máscara 2D: qué "superficie" (vóxel visible desde esta cara) hay en cada celda de la capa.
            for (int u = 0; u < lado; u++) {
                java.util.Arrays.fill(mascara[u], null);
                java.util.Arrays.fill(visitado[u], false);
            }

            for (int u = 0; u < lado; u++) {
                for (int v = 0; v < lado; v++) {
                    SuperVoxel actual = obtener(grid, lado, eje, capa, u, v);
                    if (esAire(actual)) continue;

                    int capaVecina = positivo ? capa + 1 : capa - 1;
                    SuperVoxel vecino = (capaVecina >= 0 && capaVecina < lado)
                            ? obtener(grid, lado, eje, capaVecina, u, v)
                            : afuera(vecinos, eje, capaVecina, u, v); // sin vecinos: cara expuesta

                    // Cara visible: contra aire, o un sólido contra agua (fondo marino: se ve
                    // a través del agua; sin esto, mirando a ras del agua quedaban huecos).
                    boolean bajoAgua = esAgua(vecino) && !esAgua(actual);
                    if (esAire(vecino) || bajoAgua) {
                        mascara[u][v] = eje == Quad.Eje.Y && positivo && actual.nevado()
                                ? superficieNevada(actual) : actual;
                        oclusion[u][v] = (conOclusion
                                ? oclusionCara(grid, lado, eje, capaVecina, u, v, vecinos) : Quad.SIN_OCLUSION)
                                | (bajoAgua ? Quad.BAJO_AGUA : 0);
                    }
                }
            }

            fusionarMascara(mascara, oclusion, visitado, lado, capa, eje, positivo, resultado);
        }

        return resultado;
    }

    /** Algoritmo greedy 2D estándar: barre la máscara y va extendiendo rectángulos lo más posible. */
    private static void fusionarMascara(SuperVoxel[][] mascara, int[][] oclusion, boolean[][] visitado, int lado,
                                        int capa, Quad.Eje eje, boolean positivo, List<Quad> quads) {

        for (int u = 0; u < lado; u++) {
            for (int v = 0; v < lado; v++) {
                if (visitado[u][v] || mascara[u][v] == null) continue;

                SuperVoxel referencia = mascara[u][v];
                int oclusionReferencia = oclusion[u][v];

                // Extender en la dirección "v" mientras siga siendo la misma superficie.
                int anchoV = 1;
                while (v + anchoV < lado
                        && !visitado[u][v + anchoV]
                        && mismaSuperficie(mascara[u][v + anchoV], referencia)
                        && oclusion[u][v + anchoV] == oclusionReferencia) {
                    anchoV++;
                }

                // Extender en la dirección "u" mientras toda la fila (de ancho anchoV) siga calzando.
                int anchoU = 1;
                filaSiguiente:
                while (u + anchoU < lado) {
                    for (int dv = 0; dv < anchoV; dv++) {
                        if (visitado[u + anchoU][v + dv]
                                || !mismaSuperficie(mascara[u + anchoU][v + dv], referencia)
                                || oclusion[u + anchoU][v + dv] != oclusionReferencia) {
                            break filaSiguiente;
                        }
                    }
                    anchoU++;
                }

                // Marcar visitado el rectángulo encontrado.
                for (int du = 0; du < anchoU; du++) {
                    for (int dv = 0; dv < anchoV; dv++) {
                        visitado[u + du][v + dv] = true;
                    }
                }

                quads.add(construirQuad(eje, positivo, capa, u, v, anchoU, anchoV, referencia, oclusionReferencia));
            }
        }
    }

    private static Quad construirQuad(Quad.Eje eje, boolean positivo, int capa, int u, int v,
                                       int anchoU, int anchoV, SuperVoxel referencia, int oclusion) {
        // Mapear (capa, u, v) de vuelta a (x, y, z) según el eje — convención:
        // eje X -> capa=x, u=y, v=z | eje Y -> capa=y, u=x, v=z | eje Z -> capa=z, u=x, v=y
        return switch (eje) {
            case X -> new Quad(capa, u, v, anchoV, anchoU, eje, positivo, referencia, oclusion);
            case Y -> new Quad(u, capa, v, anchoU, anchoV, eje, positivo, referencia, oclusion);
            case Z -> new Quad(u, v, capa, anchoU, anchoV, eje, positivo, referencia, oclusion);
        };
    }

    /**
     * Oclusión de las 4 esquinas de la cara de vóxel (u, v), mirando la capa
     * de enfrente ({@code capaFrente}): por esquina, 3 - (costados + diagonal
     * que ocluyen), y 0 si ocluyen los dos costados (rincón cerrado).
     */
    static int oclusionCara(SuperVoxel[] grid, int lado, Quad.Eje eje, int capaFrente, int u, int v,
                            Vecinos vecinos) {
        int resultado = 0;
        for (int esquina = 0; esquina < 4; esquina++) {
            int du = (esquina & 1) == 0 ? -1 : 1;
            int dv = (esquina & 2) == 0 ? -1 : 1;
            boolean costadoU = ocluye(grid, lado, eje, capaFrente, u + du, v, vecinos);
            boolean costadoV = ocluye(grid, lado, eje, capaFrente, u, v + dv, vecinos);
            int valor;
            if (costadoU && costadoV) {
                valor = 0;
            } else {
                boolean diagonal = ocluye(grid, lado, eje, capaFrente, u + du, v + dv, vecinos);
                valor = 3 - ((costadoU ? 1 : 0) + (costadoV ? 1 : 0) + (diagonal ? 1 : 0));
            }
            resultado |= valor << (esquina * 2);
        }
        return resultado;
    }

    /** Sólidos y vegetación ocluyen; aire y agua no (el agua no oscurece la orilla). */
    private static boolean ocluye(SuperVoxel[] grid, int lado, Quad.Eje eje, int capa, int u, int v,
                                  Vecinos vecinos) {
        int fuera = (capa < 0 || capa >= lado ? 1 : 0) + (u < 0 || u >= lado ? 1 : 0) + (v < 0 || v >= lado ? 1 : 0);
        SuperVoxel s;
        if (fuera == 0) {
            s = obtener(grid, lado, eje, capa, u, v);
        } else if (fuera == 1) {
            s = afuera(vecinos, eje, capa, u, v);
        } else {
            return false; // diagonal fuera de dos grillas a la vez: sin dato, no ocluye
        }
        return s != null && s.material() != SuperVoxel.Material.AIRE && s.material() != SuperVoxel.Material.AGUA;
    }

    private static SuperVoxel afuera(Vecinos vecinos, Quad.Eje eje, int capa, int u, int v) {
        if (vecinos == null) {
            return null;
        }
        return switch (eje) {
            case X -> vecinos.en(capa, u, v);
            case Y -> vecinos.en(u, capa, v);
            case Z -> vecinos.en(u, v, capa);
        };
    }

    private static SuperVoxel obtener(SuperVoxel[] grid, int lado, Quad.Eje eje, int capa, int u, int v) {
        int x, y, z;
        switch (eje) {
            case X -> { x = capa; y = u; z = v; }
            case Y -> { x = u; y = capa; z = v; }
            default -> { x = u; y = v; z = capa; }
        }
        if (x < 0 || x >= lado || y < 0 || y >= lado || z < 0 || z >= lado) return null;
        return grid[(x * lado + y) * lado + z];
    }
}
