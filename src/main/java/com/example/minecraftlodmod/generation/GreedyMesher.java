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
        List<Quad> quads = new ArrayList<>();
        for (Quad.Eje eje : Quad.Eje.values()) {
            quads.addAll(mallarEje(grid, lado, eje, true, vecinos));
            quads.addAll(mallarEje(grid, lado, eje, false, vecinos));
        }
        return quads;
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
                                        Vecinos vecinos) {
        List<Quad> resultado = new ArrayList<>();

        // Recorremos capa por capa a lo largo del eje principal.
        for (int capa = 0; capa < lado; capa++) {
            // Máscara 2D: qué "superficie" (vóxel visible desde esta cara) hay en cada celda de la capa.
            SuperVoxel[][] mascara = new SuperVoxel[lado][lado];

            for (int u = 0; u < lado; u++) {
                for (int v = 0; v < lado; v++) {
                    SuperVoxel actual = obtener(grid, lado, eje, capa, u, v);
                    if (esAire(actual)) continue;

                    int capaVecina = positivo ? capa + 1 : capa - 1;
                    SuperVoxel vecino = (capaVecina >= 0 && capaVecina < lado)
                            ? obtener(grid, lado, eje, capaVecina, u, v)
                            : afuera(vecinos, eje, capaVecina, u, v); // sin vecinos: cara expuesta

                    if (esAire(vecino)) {
                        mascara[u][v] = actual;
                    }
                }
            }

            resultado.addAll(fusionarMascara(mascara, lado, capa, eje, positivo));
        }

        return resultado;
    }

    /** Algoritmo greedy 2D estándar: barre la máscara y va extendiendo rectángulos lo más posible. */
    private static List<Quad> fusionarMascara(SuperVoxel[][] mascara, int lado, int capa,
                                               Quad.Eje eje, boolean positivo) {
        List<Quad> quads = new ArrayList<>();
        boolean[][] visitado = new boolean[lado][lado];

        for (int u = 0; u < lado; u++) {
            for (int v = 0; v < lado; v++) {
                if (visitado[u][v] || mascara[u][v] == null) continue;

                SuperVoxel referencia = mascara[u][v];

                // Extender en la dirección "v" mientras siga siendo la misma superficie.
                int anchoV = 1;
                while (v + anchoV < lado
                        && !visitado[u][v + anchoV]
                        && mismaSuperficie(mascara[u][v + anchoV], referencia)) {
                    anchoV++;
                }

                // Extender en la dirección "u" mientras toda la fila (de ancho anchoV) siga calzando.
                int anchoU = 1;
                filaSiguiente:
                while (u + anchoU < lado) {
                    for (int dv = 0; dv < anchoV; dv++) {
                        if (visitado[u + anchoU][v + dv]
                                || !mismaSuperficie(mascara[u + anchoU][v + dv], referencia)) {
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

                quads.add(construirQuad(eje, positivo, capa, u, v, anchoU, anchoV, referencia));
            }
        }

        return quads;
    }

    private static Quad construirQuad(Quad.Eje eje, boolean positivo, int capa, int u, int v,
                                       int anchoU, int anchoV, SuperVoxel referencia) {
        // Mapear (capa, u, v) de vuelta a (x, y, z) según el eje — convención:
        // eje X -> capa=x, u=y, v=z | eje Y -> capa=y, u=x, v=z | eje Z -> capa=z, u=x, v=y
        return switch (eje) {
            case X -> new Quad(capa, u, v, anchoV, anchoU, eje, positivo, referencia);
            case Y -> new Quad(u, capa, v, anchoU, anchoV, eje, positivo, referencia);
            case Z -> new Quad(u, v, capa, anchoU, anchoV, eje, positivo, referencia);
        };
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
