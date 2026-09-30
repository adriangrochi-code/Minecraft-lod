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
 *  - queda visible con 4 o más vóxeles visibles (así las superficies de un
 *    bloque de espesor no se "hunden" al subir de nivel), si lo sólido
 *    llega en promedio a un cuarto de su alto (una costa, el borde de una
 *    meseta), o si cubre al menos 2 de sus 4 columnas vistas desde arriba:
 *    el terreno lejano se ve desde arriba, y con la regla de volumen sola
 *    las copas de los árboles (ralas) se perdían nivel a nivel y quedaba el
 *    suelo pelado a pocos chunks;
 *  - el RELLENO ({@link SuperVoxel#relleno()}) es la altura media de lo
 *    sólido en sus 4 columnas: en cada una, el hijo de arriba cuenta su
 *    relleno más el alto entero del de abajo; el de abajo solo, su relleno.
 *    Así una sección de pasto a 5 bloques del piso da un vóxel de 16 con la
 *    superficie a 5 bloques, no un cubo entero;
 *  - el material es el más votado entre los vóxeles visibles;
 *  - color, luz horneada y estado de bloque salen de la SUPERFICIE vista
 *    desde arriba (por columna, el vóxel visible más alto): el terreno
 *    lejano se ve desde arriba, y una ladera de pasto tiene que seguir
 *    siendo pasto, no la tierra o piedra que hay debajo. El estado es el
 *    más frecuente de esa superficie (define la textura en el render), y
 *    el color se promedia SOLO entre los vóxeles de ese estado: bloque
 *    representativo al estilo del "mipper" de Voxy (idea, no código —
 *    sección 25). Mezclar pasto con piedra daba un color barroso que no
 *    es de ningún bloque y no casa con la textura elegida; promediar
 *    dentro del mismo estado conserva el degradé del tinte de bioma.
 *  - el aire nunca aporta color (antes entraba como negro y oscurecía todo
 *    nivel reducido, y la luz se perdía — corregido con el primer render).
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

    /** Relleno medio mínimo (en altos de hijo, de 0 a 2) para que el vóxel sea visible sin mayoría. */
    static final double RELLENO_VISIBLE = 0.5;
    /** Columnas (de 4) con algo visible que alcanzan para que el vóxel se vea desde arriba. */
    static final int COLUMNAS_VISIBLE = 2;

    private static SuperVoxel fusionarBloque(SuperVoxel[] entrada, int lado, int ox, int oy, int oz) {
        double sumaRelleno = 0; // por columna, en altos de hijo (0 a 2)
        int[] votosMaterial = new int[SuperVoxel.Material.TODOS.length];
        int visibles = 0;
        SuperVoxel[] superficie = new SuperVoxel[4];
        int enSuperficie = 0;

        for (int dx = 0; dx < 2; dx++) {
            for (int dz = 0; dz < 2; dz++) {
                SuperVoxel masAlto = null;
                double rellenoColumna = 0;
                for (int dy = 0; dy < 2; dy++) {
                    SuperVoxel v = entrada[indice(ox + dx, oy + dy, oz + dz, lado)];
                    if (v.material() == SuperVoxel.Material.AIRE) {
                        continue;
                    }
                    votosMaterial[v.material().ordinal()]++;
                    visibles++;
                    masAlto = v; // dy crece: el último visible es el más alto
                    rellenoColumna = dy + v.relleno() / (double) SuperVoxel.LLENO;
                }
                if (masAlto != null) {
                    superficie[enSuperficie++] = masAlto;
                }
                sumaRelleno += rellenoColumna;
            }
        }

        double rellenoMedio = sumaRelleno / 4;
        if (visibles * 2 < 8 && rellenoMedio < RELLENO_VISIBLE && enSuperficie < COLUMNAS_VISIBLE) {
            return new SuperVoxel((byte) 0, (byte) 0, (byte) 0, (byte) 0, SuperVoxel.Material.AIRE, (byte) 0);
        }
        short estado = estadoMasFrecuente(superficie, enSuperficie);
        int sumaR = 0, sumaG = 0, sumaB = 0, sumaLuz = 0, delEstado = 0, nevados = 0, luzBloque = 0;
        for (int i = 0; i < enSuperficie; i++) {
            SuperVoxel v = superficie[i];
            sumaLuz += v.luzHorneada();
            // La luz de bloque va por máximo: una aldea iluminada se sigue viendo de lejos.
            luzBloque = Math.max(luzBloque, v.luzBloque());
            if (v.nevado()) {
                nevados++;
            }
            if (v.estado() != estado) {
                continue;
            }
            sumaR += v.r() & 0xFF;
            sumaG += v.g() & 0xFF;
            sumaB += v.b() & 0xFF;
            delEstado++;
        }
        return new SuperVoxel(
                (byte) (sumaR / delEstado),
                (byte) (sumaG / delEstado),
                (byte) (sumaB / delEstado),
                (byte) Math.round(rellenoMedio / 2 * SuperVoxel.LLENO),
                materialMasVotado(votosMaterial),
                (byte) 0,
                estado
        ).conLuzHorneada(Math.round(sumaLuz / (float) enSuperficie))
                .conLuzBloque(luzBloque)
                .conNevado(nevados * 2 >= enSuperficie); // nieve si cubre al menos la mitad de la superficie
    }

    /** Estado más repetido entre (a lo sumo 4) vóxeles; en empate, el primero encontrado. */
    private static short estadoMasFrecuente(SuperVoxel[] voxeles, int cantidad) {
        short mejor = SuperVoxel.SIN_ESTADO;
        int mejorCuenta = 0;
        for (int i = 0; i < cantidad; i++) {
            int cuenta = 0;
            for (int j = 0; j < cantidad; j++) {
                if (voxeles[j].estado() == voxeles[i].estado()) {
                    cuenta++;
                }
            }
            if (cuenta > mejorCuenta) {
                mejor = voxeles[i].estado();
                mejorCuenta = cuenta;
            }
        }
        return mejor;
    }

    private static SuperVoxel.Material materialMasVotado(int[] votos) {
        int mejorIndice = 0;
        for (int i = 1; i < votos.length; i++) {
            if (votos[i] > votos[mejorIndice]) mejorIndice = i;
        }
        return SuperVoxel.Material.TODOS[mejorIndice];
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
