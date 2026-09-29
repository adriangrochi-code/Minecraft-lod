package com.example.minecraftlodmod.render;

import com.example.minecraftlodmod.core.LodSelector;
import com.example.minecraftlodmod.generation.NivelesGrandes;
import com.example.minecraftlodmod.generation.PrioridadVista;
import com.example.minecraftlodmod.generation.SectionExtractor;

import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongPredicate;

/**
 * Qué dibujar (lógica pura): el terreno LOD se agrupa en celdas de
 * {@link #LADO_CELDA}×{@link #LADO_CELDA} chunks, un buffer de GPU por
 * celda (sección 6: agrupar, no un draw call por nodo). Por celda decide:
 *
 *  - nivel de LOD: el más detallado cuyo error en pantalla no supera el
 *    umbral ({@link LodSelector#errorDePantalla}, con el tamaño de un
 *    supervóxel del nivel como error). Reacciona solo al FOV: con zoom el
 *    error crece y la celda baja a un nivel más detallado (sección 3);
 *  - qué chunks omitir porque ya los dibuja vanilla (dentro de su
 *    distancia de render), como máscara de 16 bits.
 *
 * Primer render funcional: un nivel por celda, no por nodo; el selector
 * jerárquico sobre el octree completo queda para Pista B.
 */
public final class PlanCeldas {

    public static final int LADO_CELDA = 4;
    public static final int NIVEL_MAXIMO = SectionExtractor.NIVELES - 1;
    /**
     * Nivel más fino con teselas: 16³ vóxeles de 8 bloques = 128 bloques,
     * 2×2 celdas. Por debajo, celdas de 4×4 chunks (con máscara de vanilla).
     */
    public static final int NIVEL_TESELA_MIN = 3;

    /**
     * @param mascaraOmitidos bit (dx * LADO_CELDA + dz) = 1 si el chunk lo dibuja vanilla
     */
    public record Celda(int celdaX, int celdaZ, int nivel, int mascaraOmitidos, boolean tesela) {

        public Celda(int celdaX, int celdaZ, int nivel, int mascaraOmitidos) {
            this(celdaX, celdaZ, nivel, mascaraOmitidos, false);
        }

        /**
         * true = TESELA del quadtree: una grilla de 16³ vóxeles de 2^nivel
         * bloques que cubre {@link #ladoEnBloques()} (celdaX/Z en unidades de
         * ese lado). Desde el nivel 5 sale de {@link NivelesGrandes}; en los
         * niveles 3 y 4 se arma con los datos por sección. Nunca omite nada:
         * solo se usa lejos de lo que dibuja vanilla.
         */
        public boolean esGrande() {
            return tesela;
        }

        public int ladoEnBloques() {
            return esGrande() ? NivelesGrandes.ladoEnBloques(nivel) : LADO_CELDA * 16;
        }

        public int origenX() {
            return celdaX * ladoEnBloques();
        }

        public int origenZ() {
            return celdaZ * ladoEnBloques();
        }

        public boolean omitido(int dx, int dz) {
            return (mascaraOmitidos & (1 << (dx * LADO_CELDA + dz))) != 0;
        }

        public boolean todoOmitido() {
            return mascaraOmitidos == (1 << (LADO_CELDA * LADO_CELDA)) - 1;
        }
    }

    private PlanCeldas() {
    }

    /** Clave de chunk, mismo empaquetado que {@code ChunkPos.asLong}. */
    public static long claveChunk(int chunkX, int chunkZ) {
        return (chunkX & 0xFFFFFFFFL) | ((long) chunkZ << 32);
    }

    /**
     * Hacia dónde mira el jugador, para que el zoom (spyglass, mods de zoom:
     * FOV efectivo más chico) pida detalle SOLO en lo que se ve. Sin esto el
     * zoom le pedía detalle fino a todo el radio, también a lo de atrás, y
     * cada zoom rearmaba todo el mapa.
     *
     * @param miraX, miraZ   dirección horizontal de la mirada
     * @param fovNormal      FOV vertical sin zoom (el de las opciones), radianes
     * @param mediaApertura  media apertura horizontal del cono con zoom, radianes
     *                       (con margen); fuera de él se usa {@code fovNormal}
     */
    public record Vista(double miraX, double miraZ, double fovNormal, double mediaApertura) {
    }

    /** FOV para el nivel de una pieza: el efectivo si la pieza toca el cono de la vista, si no el normal. */
    static double fovPara(Vista vista, double fovEfectivo, double camX, double camZ,
                          double minX, double minZ, double lado) {
        if (vista == null || fovEfectivo >= vista.fovNormal()) {
            return fovEfectivo;
        }
        double[][] puntos = {{minX + lado / 2, minZ + lado / 2}, {minX, minZ}, {minX + lado, minZ},
                {minX, minZ + lado}, {minX + lado, minZ + lado}};
        for (double[] p : puntos) {
            if (PrioridadVista.dentro(p[0] - camX, p[1] - camZ, vista.miraX(), vista.miraZ(), vista.mediaApertura())) {
                return fovEfectivo;
            }
        }
        if (camX >= minX && camX <= minX + lado && camZ >= minZ && camZ <= minZ + lado) {
            return fovEfectivo;
        }
        return vista.fovNormal();
    }

    /** Vanilla lista en todo su radio (tests y planes sin mundo). */
    private static final LongPredicate VANILLA_SIEMPRE = clave -> true;

    /**
     * @param camX/camZ          cámara, en bloques
     * @param radioLodChunks     radio de LOD del preset
     * @param distanciaVanilla   radio en chunks que ya dibuja vanilla (chunks más
     *                           cercanos que esto se omiten)
     * @param fovRadianes        FOV vertical efectivo (después de mods de zoom)
     * @param alturaPantallaPx   alto del viewport
     * @param umbralPx           error de pantalla tolerado
     */
    public static List<Celda> planificar(double camX, double camZ, int radioLodChunks, int distanciaVanilla,
                                         double fovRadianes, double alturaPantallaPx, double umbralPx) {
        List<Celda> plan = new ArrayList<>();
        agregarCeldas(plan, camX, camZ, radioLodChunks, distanciaVanilla, VANILLA_SIEMPRE, null, fovRadianes,
                alturaPantallaPx, umbralPx, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE);
        return plan;
    }

    /**
     * Plan con niveles grandes (quadtree, como el selector de la sección 3):
     * teselas de nivel {@link NivelesGrandes#NIVEL_MAX} que cubren el radio,
     * subdivididas mientras el nivel que pide el error en pantalla (medido en
     * el punto de la tesela MÁS CERCANO a la cámara) sea más fino que el de
     * la tesela. Una tesela de nivel 5 que todavía pide más detalle se
     * reemplaza por las celdas de siempre (niveles 0-4) que caen en ella.
     * El resultado: lejos, pocas piezas enormes; cerca, celdas finas.
     */
    public static List<Celda> planificarConGrandes(double camX, double camZ, int radioLodChunks, int distanciaVanilla,
                                                   double fovRadianes, double alturaPantallaPx, double umbralPx) {
        return planificarConGrandes(camX, camZ, radioLodChunks, distanciaVanilla, VANILLA_SIEMPRE, null, fovRadianes,
                alturaPantallaPx, umbralPx);
    }

    /**
     * @param vanillaListo clave de chunk ({@link #claveChunk}) → true si vanilla ya
     *                     lo tiene cargado. Dentro de la distancia vanilla, un chunk
     *                     que vanilla TODAVÍA no tiene lo sigue dibujando el LOD
     *                     (vanilla lo tapa al llegar): así no quedan huecos al
     *                     moverse rápido o teletransportarse. Idea del "vanilla
     *                     renderability" de FarPlaneTwo (sección 25, punto 6).
     */
    public static List<Celda> planificarConGrandes(double camX, double camZ, int radioLodChunks, int distanciaVanilla,
                                                   LongPredicate vanillaListo, Vista vista, double fovRadianes,
                                                   double alturaPantallaPx, double umbralPx) {
        List<Celda> plan = new ArrayList<>();
        int nivel = NivelesGrandes.NIVEL_MAX;
        int lado = NivelesGrandes.ladoEnBloques(nivel);
        double radio = radioLodChunks * 16.0;
        int desdeX = (int) Math.floor((camX - radio) / lado), hastaX = (int) Math.floor((camX + radio) / lado);
        int desdeZ = (int) Math.floor((camZ - radio) / lado), hastaZ = (int) Math.floor((camZ + radio) / lado);
        for (int tx = desdeX; tx <= hastaX; tx++) {
            for (int tz = desdeZ; tz <= hastaZ; tz++) {
                subdividir(plan, nivel, tx, tz, camX, camZ, radioLodChunks, distanciaVanilla, vanillaListo, vista,
                        fovRadianes, alturaPantallaPx, umbralPx);
            }
        }
        return plan;
    }

    private static void subdividir(List<Celda> plan, int nivel, int tx, int tz, double camX, double camZ,
                                   int radioLodChunks, int distanciaVanilla, LongPredicate vanillaListo,
                                   Vista vista, double fov, double alto, double umbral) {
        int lado = NivelesGrandes.ladoEnBloques(nivel);
        double minX = (double) tx * lado, minZ = (double) tz * lado;
        double dx = Math.max(0, Math.max(minX - camX, camX - (minX + lado)));
        double dz = Math.max(0, Math.max(minZ - camZ, camZ - (minZ + lado)));
        double masCerca = Math.hypot(dx, dz);
        if (masCerca > radioLodChunks * 16.0) {
            return;
        }
        boolean lejosDeVanilla = masCerca > distanciaVanilla * 16.0;
        double fovTesela = fovPara(vista, fov, camX, camZ, minX, minZ, lado);
        if (lejosDeVanilla && nivelPara(masCerca, fovTesela, alto, umbral, NivelesGrandes.NIVEL_MAX) >= nivel) {
            plan.add(new Celda(tx, tz, nivel, 0, true));
        } else if (nivel > NIVEL_TESELA_MIN) {
            for (int hx = 0; hx < 2; hx++) {
                for (int hz = 0; hz < 2; hz++) {
                    subdividir(plan, nivel - 1, tx * 2 + hx, tz * 2 + hz, camX, camZ, radioLodChunks,
                            distanciaVanilla, vanillaListo, vista, fov, alto, umbral);
                }
            }
        } else {
            int celdasPorLado = lado / (LADO_CELDA * 16);
            agregarCeldas(plan, camX, camZ, radioLodChunks, distanciaVanilla, vanillaListo, vista, fov, alto, umbral,
                    tx * celdasPorLado, (tx + 1) * celdasPorLado - 1, tz * celdasPorLado, (tz + 1) * celdasPorLado - 1);
        }
    }

    /** Celdas de nivel 0-4 dentro del radio y del rango de celdas dado (inclusive). */
    private static void agregarCeldas(List<Celda> plan, double camX, double camZ, int radioLodChunks,
                                      int distanciaVanilla, LongPredicate vanillaListo, Vista vista,
                                      double fovRadianes, double alturaPantallaPx,
                                      double umbralPx, int minCeldaX, int maxCeldaX, int minCeldaZ, int maxCeldaZ) {
        int chunkCamX = (int) Math.floor(camX / 16);
        int chunkCamZ = (int) Math.floor(camZ / 16);
        int radioCeldas = Math.floorDiv(radioLodChunks, LADO_CELDA) + 1;
        int celdaCamX = Math.floorDiv(chunkCamX, LADO_CELDA);
        int celdaCamZ = Math.floorDiv(chunkCamZ, LADO_CELDA);
        long radio2 = (long) radioLodChunks * radioLodChunks;
        long vanilla2 = (long) distanciaVanilla * distanciaVanilla;

        for (int cx = Math.max(minCeldaX, celdaCamX - radioCeldas); cx <= Math.min(maxCeldaX, celdaCamX + radioCeldas); cx++) {
            for (int cz = Math.max(minCeldaZ, celdaCamZ - radioCeldas); cz <= Math.min(maxCeldaZ, celdaCamZ + radioCeldas); cz++) {
                int mascara = 0;
                boolean algunoEnRadio = false;
                for (int dx = 0; dx < LADO_CELDA; dx++) {
                    for (int dz = 0; dz < LADO_CELDA; dz++) {
                        long ddx = (long) cx * LADO_CELDA + dx - chunkCamX;
                        long ddz = (long) cz * LADO_CELDA + dz - chunkCamZ;
                        long d2 = ddx * ddx + ddz * ddz;
                        boolean deVanilla = d2 < vanilla2 && vanillaListo.test(
                                claveChunk(cx * LADO_CELDA + dx, cz * LADO_CELDA + dz));
                        if (d2 > radio2 || deVanilla) {
                            mascara |= 1 << (dx * LADO_CELDA + dz);
                        } else {
                            algunoEnRadio = true;
                        }
                    }
                }
                if (!algunoEnRadio) {
                    continue;
                }
                double centroX = (cx * LADO_CELDA + LADO_CELDA / 2.0) * 16;
                double centroZ = (cz * LADO_CELDA + LADO_CELDA / 2.0) * 16;
                double distancia = Math.hypot(centroX - camX, centroZ - camZ);
                double fovCelda = fovPara(vista, fovRadianes, camX, camZ, cx * LADO_CELDA * 16.0,
                        cz * LADO_CELDA * 16.0, LADO_CELDA * 16.0);
                plan.add(new Celda(cx, cz, nivelPara(distancia, fovCelda, alturaPantallaPx, umbralPx), mascara));
            }
        }
    }

    /**
     * Nivel más grueso (más barato) cuyo supervóxel (2^nivel bloques) no
     * supera el umbral a esa distancia; si ni el nivel 0 cumple, el 0.
     */
    public static int nivelPara(double distancia, double fovRadianes, double alturaPantallaPx, double umbralPx) {
        return nivelPara(distancia, fovRadianes, alturaPantallaPx, umbralPx, NIVEL_MAXIMO);
    }

    /** Como {@link #nivelPara(double, double, double, double)}, hasta {@code nivelMaximo}. */
    public static int nivelPara(double distancia, double fovRadianes, double alturaPantallaPx, double umbralPx,
                                int nivelMaximo) {
        for (int nivel = nivelMaximo; nivel > 0; nivel--) {
            double tamanoVoxel = 1 << nivel;
            if (LodSelector.errorDePantalla(tamanoVoxel, distancia, fovRadianes, alturaPantallaPx) <= umbralPx) {
                return nivel;
            }
        }
        return 0;
    }

    /**
     * La proyección del frame de vanilla con otros planos de profundidad.
     *
     * Vanilla multiplica su perspectiva por el balanceo de cámara al caminar
     * (y el de daño, náusea, zoom de otros mods): P × B. Si el LOD armara su
     * propia perspectiva, el terreno vanilla se balancearía y el LOD no, y
     * parecería que el LOD sube y baja con cada paso. Como B es afín (fila
     * de abajo 0,0,0,1) y en una perspectiva la fila 3 es (0,0,-1,0), la
     * fila 3 de P × B es -(fila 2 de B): alcanza con reescribir la fila 2
     * del producto con los coeficientes de profundidad nuevos, sin conocer B.
     *
     * @param proyeccion proyección de vanilla (se modifica y se devuelve)
     */
    public static Matrix4f conPlanosDeProfundidad(Matrix4f proyeccion, float near, float far) {
        float a = -(far + near) / (far - near);
        float b = -2f * far * near / (far - near);
        Matrix4f m = proyeccion;
        return m.m02(-a * m.m03()).m12(-a * m.m13()).m22(-a * m.m23()).m32(-a * m.m33() + b);
    }
}
