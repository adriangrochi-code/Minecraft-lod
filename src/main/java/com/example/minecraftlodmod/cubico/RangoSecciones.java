package com.example.minecraftlodmod.cubico;

/**
 * Secciones (en Y de sección, inclusive) de una columna que el cliente tiene
 * con sus bloques reales; las de afuera las tiene como aire. Lógica pura.
 */
public record RangoSecciones(int min, int max) {

    public boolean contiene(int seccionY) {
        return seccionY >= min && seccionY <= max;
    }

    public long empaquetado() {
        return (long) min << 32 | (max & 0xFFFFFFFFL);
    }

    public static RangoSecciones desempaquetar(long v) {
        return new RangoSecciones((int) (v >> 32), (int) v);
    }

    /**
     * Rango que se quiere alrededor de {@code centro} (la sección del jugador),
     * dentro de la altura del mundo {@code [minMundo, maxMundo]}.
     */
    public static RangoSecciones alrededor(int centro, int distancia, int minMundo, int maxMundo) {
        int min = Math.max(minMundo, centro - distancia);
        int max = Math.min(maxMundo, centro + distancia);
        return min <= max ? new RangoSecciones(min, max) : new RangoSecciones(minMundo, minMundo);
    }

    /**
     * Rango nuevo al moverse, con histéresis de una sección: lo cargado que quedó
     * apenas una sección afuera de {@code objetivo} se conserva (subir y bajar sobre
     * el borde de una sección no carga y descarga la misma fila una y otra vez).
     * Si no se tocan (teletransporte), es directamente el objetivo.
     */
    public static RangoSecciones mover(RangoSecciones actual, RangoSecciones objetivo) {
        if (actual.max < objetivo.min - 1 || actual.min > objetivo.max + 1) {
            return objetivo;
        }
        int min = actual.min >= objetivo.min - 1 ? Math.min(actual.min, objetivo.min) : objetivo.min;
        int max = actual.max <= objetivo.max + 1 ? Math.max(actual.max, objetivo.max) : objetivo.max;
        return new RangoSecciones(min, max);
    }
}
