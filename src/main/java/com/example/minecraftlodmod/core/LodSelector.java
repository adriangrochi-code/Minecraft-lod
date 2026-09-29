package com.example.minecraftlodmod.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Selector de nivel de detalle por "error de pantalla" (screen-space error),
 * ver sección 3 del documento de arquitectura.
 *
 * Es agnóstico a qué mod de zoom esté instalado: solo necesita el FOV
 * efectivo del frame actual (leído en el mod real vía el evento de NeoForge
 * ViewportEvent.ComputeFov, después de que cualquier otro mod ya lo haya
 * modificado). Acá se recibe simplemente como un double, para poder testear
 * la lógica sin depender del juego.
 */
public final class LodSelector {

    /** Umbral de error de pantalla en píxeles. Más chico = más detalle exigido. */
    private double umbralPx;

    public LodSelector(double umbralPxInicial) {
        this.umbralPx = umbralPxInicial;
    }

    public double umbralPx() {
        return umbralPx;
    }

    /** Usado por el auto-ajuste de rendimiento (sección 7) para subir/bajar detalle. */
    public void ajustarUmbral(double delta, double minimo) {
        this.umbralPx = Math.max(minimo, this.umbralPx + delta);
    }

    /**
     * Calcula el error de pantalla en píxeles que produciría representar un
     * nodo con su simplificación actual, dado el punto de vista.
     *
     * @param tamanoNodoMundo arista del cubo del nodo, en bloques
     * @param distanciaCamara distancia del centro del nodo a la cámara, en bloques
     * @param fovRadianes     FOV vertical efectivo actual, en radianes
     * @param alturaPantallaPx alto del viewport en píxeles
     */
    public static double errorDePantalla(double tamanoNodoMundo, double distanciaCamara,
                                          double fovRadianes, double alturaPantallaPx) {
        if (distanciaCamara <= 0.0001) {
            // Cámara prácticamente dentro del nodo: máximo detalle posible.
            return Double.MAX_VALUE;
        }
        double proyeccion = alturaPantallaPx / (2.0 * Math.tan(fovRadianes / 2.0));
        return (tamanoNodoMundo / distanciaCamara) * proyeccion;
    }

    /**
     * Recorre el octree a partir de la raíz y devuelve la lista de nodos que
     * deben renderizarse en el frame actual (las "hojas efectivas" según el
     * criterio de error de pantalla, no necesariamente hojas reales del árbol
     * en memoria).
     */
    public List<OctreeNode> seleccionarNodosVisibles(OctreeNode raiz, double camX, double camY, double camZ,
                                                      double fovRadianes, double alturaPantallaPx) {
        List<OctreeNode> resultado = new ArrayList<>();
        recorrer(raiz, camX, camY, camZ, fovRadianes, alturaPantallaPx, resultado);
        return resultado;
    }

    private void recorrer(OctreeNode nodo, double camX, double camY, double camZ,
                           double fovRadianes, double alturaPantallaPx, List<OctreeNode> resultado) {
        double dx = nodo.centroX() - camX;
        double dy = nodo.centroY() - camY;
        double dz = nodo.centroZ() - camZ;
        double distancia = Math.sqrt(dx * dx + dy * dy + dz * dz);

        double error = errorDePantalla(nodo.tamanoMundo(), distancia, fovRadianes, alturaPantallaPx);

        boolean tieneHijosCargados = !nodo.esHoja();

        if (error > umbralPx && tieneHijosCargados) {
            // El nodo actual es demasiado grueso para el detalle requerido,
            // y ya tenemos hijos generados: bajar un nivel.
            for (OctreeNode hijo : nodo.hijos()) {
                recorrer(hijo, camX, camY, camZ, fovRadianes, alturaPantallaPx, resultado);
            }
        } else {
            // O el error ya es aceptable, o no tenemos más detalle generado
            // todavía (generation/ lo generará bajo demanda) — se usa este
            // nodo tal cual para este frame.
            resultado.add(nodo);
        }
    }
}
