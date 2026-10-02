package com.example.minecraftlodmod.tierra;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Convierte un GeoTIFF de elevación (ETOPO 2022, {@code _surface}) al formato
 * {@code .lodt}. Se corre una sola vez, fuera del juego:
 *
 * <pre>
 *   java -cp &lt;clases&gt; com.example.minecraftlodmod.tierra.PreparadorDatos \
 *       entrada.tif salida.lodt [--clima koppen.tif] [--reduccion N] [--recorte latSur latNorte lonOeste lonEste]
 * </pre>
 *
 * {@code --reduccion N} promedia bloques de N×N píxeles (de 15″ a 30″ con 2);
 * {@code --recorte} se ajusta a la grilla de píxeles de origen (para los datos
 * de prueba de {@code src/test/resources/tierra/}). {@code --clima} llena la
 * clase de bioma con el mapa de Köppen-Geiger de Beck et al. (2023; 1..30,
 * 0 = sin dato, el mar), tomando para cada muestra el píxel que contiene su
 * centro: sirve con cualquier grilla de clima, igual o distinta a la de
 * elevación. Sin {@code --clima} la clase queda en 0. Con clima se calculan
 * además los niveles del agua ({@link AguaContinental}: océano, lagos y
 * depresiones secas) con la grilla entera en memoria ({@code -Xmx8g} para la
 * global de 30″).
 */
public final class PreparadorDatos {

    private PreparadorDatos() {}

    /** Recorte en grados; {@code null} = todo el archivo. */
    public record Recorte(double latSur, double latNorte, double lonOeste, double lonEste) {
        public Recorte {
            if (!(latSur < latNorte) || !(lonOeste < lonEste)) {
                throw new IllegalArgumentException("Recorte vacío");
            }
        }
    }

    public static FormatoLodt.Cabecera preparar(Path entrada, Path salida, int reduccion, Recorte recorte, int lado) throws IOException {
        return preparar(entrada, null, salida, reduccion, recorte, lado);
    }

    public static FormatoLodt.Cabecera preparar(Path entrada, Path clima, Path salida, int reduccion, Recorte recorte, int lado) throws IOException {
        return preparar(entrada, clima, java.util.List.of(), salida, reduccion, recorte, lado);
    }

    /** @param aguaVector shapefiles de polígonos de agua (Natural Earth: lagos y océano), o vacío */
    public static FormatoLodt.Cabecera preparar(Path entrada, Path clima, java.util.List<Path> aguaVector, Path salida,
                                                int reduccion, Recorte recorte, int lado) throws IOException {
        if (reduccion < 1) throw new IllegalArgumentException("La reducción debe ser >= 1");
        try (GeoTiff tif = GeoTiff.abrir(entrada); GeoTiff tifClima = clima == null ? null : GeoTiff.abrir(clima)) {
            if (Math.abs(tif.pasoLon - tif.pasoLat) > tif.pasoLon * 1e-6) {
                throw new IOException("El GeoTIFF no tiene el mismo paso en latitud y longitud");
            }
            double paso = tif.pasoLon;
            double oeste = tif.lonOrigen - paso / 2, norte = tif.latOrigen + paso / 2;
            int col0 = 0, fila0 = 0, anchoOrigen = tif.ancho, altoOrigen = tif.alto;
            if (recorte != null) {
                col0 = (int) Math.round((recorte.lonOeste() - oeste) / paso);
                fila0 = (int) Math.round((norte - recorte.latNorte()) / paso);
                anchoOrigen = (int) Math.round((recorte.lonEste() - recorte.lonOeste()) / paso);
                altoOrigen = (int) Math.round((recorte.latNorte() - recorte.latSur()) / paso);
                if (col0 < 0 || fila0 < 0 || col0 + anchoOrigen > tif.ancho || fila0 + altoOrigen > tif.alto) {
                    throw new IOException("El recorte se sale del GeoTIFF");
                }
            }
            int ancho = anchoOrigen / reduccion, alto = altoOrigen / reduccion;
            if (ancho == 0 || alto == 0) throw new IOException("Recorte más chico que la reducción");
            double pasoSalida = paso * reduccion;
            FormatoLodt.Cabecera cab = new FormatoLodt.Cabecera(ancho, alto, lado,
                    oeste + col0 * paso + pasoSalida / 2, norte - fila0 * paso - pasoSalida / 2, pasoSalida);

            final int c0 = col0, f0 = fila0;
            float[] ventana = new float[lado * reduccion * lado * reduccion];
            float[][] ventanaClima = new float[1][];
            EscritorLodt.Muestras origen = (col, fila, anchoV, altoV, elevacion, bioma, nivelIgnorado) -> {
                int anchoO = anchoV * reduccion, altoO = altoV * reduccion;
                tif.leerVentana(c0 + col * reduccion, f0 + fila * reduccion, anchoO, altoO, ventana);
                for (int f = 0; f < altoV; f++) {
                    for (int c = 0; c < anchoV; c++) {
                        double suma = 0;
                        int cuenta = 0;
                        for (int df = 0; df < reduccion; df++) {
                            for (int dc = 0; dc < reduccion; dc++) {
                                float v = ventana[(f * reduccion + df) * anchoO + c * reduccion + dc];
                                if (!Float.isNaN(v) && v != tif.sinDato) {
                                    suma += v;
                                    cuenta++;
                                }
                            }
                        }
                        double metros = cuenta == 0 ? 0 : suma / cuenta;
                        elevacion[f * anchoV + c] = (short) Math.clamp(Math.round(metros), Short.MIN_VALUE, Short.MAX_VALUE);
                        bioma[f * anchoV + c] = 0;
                    }
                }
                if (tifClima != null) {
                    // Píxeles de clima que contienen los centros de las muestras de esta tesela
                    double oesteC = tifClima.lonOrigen - tifClima.pasoLon / 2, norteC = tifClima.latOrigen + tifClima.pasoLat / 2;
                    double lonA = cab.lonOrigen() + col * cab.paso(), latA = cab.latOrigen() - fila * cab.paso();
                    int ca = (int) Math.floor((lonA - oesteC) / tifClima.pasoLon);
                    int fa = (int) Math.floor((norteC - latA) / tifClima.pasoLat);
                    int cb = (int) Math.floor((lonA + (anchoV - 1) * cab.paso() - oesteC) / tifClima.pasoLon);
                    int fb = (int) Math.floor((norteC - (latA - (altoV - 1) * cab.paso())) / tifClima.pasoLat);
                    int anchoC = cb - ca + 1, altoC = fb - fa + 1;
                    if (ventanaClima[0] == null || ventanaClima[0].length < anchoC * altoC) ventanaClima[0] = new float[anchoC * altoC];
                    float[] vc = ventanaClima[0];
                    tifClima.leerVentana(ca, fa, anchoC, altoC, vc);
                    for (int f = 0; f < altoV; f++) {
                        int fc = (int) Math.floor((norteC - (latA - f * cab.paso())) / tifClima.pasoLat) - fa;
                        for (int c = 0; c < anchoV; c++) {
                            int cc = (int) Math.floor((lonA + c * cab.paso() - oesteC) / tifClima.pasoLon) - ca;
                            float v = vc[Math.clamp(fc, 0, altoC - 1) * anchoC + Math.clamp(cc, 0, anchoC - 1)];
                            bioma[f * anchoV + c] = (byte) (Float.isNaN(v) || v == tifClima.sinDato ? 0 : Math.clamp((int) v, 0, 255));
                        }
                    }
                }
            };
            if (tifClima == null) {
                return EscritorLodt.escribir(salida, cab, origen); // sin clima no hay máscara de agua: nivel deducido
            }
            return conAgua(salida, cab, origen, aguaVector);
        }
    }

    /**
     * Con clima: la grilla entera en memoria (elevación, clase y nivel: ~5 bytes
     * por muestra, ~4,7 GB la global de 30″: correr con {@code -Xmx8g}), los
     * niveles del agua de {@link AguaContinental} y recién ahí se escribe.
     */
    private static FormatoLodt.Cabecera conAgua(Path salida, FormatoLodt.Cabecera cab, EscritorLodt.Muestras origen,
                                                java.util.List<Path> aguaVector) throws IOException {
        long total = (long) cab.ancho() * cab.alto();
        if (total > Integer.MAX_VALUE - 8) throw new IOException("Grilla demasiado grande para calcular el agua en memoria");
        int ancho = cab.ancho(), alto = cab.alto(), lado = cab.lado();
        short[] elev = new short[(int) total];
        byte[] clase = new byte[(int) total];
        short[] e = new short[lado * lado], n = new short[lado * lado];
        byte[] b = new byte[lado * lado];
        long t0 = System.nanoTime();
        for (int fila = 0; fila < alto; fila += lado) {
            for (int col = 0; col < ancho; col += lado) {
                int a = Math.min(lado, ancho - col), h = Math.min(lado, alto - fila);
                origen.leer(col, fila, a, h, e, b, n);
                for (int f = 0; f < h; f++) {
                    System.arraycopy(e, f * a, elev, (fila + f) * ancho + col, a);
                    System.arraycopy(b, f * a, clase, (fila + f) * ancho + col, a);
                }
            }
        }
        long t1 = System.nanoTime();
        if (!aguaVector.isEmpty()) {
            // Köppen da clima también sobre los lagos: los polígonos de agua los vuelven "sin clima" (agua).
            long[] mascara = new long[(int) ((total + 63) >>> 6)];
            long marcadas = 0;
            for (Path shp : aguaVector) marcadas += MascaraAgua.rasterizar(MascaraAgua.leerShapefile(shp), cab, mascara);
            for (int w = 0; w < mascara.length; w++) {
                long bits = mascara[w];
                while (bits != 0) {
                    int i = (w << 6) + Long.numberOfTrailingZeros(bits);
                    bits &= bits - 1;
                    if (i < total) clase[i] = 0;
                }
            }
            System.out.printf(Locale.ROOT, "agua: %d muestras dentro de los polígonos de agua%n", marcadas);
        }
        short[] nivel = AguaContinental.calcular(elev, clase, ancho, alto, cab.global());
        System.out.printf(Locale.ROOT, "agua: lectura %.1f s, masas de agua y niveles %.1f s%n", (t1 - t0) / 1e9,
                (System.nanoTime() - t1) / 1e9);
        return EscritorLodt.escribir(salida, cab, (col, fila, a, h, ve, vb, vn) -> {
            for (int f = 0; f < h; f++) {
                System.arraycopy(elev, (fila + f) * ancho + col, ve, f * a, a);
                System.arraycopy(clase, (fila + f) * ancho + col, vb, f * a, a);
                System.arraycopy(nivel, (fila + f) * ancho + col, vn, f * a, a);
            }
        });
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("Uso: PreparadorDatos entrada.tif salida.lodt [--clima koppen.tif] [--agua-vector lagos.shp,oceano.shp] "
                    + "[--reduccion N] [--recorte latSur latNorte lonOeste lonEste]");
            System.exit(2);
        }
        int reduccion = 1;
        Recorte recorte = null;
        Path clima = null;
        java.util.List<Path> aguaVector = new java.util.ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--reduccion" -> reduccion = Integer.parseInt(args[++i]);
                case "--clima" -> clima = Path.of(args[++i]);
                case "--agua-vector" -> {
                    for (String a : args[++i].split(",")) aguaVector.add(Path.of(a));
                }
                case "--recorte" -> recorte = new Recorte(Double.parseDouble(args[++i]), Double.parseDouble(args[++i]),
                        Double.parseDouble(args[++i]), Double.parseDouble(args[++i]));
                default -> throw new IllegalArgumentException("Opción desconocida: " + args[i]);
            }
        }
        Path salida = Path.of(args[1]);
        long inicio = System.nanoTime();
        FormatoLodt.Cabecera cab = preparar(Path.of(args[0]), clima, aguaVector, salida, reduccion, recorte, FormatoLodt.LADO_POR_DEFECTO);
        System.out.printf(Locale.ROOT, "%s: %dx%d muestras, paso %.6f grados, %d teselas, elevacion %d..%d m, %.2f MB, %.1f s%n",
                salida, cab.ancho(), cab.alto(), cab.paso(), cab.cantidadTeselas(), cab.elevMinima(), cab.elevMaxima(),
                Files.size(salida) / 1048576.0, (System.nanoTime() - inicio) / 1e9);
    }
}
