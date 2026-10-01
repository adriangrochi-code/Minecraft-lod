package com.example.minecraftlodmod.tierra;

/**
 * Elevación (metros) y clase de bioma en (lat, lon) desde un {@link LectorLodt}.
 * La elevación se interpola con bicúbica Catmull-Rom sobre 4×4 muestras: pasa
 * por las muestras (los puntos conocidos dan el valor de la grilla) y no deja
 * escalones entre ellas. Lógica pura + la caché del lector.
 *
 * Bordes: si la grilla es global la longitud se envuelve; si no (recortes de
 * prueba), se repite el borde. La latitud siempre repite el borde (más allá de
 * los polos, el valor del polo; las farlands son de {@code 05-borde.md}, H7).
 *
 * Camino rápido: si las 16 muestras caen en una tesela (casi siempre: con
 * teselas de 256 solo las 3 filas/columnas de cada borde salen de la tesela),
 * se busca la tesela una sola vez.
 */
public final class FuenteTierra {

    private final LectorLodt lector;
    private final FormatoLodt.Cabecera cab;
    private final boolean global;

    public FuenteTierra(LectorLodt lector) {
        this.lector = lector;
        this.cab = lector.cabecera;
        this.global = cab.global();
    }

    public FormatoLodt.Cabecera cabecera() {
        return cab;
    }

    /** Elevación interpolada en metros sobre el nivel del mar (EGM2008). */
    public double elevacion(double latitud, double longitud) {
        double u = columnaReal(longitud);
        double v = (cab.latOrigen() - latitud) / cab.paso();
        int i = (int) Math.floor(u), j = (int) Math.floor(v);
        double tu = u - i, tv = v - j;
        double u0 = pesoA(tu), u1 = pesoB(tu), u2 = pesoC(tu), u3 = pesoD(tu);
        double v0 = pesoA(tv), v1 = pesoB(tv), v2 = pesoC(tv), v3 = pesoD(tv);

        int lado = cab.lado();
        int tx = Math.floorDiv(i - 1, lado), tz = Math.floorDiv(j - 1, lado);
        if (i - 1 >= 0 && j - 1 >= 0 && i + 2 < cab.ancho() && j + 2 < cab.alto()
                && (i + 2) / lado == tx && (j + 2) / lado == tz) {
            short[] e = lector.tesela(tx, tz).elevacion;
            int base = (j - 1 - tz * lado) * lado + (i - 1 - tx * lado);
            return v0 * fila(e, base, u0, u1, u2, u3)
                    + v1 * fila(e, base + lado, u0, u1, u2, u3)
                    + v2 * fila(e, base + 2 * lado, u0, u1, u2, u3)
                    + v3 * fila(e, base + 3 * lado, u0, u1, u2, u3);
        }
        double suma = 0;
        for (int dj = 0; dj < 4; dj++) {
            int fila = Math.clamp(j - 1 + dj, 0, cab.alto() - 1);
            double pv = dj == 0 ? v0 : dj == 1 ? v1 : dj == 2 ? v2 : v3;
            double s = u0 * lector.elevacion(columna(i - 1), fila)
                    + u1 * lector.elevacion(columna(i), fila)
                    + u2 * lector.elevacion(columna(i + 1), fila)
                    + u3 * lector.elevacion(columna(i + 2), fila);
            suma += pv * s;
        }
        return suma;
    }

    /** Clase de bioma de la muestra más cercana (0 = sin clasificar, hasta H4). */
    public int claseBioma(double latitud, double longitud) {
        int i = (int) Math.round(columnaReal(longitud));
        int j = Math.clamp(Math.round((cab.latOrigen() - latitud) / cab.paso()), 0, cab.alto() - 1);
        return lector.bioma(columna(i), j) & 0xFF;
    }

    private double columnaReal(double longitud) {
        double u = (longitud - cab.lonOrigen()) / cab.paso();
        if (global) {
            u %= cab.ancho();
            if (u < 0) u += cab.ancho();
        }
        return u;
    }

    private int columna(int i) {
        if (global) return Math.floorMod(i, cab.ancho());
        return Math.clamp(i, 0, cab.ancho() - 1);
    }

    private static double fila(short[] e, int base, double u0, double u1, double u2, double u3) {
        return u0 * e[base] + u1 * e[base + 1] + u2 * e[base + 2] + u3 * e[base + 3];
    }

    // Pesos de Catmull-Rom para t en [0, 1); suman 1.
    static double pesoA(double t) {
        return ((-t + 2) * t - 1) * t * 0.5;
    }

    static double pesoB(double t) {
        return ((3 * t - 5) * t * t + 2) * 0.5;
    }

    static double pesoC(double t) {
        return ((-3 * t + 4) * t + 1) * t * 0.5;
    }

    static double pesoD(double t) {
        return (t - 1) * t * t * 0.5;
    }
}
