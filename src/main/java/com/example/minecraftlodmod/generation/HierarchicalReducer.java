package com.example.minecraftlodmod.generation;

import com.example.minecraftlodmod.core.SuperVoxel;

/**
 * Reductor jerárquico: deriva un nivel de LOD a partir del nivel
 * inmediatamente inferior ya calculado (nunca desde los datos originales),
 * para que el costo total de construir todos los niveles sea O(n) y no
 * O(n * niveles) — ver sección 2 y 4 del documento de arquitectura.
 *
 * Fusiona bloques de 2x2x2 supervóxeles del nivel de entrada en 1
 * supervóxel del nivel de salida:
 *  - es AIRE solo si más de la mitad del bloque es aire; con 4 o más
 *    vóxeles visibles queda visible, así las superficies de un bloque de
 *    espesor no se "hunden" al subir de nivel;
 *  - el material es el más votado entre los vóxeles visibles;
 *  - color, altura y luz horneada se promedian solo sobre los visibles: el
 *    aire no aporta color (antes entraba como negro y oscurecía todo nivel
 *    reducido, y la luz se perdía — corregido con el primer render).
 */
public final class HierarchicalReducer {

    private HierarchicalReducer() {
    }

    /**
     * @param entrada  array plano de supervóxeles del nivel inferior, tamaño lado^3
     * @param lado     longitud de arista del cubo de entrada, en supervóxeles (debe ser par)
     * @return array plano del nivel derivado, tamaño (lado/2)^3
     */
    public static SuperVoxel[] reducir(SuperVoxel[] entrada, int lado) {
        if (lado % 2 != 0) {
            throw new IllegalArgumentException("El lado debe ser par para reducir en bloques 2x2x2, fue: " + lado);
        }
        int ladoSalida = lado / 2;
        SuperVoxel[] salida = new SuperVoxel[ladoSalida * ladoSalida * ladoSalida];

        for (int x = 0; x < ladoSalida; x++) {
            for (int y = 0; y < ladoSalida; y++) {
                for (int z = 0; z < ladoSalida; z++) {
                    SuperVoxel fusionado = fusionarBloque(entrada, lado, x * 2, y * 2, z * 2);
                    salida[indice(x, y, z, ladoSalida)] = fusionado;
                }
            }
        }
        return salida;
    }

    private static SuperVoxel fusionarBloque(SuperVoxel[] entrada, int lado, int ox, int oy, int oz) {
        int sumaR = 0, sumaG = 0, sumaB = 0, sumaAltura = 0, sumaLuz = 0;
        int[] votosMaterial = new int[SuperVoxel.Material.values().length];
        int visibles = 0;

        for (int dx = 0; dx < 2; dx++) {
            for (int dy = 0; dy < 2; dy++) {
                for (int dz = 0; dz < 2; dz++) {
                    SuperVoxel v = entrada[indice(ox + dx, oy + dy, oz + dz, lado)];
                    if (v.material() == SuperVoxel.Material.AIRE) {
                        continue;
                    }
                    sumaR += v.r() & 0xFF;
                    sumaG += v.g() & 0xFF;
                    sumaB += v.b() & 0xFF;
                    sumaAltura += v.alturaLocal() & 0xFF;
                    sumaLuz += v.luzHorneada();
                    votosMaterial[v.material().ordinal()]++;
                    visibles++;
                }
            }
        }

        if (visibles * 2 < 8) {
            return new SuperVoxel((byte) 0, (byte) 0, (byte) 0, (byte) 0, SuperVoxel.Material.AIRE, (byte) 0);
        }
        return new SuperVoxel(
                (byte) (sumaR / visibles),
                (byte) (sumaG / visibles),
                (byte) (sumaB / visibles),
                (byte) (sumaAltura / visibles),
                materialMasVotado(votosMaterial),
                (byte) 0
        ).conLuzHorneada(Math.round(sumaLuz / (float) visibles));
    }

    private static SuperVoxel.Material materialMasVotado(int[] votos) {
        int mejorIndice = 0;
        for (int i = 1; i < votos.length; i++) {
            if (votos[i] > votos[mejorIndice]) mejorIndice = i;
        }
        return SuperVoxel.Material.values()[mejorIndice];
    }

    private static int indice(int x, int y, int z, int lado) {
        return (x * lado + y) * lado + z;
    }

    /** true si los 8 supervóxeles de un bloque 2x2x2 son idénticos — útil para decidir colapso por homogeneidad. */
    public static boolean esBloqueHomogeneo(SuperVoxel[] entrada, int lado, int ox, int oy, int oz) {
        SuperVoxel primero = entrada[indice(ox, oy, oz, lado)];
        for (int dx = 0; dx < 2; dx++) {
            for (int dy = 0; dy < 2; dy++) {
                for (int dz = 0; dz < 2; dz++) {
                    if (!entrada[indice(ox + dx, oy + dy, oz + dz, lado)].equals(primero)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }
}
