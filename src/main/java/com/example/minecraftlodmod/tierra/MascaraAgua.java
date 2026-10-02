package com.example.minecraftlodmod.tierra;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Máscara de agua desde polígonos de Natural Earth (dominio público:
 * {@code ne_10m_lakes.shp} y {@code ne_10m_ocean.shp}, este último trae el
 * Caspio), rasterizada sobre la grilla de los datos: una muestra es agua si su
 * centro cae dentro de algún polígono (regla par-impar: los anillos interiores,
 * islas dentro de lagos, quedan fuera).
 *
 * El mapa de Köppen da clima también sobre los lagos (el Caspio, el Superior,
 * el Baikal), así que solo con él los lagos quedaban como pozos secos.
 *
 * Lector mínimo de shapefile: solo polígonos (tipo 5) en lon/lat WGS84.
 */
public final class MascaraAgua {

    private MascaraAgua() {}

    /** Un polígono: anillos de puntos (lon, lat) intercalados. */
    record Poligono(List<double[]> anillos) {
    }

    static List<Poligono> leerShapefile(Path shp) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(shp));
        if (b.order(ByteOrder.BIG_ENDIAN).getInt(0) != 9994) throw new IOException("No es un shapefile: " + shp);
        int tipo = b.order(ByteOrder.LITTLE_ENDIAN).getInt(32);
        if (tipo != 5) throw new IOException("Shapefile de tipo " + tipo + " (se esperaban polígonos): " + shp);
        List<Poligono> poligonos = new ArrayList<>();
        int pos = 100;
        while (pos + 8 <= b.limit()) {
            int largo = b.order(ByteOrder.BIG_ENDIAN).getInt(pos + 4) * 2;
            int c = pos + 8;
            b.order(ByteOrder.LITTLE_ENDIAN);
            if (b.getInt(c) == 5) {
                int partes = b.getInt(c + 36), puntos = b.getInt(c + 40);
                int inicioPartes = c + 44, inicioPuntos = inicioPartes + 4 * partes;
                List<double[]> anillos = new ArrayList<>(partes);
                for (int p = 0; p < partes; p++) {
                    int desde = b.getInt(inicioPartes + 4 * p);
                    int hasta = p + 1 < partes ? b.getInt(inicioPartes + 4 * (p + 1)) : puntos;
                    double[] anillo = new double[(hasta - desde) * 2];
                    for (int k = desde; k < hasta; k++) {
                        anillo[(k - desde) * 2] = b.getDouble(inicioPuntos + 16 * k);
                        anillo[(k - desde) * 2 + 1] = b.getDouble(inicioPuntos + 16 * k + 8);
                    }
                    anillos.add(anillo);
                }
                poligonos.add(new Poligono(anillos));
            }
            pos += 8 + largo;
        }
        return poligonos;
    }

    /**
     * Marca en {@code mascara} (un bit por muestra, fila por fila) las muestras
     * cuyo centro cae dentro de los polígonos.
     *
     * @return cuántas muestras quedaron marcadas
     */
    static long rasterizar(List<Poligono> poligonos, FormatoLodt.Cabecera cab, long[] mascara) {
        long marcadas = 0;
        int ancho = cab.ancho(), alto = cab.alto();
        double paso = cab.paso();
        for (Poligono p : poligonos) {
            // Aristas del polígono (todas las partes juntas: par-impar)
            int n = 0;
            for (double[] a : p.anillos()) n += a.length / 2;
            double[] x0 = new double[n], y0 = new double[n], x1 = new double[n], y1 = new double[n];
            int e = 0;
            double latMin = Double.MAX_VALUE, latMax = -Double.MAX_VALUE;
            for (double[] a : p.anillos()) {
                int k = a.length / 2;
                for (int i = 0; i < k; i++) {
                    int j = (i + 1) % k;
                    x0[e] = a[2 * i];
                    y0[e] = a[2 * i + 1];
                    x1[e] = a[2 * j];
                    y1[e] = a[2 * j + 1];
                    latMin = Math.min(latMin, y0[e]);
                    latMax = Math.max(latMax, y0[e]);
                    e++;
                }
            }
            // Filas cuyo centro cae en el rango del polígono
            int filaDesde = Math.max(0, (int) Math.ceil((cab.latOrigen() - latMax) / paso));
            int filaHasta = Math.min(alto - 1, (int) Math.floor((cab.latOrigen() - latMin) / paso));
            if (filaDesde > filaHasta) continue;
            int filas = filaHasta - filaDesde + 1;
            // Índice de aristas por fila (orden por conteo): cada arista en las filas que cruza
            int[] cuenta = new int[filas + 1];
            int[] rango = new int[2 * e];
            for (int i = 0; i < e; i++) {
                double ya = Math.min(y0[i], y1[i]), yb = Math.max(y0[i], y1[i]);
                int fa = Math.max(filaDesde, (int) Math.ceil((cab.latOrigen() - yb) / paso));
                int fb = Math.min(filaHasta, (int) Math.floor((cab.latOrigen() - ya) / paso));
                rango[2 * i] = fa;
                rango[2 * i + 1] = fb;
                for (int f = fa; f <= fb; f++) cuenta[f - filaDesde + 1]++;
            }
            for (int f = 0; f < filas; f++) cuenta[f + 1] += cuenta[f];
            int[] aristas = new int[cuenta[filas]];
            int[] lleno = java.util.Arrays.copyOf(cuenta, filas);
            for (int i = 0; i < e; i++) {
                for (int f = rango[2 * i]; f <= rango[2 * i + 1]; f++) aristas[lleno[f - filaDesde]++] = i;
            }
            double[] cruces = new double[16];
            for (int f = 0; f < filas; f++) {
                double lat = cab.latOrigen() - (filaDesde + f) * paso;
                int nc = 0;
                for (int k = cuenta[f]; k < cuenta[f + 1]; k++) {
                    int i = aristas[k];
                    // Regla del semiplano: cuenta la arista si la fila está en [min, max)
                    if ((y0[i] <= lat) != (y1[i] <= lat)) {
                        if (nc == cruces.length) cruces = java.util.Arrays.copyOf(cruces, nc * 2);
                        cruces[nc++] = x0[i] + (lat - y0[i]) * (x1[i] - x0[i]) / (y1[i] - y0[i]);
                    }
                }
                java.util.Arrays.sort(cruces, 0, nc);
                long base = (long) (filaDesde + f) * ancho;
                for (int k = 0; k + 1 < nc; k += 2) {
                    int ca = Math.max(0, (int) Math.ceil((cruces[k] - cab.lonOrigen()) / paso));
                    int cb = Math.min(ancho - 1, (int) Math.floor((cruces[k + 1] - cab.lonOrigen()) / paso));
                    for (int c = ca; c <= cb; c++) {
                        long idx = base + c;
                        long bit = 1L << (idx & 63);
                        int w = (int) (idx >>> 6);
                        if ((mascara[w] & bit) == 0) {
                            mascara[w] |= bit;
                            marcadas++;
                        }
                    }
                }
            }
        }
        return marcadas;
    }
}
