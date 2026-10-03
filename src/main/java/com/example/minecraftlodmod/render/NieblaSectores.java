package com.example.minecraftlodmod.render;

import java.util.Arrays;

/**
 * Niebla en las zonas sin datos (parte pura): la vista alrededor de la
 * cámara se divide en {@link #SECTORES} porciones y en cada una la niebla
 * termina donde empieza la primera zona del plan que todavía no tiene datos
 * (sin cargar, sin generar o armándose), o donde termina lo dibujado. Así no
 * se ven bordes rectos ni huecos del LOD: se pierden en la niebla, como en
 * vanilla con los chunks que todavía no llegaron. Lo usa {@link AcabadoLod}.
 */
public final class NieblaSectores {

    public static final int SECTORES = 64;

    private final float[] dibujado = new float[SECTORES];
    private final float[] faltante = new float[SECTORES];

    /** Empieza un recuento nuevo (una vez por cuadro). */
    public void reiniciar() {
        Arrays.fill(dibujado, 0f);
        Arrays.fill(faltante, Float.POSITIVE_INFINITY);
    }

    /** Sector (0 a {@link #SECTORES}-1) de una dirección horizontal desde la cámara. */
    public static int sector(double dx, double dz) {
        double angulo = Math.atan2(dz, dx); // -π..π
        int s = (int) Math.floor((angulo + Math.PI) / (2 * Math.PI) * SECTORES);
        return Math.floorMod(s, SECTORES);
    }

    /** Una celda dibujada con centro en (dx, dz) relativo a la cámara y radio {@code mitadDiagonal}. */
    public void dibujada(double dx, double dz, double mitadDiagonal) {
        double d = Math.sqrt(dx * dx + dz * dz);
        float lejos = (float) (d + mitadDiagonal);
        int rango = rango(dx, dz, d, mitadDiagonal);
        for (int i = 0, desde = rango >>> 8, n = rango & 0xFF; i < n; i++) {
            int s = (desde + i) % SECTORES;
            dibujado[s] = Math.max(dibujado[s], lejos);
        }
    }

    /** Una celda del plan sin datos (todavía): la niebla del sector termina antes de ella. */
    public void faltante(double dx, double dz, double mitadDiagonal) {
        double d = Math.sqrt(dx * dx + dz * dz);
        float cerca = (float) Math.max(0, d - mitadDiagonal);
        int rango = rango(dx, dz, d, mitadDiagonal);
        for (int i = 0, desde = rango >>> 8, n = rango & 0xFF; i < n; i++) {
            int s = (desde + i) % SECTORES;
            faltante[s] = Math.min(faltante[s], cerca);
        }
    }

    /**
     * Sectores que abarca una celda vista desde la cámara (cerca, varios; lejos, uno),
     * como {@code primero << 8 | cantidad}. Se llama por cada celda en cada recuento: sin
     * arreglos nuevos (antes, uno por celda) ni {@code hypot}.
     */
    static int rango(double dx, double dz, double d, double mitadDiagonal) {
        if (d <= mitadDiagonal) {
            return SECTORES;
        }
        double ancho = Math.asin(Math.min(1, mitadDiagonal / d)) / (2 * Math.PI) * SECTORES;
        double centro = (Math.atan2(dz, dx) + Math.PI) / (2 * Math.PI) * SECTORES;
        int desde = (int) Math.floor(centro - ancho), hasta = (int) Math.floor(centro + ancho);
        return Math.floorMod(desde, SECTORES) << 8 | Math.min(SECTORES, hasta - desde + 1);
    }

    /**
     * Alcance de la niebla por sector, en bloques: el menor entre lo dibujado
     * y la primera zona sin datos, nunca más cerca que {@code minimo} (el
     * terreno de vanilla tiene su propia niebla).
     */
    public float[] alcances(float minimo) {
        float[] a = new float[SECTORES];
        for (int s = 0; s < SECTORES; s++) {
            a[s] = Math.max(minimo, Math.min(dibujado[s], faltante[s]));
        }
        return a;
    }
}
