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
     * @param grid grilla plana de supervóxeles, indexada por (x*lado+y)*lado+z
     * @param lado longitud de arista de la grilla cúbica
     */
    public static List<Quad> mallar(SuperVoxel[] grid, int lado) {
        List<Quad> quads = new ArrayList<>();
        for (Quad.Eje eje : Quad.Eje.values()) {
            quads.addAll(mallarEje(grid, lado, eje, true));
            quads.addAll(mallarEje(grid, lado, eje, false));
        }
        return quads;
    }

    private static boolean esAire(SuperVoxel v) {
        return v == null || v.material() == SuperVoxel.Material.AIRE;
    }

    /** true si dos vóxeles se pueden fusionar en el mismo quad (mismo material y color). */
    private static boolean mismaSuperficie(SuperVoxel a, SuperVoxel b) {
        if (a == null || b == null) return false;
        return a.material() == b.material()
                && a.r() == b.r() && a.g() == b.g() && a.b() == b.b();
    }

    private static List<Quad> mallarEje(SuperVoxel[] grid, int lado, Quad.Eje eje, boolean positivo) {
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
                            : null; // fuera del nodo: se considera cara expuesta (el nodo vecino se resuelve aparte)

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
