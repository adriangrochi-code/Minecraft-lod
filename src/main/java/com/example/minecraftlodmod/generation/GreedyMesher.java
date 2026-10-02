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
 * Superficie a la altura real (con {@code bloquesPorVoxel} > 1): la cara de
 * arriba de un vóxel con aire (o agua) encima se dibuja a la altura de su
 * relleno ({@link SuperVoxel#relleno()}), sus costados llegan hasta ahí, y
 * contra un vecino de superficie más bajo queda a la vista el tramo entre
 * las dos alturas (sin eso quedaría un hueco). Solo se fusionan caras con el
 * mismo recorte, y un costado recortado no se estira a lo alto.
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
        return mallar(grid, lado, vecinos, conOclusion, 0);
    }

    /**
     * @param bloquesPorVoxel tamaño del vóxel en bloques: con más de 1, la
     *                        superficie se dibuja a la altura real (recortes
     *                        en bloques enteros); 0 o 1 = cubos enteros
     */
    public static List<Quad> mallar(SuperVoxel[] grid, int lado, Vecinos vecinos, boolean conOclusion,
                                    int bloquesPorVoxel) {
        return mallar(grid, lado, vecinos, conOclusion, bloquesPorVoxel, null);
    }

    /**
     * Qué dibuja cada cara de un estado de bloque (la textura de esa cara): dos
     * vóxeles de distinto estado con la misma cara se pueden fusionar (hojas a
     * distinta distancia del tronco, pasto con o sin nieve al costado...).
     * Antes se exigía el mismo estado: era lo que más cortaba las fusiones.
     */
    public interface Superficies {
        /** Clave de lo que se ve en esa cara; mismas claves = misma textura y mismo color base. */
        int clave(int idEstado, Quad.Eje eje, boolean positivo);
    }

    /**
     * @param superficies qué dibuja cada cara (null = solo color: sin texturas el estado no se ve)
     */
    public static List<Quad> mallar(SuperVoxel[] grid, int lado, Vecinos vecinos, boolean conOclusion,
                                    int bloquesPorVoxel, Superficies superficies) {
        return mallar(grid, lado, vecinos, conOclusion, false, bloquesPorVoxel, superficies);
    }

    /**
     * @param enCostados oclusión también en costados y caras de abajo, como vanilla (si no,
     *                   solo en las de arriba): más relieve en acantilados y bosques, pero corta
     *                   fusiones de caras (medido: +29% de vértices, +18% de GPU)
     */
    public static List<Quad> mallar(SuperVoxel[] grid, int lado, Vecinos vecinos, boolean conOclusion,
                                    boolean enCostados, int bloquesPorVoxel, Superficies superficies) {
        List<Quad> quads = new ArrayList<>();
        for (Quad.Eje eje : Quad.Eje.values()) {
            quads.addAll(mallarEje(grid, lado, eje, true, vecinos, conOclusion && (enCostados || eje == Quad.Eje.Y),
                    bloquesPorVoxel, superficies));
            quads.addAll(mallarEje(grid, lado, eje, false, vecinos, conOclusion && enCostados, bloquesPorVoxel,
                    superficies));
        }
        return quads;
    }

    /**
     * Altura de lo sólido dentro del vóxel en (x, y, z), en bloques: la de
     * su relleno si tiene aire (o, siendo sólido, agua) encima; si no, el
     * vóxel entero. Al menos 1 bloque: un vóxel visible nunca queda plano.
     */
    static int tope(SuperVoxel[] grid, int lado, Vecinos vecinos, Quad.Eje eje, int capa, int u, int w,
                    SuperVoxel v, int escala) {
        // (capa, u, w) a (x, y, z), misma convención que construirQuad.
        int x, y, z;
        switch (eje) {
            case X -> { x = capa; y = u; z = w; }
            case Y -> { x = u; y = capa; z = w; }
            default -> { x = u; y = w; z = capa; }
        }
        SuperVoxel arriba = voxel(grid, lado, vecinos, x, y + 1, z);
        boolean superficie = esAire(arriba) || (esAgua(arriba) && !esAgua(v));
        if (!superficie) {
            return escala;
        }
        return Math.max(1, Math.min(escala, Math.round(v.relleno() * escala / (float) SuperVoxel.LLENO)));
    }

    /** Vóxel de la grilla, o del vecino si UNA coordenada queda afuera por un paso; null si no se sabe. */
    private static SuperVoxel voxel(SuperVoxel[] grid, int lado, Vecinos vecinos, int x, int y, int z) {
        int fuera = (x < 0 || x >= lado ? 1 : 0) + (y < 0 || y >= lado ? 1 : 0) + (z < 0 || z >= lado ? 1 : 0);
        if (fuera == 0) {
            return grid[(x * lado + y) * lado + z];
        }
        return fuera == 1 && vecinos != null ? vecinos.en(x, y, z) : null;
    }

    /** Recorte empaquetado para la máscara: arriba en los 16 bits bajos, abajo en los altos. */
    private static int recorte(int arriba, int abajo) {
        return arriba | abajo << 16;
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
    private static boolean mismaSuperficie(SuperVoxel a, SuperVoxel b, Superficies superficies,
                                           Quad.Eje eje, boolean positivo) {
        if (a == null || b == null) return false;
        if (a.material() != b.material()) return false;
        if (a.estado() != b.estado() && superficies != null
                && superficies.clave(a.idEstado(), eje, positivo) != superficies.clave(b.idEstado(), eje, positivo)) {
            return false;
        }
        return a.r() == b.r() && a.g() == b.g() && a.b() == b.b();
    }

    private static List<Quad> mallarEje(SuperVoxel[] grid, int lado, Quad.Eje eje, boolean positivo,
                                        Vecinos vecinos, boolean conOclusion, int escala,
                                        Superficies superficies) {
        List<Quad> resultado = new ArrayList<>();
        // Superficie a la altura real: costados y cara de arriba (la de abajo queda en el piso del vóxel).
        boolean conAltura = escala > 1 && (eje != Quad.Eje.Y || positivo);
        // Una sola vez por eje (antes, tres matrices nuevas por capa): se limpian entre capas.
        SuperVoxel[][] mascara = new SuperVoxel[lado][lado];
        int[][] oclusion = new int[lado][lado];
        int[][] recortes = new int[lado][lado];
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
                    boolean visible = esAire(vecino) || bajoAgua;
                    int recorte = 0;
                    if (conAltura) {
                        int tope = tope(grid, lado, vecinos, eje, capa, u, v, actual, escala);
                        if (visible) {
                            recorte = recorte(escala - tope, 0);
                        } else if (eje != Quad.Eje.Y && esAgua(vecino) == esAgua(actual)) {
                            // Vecino del mismo tipo: si es de superficie y más bajo, queda a la vista
                            // el tramo entre las dos alturas.
                            int topeVecino = tope(grid, lado, vecinos, eje, capaVecina, u, v, vecino, escala);
                            if (topeVecino < tope) {
                                visible = true;
                                recorte = recorte(escala - tope, topeVecino);
                            }
                        }
                    }
                    if (visible) {
                        mascara[u][v] = eje == Quad.Eje.Y && positivo && actual.nevado()
                                ? superficieNevada(actual) : actual;
                        oclusion[u][v] = (conOclusion
                                ? oclusionCara(grid, lado, eje, capaVecina, u, v, vecinos) : Quad.SIN_OCLUSION)
                                | (bajoAgua ? Quad.BAJO_AGUA : 0);
                        recortes[u][v] = recorte;
                    }
                }
            }

            fusionarMascara(mascara, oclusion, recortes, visitado, lado, capa, eje, positivo, superficies, resultado);
        }

        return resultado;
    }

    /** Algoritmo greedy 2D estándar: barre la máscara y va extendiendo rectángulos lo más posible. */
    private static void fusionarMascara(SuperVoxel[][] mascara, int[][] oclusion, int[][] recortes,
                                        boolean[][] visitado, int lado, int capa, Quad.Eje eje, boolean positivo,
                                        Superficies superficies, List<Quad> quads) {

        for (int u = 0; u < lado; u++) {
            for (int v = 0; v < lado; v++) {
                if (visitado[u][v] || mascara[u][v] == null) continue;

                SuperVoxel referencia = mascara[u][v];
                int oclusionReferencia = oclusion[u][v];
                int recorteReferencia = recortes[u][v];
                // Un costado recortado ocupa un solo vóxel de alto (el eje Y es u en X, v en Z).
                boolean fijoEnU = recorteReferencia != 0 && eje == Quad.Eje.X;
                boolean fijoEnV = recorteReferencia != 0 && eje == Quad.Eje.Z;

                // Extender en la dirección "v" mientras siga siendo la misma superficie.
                int anchoV = 1;
                while (!fijoEnV && v + anchoV < lado
                        && !visitado[u][v + anchoV]
                        && mismaSuperficie(mascara[u][v + anchoV], referencia, superficies, eje, positivo)
                        && oclusion[u][v + anchoV] == oclusionReferencia
                        && recortes[u][v + anchoV] == recorteReferencia) {
                    anchoV++;
                }

                // Extender en la dirección "u" mientras toda la fila (de ancho anchoV) siga calzando.
                int anchoU = 1;
                filaSiguiente:
                while (!fijoEnU && u + anchoU < lado) {
                    for (int dv = 0; dv < anchoV; dv++) {
                        if (visitado[u + anchoU][v + dv]
                                || !mismaSuperficie(mascara[u + anchoU][v + dv], referencia, superficies, eje, positivo)
                                || oclusion[u + anchoU][v + dv] != oclusionReferencia
                                || recortes[u + anchoU][v + dv] != recorteReferencia) {
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

                quads.add(construirQuad(eje, positivo, capa, u, v, anchoU, anchoV, referencia, oclusionReferencia,
                        recorteReferencia & 0xFFFF, recorteReferencia >>> 16));
            }
        }
    }

    private static Quad construirQuad(Quad.Eje eje, boolean positivo, int capa, int u, int v,
                                       int anchoU, int anchoV, SuperVoxel referencia, int oclusion,
                                       int recorteArriba, int recorteAbajo) {
        // Mapear (capa, u, v) de vuelta a (x, y, z) según el eje — convención:
        // eje X -> capa=x, u=y, v=z | eje Y -> capa=y, u=x, v=z | eje Z -> capa=z, u=x, v=y
        return switch (eje) {
            case X -> new Quad(capa, u, v, anchoV, anchoU, eje, positivo, referencia, oclusion,
                    recorteArriba, recorteAbajo);
            case Y -> new Quad(u, capa, v, anchoU, anchoV, eje, positivo, referencia, oclusion,
                    recorteArriba, recorteAbajo);
            case Z -> new Quad(u, v, capa, anchoU, anchoV, eje, positivo, referencia, oclusion,
                    recorteArriba, recorteAbajo);
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
