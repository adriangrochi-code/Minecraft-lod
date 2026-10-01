package com.example.minecraftlodmod.cubico;

/**
 * Franja vertical (en secciones) de una columna que se genera con el ruido
 * completo en la etapa 2 de los cubic chunks ({@link GeneracionVertical}).
 * Lógica pura.
 *
 * La franja cubre la superficie estimada de la columna con un margen, y la
 * altura de los jugadores cercanos con su distancia. Debajo queda relleno
 * (piedra) pendiente; arriba, si se recorta, aire pendiente.
 */
public final class VentanaVertical {

    private VentanaVertical() {
    }

    /**
     * @param superficieMin   menor altura de superficie estimada (bloques), o {@link Integer#MAX_VALUE} si no hay
     * @param superficieMax   mayor altura de superficie estimada (bloques)
     * @param alturasJugadores Y (bloques) de los jugadores cercanos
     * @param margenAbajo     secciones debajo de la superficie más baja que se generan completas
     * @param recortarArriba  si es false la franja llega siempre al techo del mundo
     * @param margenArriba    secciones arriba de la superficie más alta (si se recorta arriba)
     * @param distanciaJugador secciones arriba y abajo de cada jugador
     * @return la franja, o null si no conviene recortar nada (sin superficie, o la franja ya es el mundo entero)
     */
    public static RangoSecciones calcular(int superficieMin, int superficieMax, int[] alturasJugadores,
                                          int margenAbajo, boolean recortarArriba, int margenArriba,
                                          int distanciaJugador, int minSeccionMundo, int maxSeccionMundo) {
        if (superficieMin == Integer.MAX_VALUE) {
            return null; // columna sin terreno según la estimación: no se arriesga
        }
        int min = (superficieMin >> 4) - margenAbajo;
        int max = recortarArriba ? (superficieMax >> 4) + margenArriba : maxSeccionMundo;
        for (int y : alturasJugadores) {
            int s = y >> 4;
            min = Math.min(min, s - distanciaJugador);
            max = Math.max(max, s + distanciaJugador);
        }
        min = Math.max(min, minSeccionMundo);
        max = Math.min(max, maxSeccionMundo);
        if (min <= minSeccionMundo && max >= maxSeccionMundo) {
            return null;
        }
        if (min > max) {
            return null;
        }
        return new RangoSecciones(min, max);
    }
}
