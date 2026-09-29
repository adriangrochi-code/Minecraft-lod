package com.example.minecraftlodmod.render;

import com.example.minecraftlodmod.core.LodSelector;
import com.example.minecraftlodmod.generation.SectionExtractor;

import java.util.ArrayList;
import java.util.List;

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
     * @param mascaraOmitidos bit (dx * LADO_CELDA + dz) = 1 si el chunk lo dibuja vanilla
     */
    public record Celda(int celdaX, int celdaZ, int nivel, int mascaraOmitidos) {
        public boolean omitido(int dx, int dz) {
            return (mascaraOmitidos & (1 << (dx * LADO_CELDA + dz))) != 0;
        }

        public boolean todoOmitido() {
            return mascaraOmitidos == (1 << (LADO_CELDA * LADO_CELDA)) - 1;
        }
    }

    private PlanCeldas() {
    }

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
        int chunkCamX = (int) Math.floor(camX / 16);
        int chunkCamZ = (int) Math.floor(camZ / 16);
        int radioCeldas = Math.floorDiv(radioLodChunks, LADO_CELDA) + 1;
        int celdaCamX = Math.floorDiv(chunkCamX, LADO_CELDA);
        int celdaCamZ = Math.floorDiv(chunkCamZ, LADO_CELDA);
        long radio2 = (long) radioLodChunks * radioLodChunks;
        long vanilla2 = (long) distanciaVanilla * distanciaVanilla;

        for (int cx = celdaCamX - radioCeldas; cx <= celdaCamX + radioCeldas; cx++) {
            for (int cz = celdaCamZ - radioCeldas; cz <= celdaCamZ + radioCeldas; cz++) {
                int mascara = 0;
                boolean algunoEnRadio = false;
                for (int dx = 0; dx < LADO_CELDA; dx++) {
                    for (int dz = 0; dz < LADO_CELDA; dz++) {
                        long ddx = (long) cx * LADO_CELDA + dx - chunkCamX;
                        long ddz = (long) cz * LADO_CELDA + dz - chunkCamZ;
                        long d2 = ddx * ddx + ddz * ddz;
                        if (d2 > radio2 || d2 < vanilla2) {
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
                plan.add(new Celda(cx, cz, nivelPara(distancia, fovRadianes, alturaPantallaPx, umbralPx), mascara));
            }
        }
        return plan;
    }

    /**
     * Nivel más grueso (más barato) cuyo supervóxel (2^nivel bloques) no
     * supera el umbral a esa distancia; si ni el nivel 0 cumple, el 0.
     */
    public static int nivelPara(double distancia, double fovRadianes, double alturaPantallaPx, double umbralPx) {
        for (int nivel = NIVEL_MAXIMO; nivel > 0; nivel--) {
            double tamanoVoxel = 1 << nivel;
            if (LodSelector.errorDePantalla(tamanoVoxel, distancia, fovRadianes, alturaPantallaPx) <= umbralPx) {
                return nivel;
            }
        }
        return 0;
    }
}
