package com.example.minecraftlodmod.generation;

import com.example.minecraftlodmod.core.SuperVoxel;

/**
 * Calcula la luz horneada "suavizada" en las 4 esquinas de un {@link Quad}.
 *
 * Problema que resuelve: el greedy mesher fusiona muchos vóxeles en un solo
 * quad grande basándose en material/color, pero la luz horneada de esos
 * vóxeles puede variar dentro del área fusionada (por ejemplo, un borde de
 * sombra a mitad del quad). Si se usara un solo valor de luz por quad, se
 * vería "plano" — un salto brusco de brillo entre quads en vez de un
 * degradado. En cambio, promediando la luz alrededor de cada esquina
 * (igual que el "smooth lighting" que ya usa el propio Minecraft para sus
 * chunks normales) y dejando que el renderer interpole esos 4 valores a lo
 * largo de la cara, el resultado se ve continuo aunque la geometría esté
 * muy fusionada.
 *
 * Esto es lógica pura: no sube nada a GPU, solo calcula los 4 valores de
 * luz (0-15) que render/ después usa como color de vértice.
 */
public final class VertexLightSampler {

    private VertexLightSampler() {
    }

    /** Luz asumida para una esquina sin ningún vóxel sólido alrededor (aire completamente abierto). */
    private static final int LUZ_POR_DEFECTO_AIRE = 15;

    public record LuzEsquinas(int minMin, int maxMin, int minMax, int maxMax) {
    }

    public static LuzEsquinas calcular(SuperVoxel[] grid, int lado, Quad quad) {
        Decomposicion d = decomponer(quad);

        return new LuzEsquinas(
                luzEnEsquina(grid, lado, quad.eje(), d.capa, d.uStart, d.vStart),
                luzEnEsquina(grid, lado, quad.eje(), d.capa, d.uStart + d.uLen, d.vStart),
                luzEnEsquina(grid, lado, quad.eje(), d.capa, d.uStart, d.vStart + d.vLen),
                luzEnEsquina(grid, lado, quad.eje(), d.capa, d.uStart + d.uLen, d.vStart + d.vLen)
        );
    }

    /** Inversa de la construcción de Quad en GreedyMesher: recupera (capa, uStart, vStart, uLen, vLen). */
    private record Decomposicion(int capa, int uStart, int vStart, int uLen, int vLen) {
    }

    private static Decomposicion decomponer(Quad q) {
        return switch (q.eje()) {
            // Ver GreedyMesher#construirQuad: para el eje X, ancho=anchoV y alto=anchoU.
            case X -> new Decomposicion(q.x(), q.y(), q.z(), q.alto(), q.ancho());
            case Y -> new Decomposicion(q.y(), q.x(), q.z(), q.ancho(), q.alto());
            case Z -> new Decomposicion(q.z(), q.x(), q.y(), q.ancho(), q.alto());
        };
    }

    private static int luzEnEsquina(SuperVoxel[] grid, int lado, Quad.Eje eje, int capa, int u, int v) {
        int suma = 0;
        int cantidad = 0;

        for (int du = -1; du <= 0; du++) {
            for (int dv = -1; dv <= 0; dv++) {
                SuperVoxel vecino = obtener(grid, lado, eje, capa, u + du, v + dv);
                if (vecino != null && vecino.material() != SuperVoxel.Material.AIRE) {
                    suma += vecino.luzHorneada();
                    cantidad++;
                }
            }
        }

        if (cantidad == 0) return LUZ_POR_DEFECTO_AIRE;
        return Math.round(suma / (float) cantidad);
    }

    /** Misma indexación que GreedyMesher#obtener — duplicada a propósito para mantener este sampler independiente. */
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
