package com.example.minecraftlodmod.generation;

import com.example.minecraftlodmod.core.OctreeNode;
import com.example.minecraftlodmod.core.SuperVoxel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Extractor de datos de sección (hito 3 del documento de arquitectura):
 * primer paso del pipeline de la sección 4 —
 * vacía → descartar | homogénea → supervóxel único | mixta → seguir.
 *
 * Esta clase es la parte PURA (sin Minecraft, testeable): trabaja contra
 * {@link LectorSeccion}, que abstrae de dónde salen los bloques. La
 * implementación que lee el mundo real es {@link LectorSeccionMinecraft}.
 *
 * Requisitos de compatibilidad con generadores de mundo variados (mods de
 * worldgen, dimensiones custom, terreno modificado) — los cumple
 * {@link LectorSeccionMinecraft}:
 *
 * 1. RANGO DE ALTURA DINÁMICO — nunca asumir 384/-64/320 fijos: se recorren
 *    las secciones que tenga el chunk, con su índice real de sección Y.
 *
 * 2. COLOR DESDE MapColor, NO UNA PALETA HARDCODEADA — el mismo sistema del
 *    mapa in-game, funciona solo para bloques de cualquier mod.
 *
 * 3. MATERIAL SIMPLIFICADO desde tags de bloque y estado de fluido, no por
 *    ID de bloque uno por uno.
 *
 * 4. SECCIÓN VACÍA/HOMOGÉNEA — detectadas sin asumir qué bloque es (piedra
 *    vanilla o el "stone replacement" de un mod de worldgen).
 *
 * Convención de {@code alturaLocal} ({@link SuperVoxel#relleno()}): en el
 * nivel 0 todo bloque visible está lleno ({@link SuperVoxel#LLENO}); en los
 * niveles reducidos {@link HierarchicalReducer} calcula a qué altura llega
 * lo sólido dentro de cada vóxel.
 */
public final class SectionExtractor {

    /** Lado de una sección vanilla, en bloques (y en supervóxeles del nivel 0). */
    public static final int LADO = 16;
    /** Niveles que se derivan de una sección: lado 16, 8, 4, 2, 1. */
    public static final int NIVELES = 5;
    /** Lado de una región de disco, en secciones/chunks (igual que un .mca vanilla). */
    public static final int LADO_REGION = 32;

    private SectionExtractor() {
    }

    /** Fuente de bloques de una sección de 16³; coords locales 0-15. */
    public interface LectorSeccion {
        /** true si la sección no tiene ningún bloque visible (el caso más común en altura). */
        boolean soloAire();

        /** true si toda la sección es un único estado de bloque — idealmente sin recorrer los 4096. */
        boolean homogenea();

        /** Supervóxel de nivel 0 del bloque en (x, y, z), lleno si es visible. */
        SuperVoxel voxel(int x, int y, int z);
    }

    /**
     * Sección ya leída, en nivel 0.
     *
     * @param homogeneaEnOrigen la sección era un único estado de bloque; los
     *                          niveles que permiten colapso la guardan como
     *                          un solo supervóxel sin compararlos uno por uno
     */
    public record SeccionExtraida(int seccionX, int seccionY, int seccionZ,
                                  SuperVoxel[] voxeles, boolean homogeneaEnOrigen) {
    }

    /**
     * @return la sección en nivel 0, o null si está vacía (se descarta, ver sección 4)
     */
    public static SeccionExtraida extraer(LectorSeccion lector, int seccionX, int seccionY, int seccionZ) {
        if (lector.soloAire()) {
            return null;
        }
        SuperVoxel[] voxeles = new SuperVoxel[LADO * LADO * LADO];
        if (lector.homogenea()) {
            // Una sola lectura: arriba al centro, donde la luz horneada es
            // más representativa de lo que se ve desde afuera.
            SuperVoxel unico = lector.voxel(LADO / 2, LADO - 1, LADO / 2);
            if (unico.material() == SuperVoxel.Material.AIRE) {
                return null; // homogénea de un bloque invisible (ej. vidrio, MapColor.NONE)
            }
            for (int x = 0; x < LADO; x++) {
                for (int y = 0; y < LADO; y++) {
                    for (int z = 0; z < LADO; z++) {
                        voxeles[indice(x, y, z, LADO)] = unico.conRelleno(SuperVoxel.LLENO);
                    }
                }
            }
            return new SeccionExtraida(seccionX, seccionY, seccionZ, voxeles, true);
        }

        boolean todoAire = true;
        for (int x = 0; x < LADO; x++) {
            for (int y = 0; y < LADO; y++) {
                for (int z = 0; z < LADO; z++) {
                    SuperVoxel v = lector.voxel(x, y, z);
                    voxeles[indice(x, y, z, LADO)] = v;
                    todoAire &= v.material() == SuperVoxel.Material.AIRE;
                }
            }
        }
        // Una sección con solo bloques invisibles al mapa (vidrio, barreras)
        // no aporta nada al LOD aunque vanilla no la cuente como aire.
        return todoAire ? null : new SeccionExtraida(seccionX, seccionY, seccionZ, voxeles, false);
    }

    /**
     * Deriva los {@link #NIVELES} niveles de LOD de una sección, cada uno
     * desde el anterior (nunca desde el original, ver sección 2).
     *
     * El colapso por homogeneidad solo se aplica desde {@code colapsoDesdeNivel}
     * ({@code QualityPreset.colapsoHomogeneoDesdeNivel}): en los niveles más
     * cercanos la sección conserva su grilla aunque sea uniforme.
     *
     * @return lista indexada por nivel; todos los nodos cubren la sección entera (16 bloques)
     */
    public static List<OctreeNode> generarNiveles(SeccionExtraida seccion, int colapsoDesdeNivel) {
        int ox = seccion.seccionX() * LADO;
        int oy = seccion.seccionY() * LADO;
        int oz = seccion.seccionZ() * LADO;

        List<OctreeNode> niveles = new ArrayList<>(NIVELES);
        SuperVoxel[] actual = seccion.voxeles();
        int lado = LADO;
        for (int nivel = 0; nivel < NIVELES; nivel++) {
            if (nivel > 0) {
                actual = HierarchicalReducer.reducir(actual, lado);
                lado /= 2;
            }
            boolean colapsar = nivel >= colapsoDesdeNivel
                    && (seccion.homogeneaEnOrigen() || todosIguales(actual));
            if (colapsar) {
                SuperVoxel unico = actual[0];
                // Una sección llena de un solo bloque está llena hasta arriba.
                byte altura = seccion.homogeneaEnOrigen() ? (byte) SuperVoxel.LLENO : unico.alturaLocal();
                SuperVoxel marcado = new SuperVoxel(unico.r(), unico.g(), unico.b(), altura,
                        unico.material(), (byte) (unico.flags() | 0b0000_0001), unico.estado());
                niveles.add(OctreeNode.homogeneo(nivel, ox, oy, oz, LADO, marcado));
            } else {
                niveles.add(OctreeNode.mixto(nivel, ox, oy, oz, LADO, actual));
            }
        }
        return niveles;
    }

    /** Cantidad de supervóxeles de un nodo de este nivel (el {@code tamanoTotalNodo3D} de {@code OctreeNodeCodec}). */
    public static int voxelesPorNodo(int nivel) {
        int lado = LADO >> nivel;
        return lado * lado * lado;
    }

    /** Coordenada de región (X o Z) que contiene a esta sección/chunk. */
    public static int regionDe(int seccion) {
        return Math.floorDiv(seccion, LADO_REGION);
    }

    /**
     * Clave de nodo dentro de su región, para {@code RegionFileStore}:
     * nivel (4 bits) | sección Y con signo (12 bits) | X local (5) | Z local (5).
     * La sección Y admite -2048..2047, muy por encima de cualquier
     * dimension_type válido (±2032 bloques).
     */
    public static long claveNodo(int nivel, int seccionX, int seccionY, int seccionZ) {
        if (nivel < 0 || nivel >= 16) {
            throw new IllegalArgumentException("Nivel fuera de rango: " + nivel);
        }
        int localX = Math.floorMod(seccionX, LADO_REGION);
        int localZ = Math.floorMod(seccionZ, LADO_REGION);
        return ((long) nivel << 22) | ((long) (seccionY & 0xFFF) << 10) | ((long) localX << 5) | localZ;
    }

    private static boolean todosIguales(SuperVoxel[] voxeles) {
        SuperVoxel primero = voxeles[0];
        return Arrays.stream(voxeles).allMatch(primero::equals);
    }

    private static int indice(int x, int y, int z, int lado) {
        return (x * lado + y) * lado + z;
    }
}
