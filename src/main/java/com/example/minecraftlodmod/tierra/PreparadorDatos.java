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
 * elevación. Sin {@code --clima} la clase queda en 0.
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
            return EscritorLodt.escribir(salida, cab, (col, fila, anchoV, altoV, elevacion, bioma) -> {
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
            });
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("Uso: PreparadorDatos entrada.tif salida.lodt [--clima koppen.tif] [--reduccion N] [--recorte latSur latNorte lonOeste lonEste]");
            System.exit(2);
        }
        int reduccion = 1;
        Recorte recorte = null;
        Path clima = null;
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--reduccion" -> reduccion = Integer.parseInt(args[++i]);
                case "--clima" -> clima = Path.of(args[++i]);
                case "--recorte" -> recorte = new Recorte(Double.parseDouble(args[++i]), Double.parseDouble(args[++i]),
                        Double.parseDouble(args[++i]), Double.parseDouble(args[++i]));
                default -> throw new IllegalArgumentException("Opción desconocida: " + args[i]);
            }
        }
        Path salida = Path.of(args[1]);
        long inicio = System.nanoTime();
        FormatoLodt.Cabecera cab = preparar(Path.of(args[0]), clima, salida, reduccion, recorte, FormatoLodt.LADO_POR_DEFECTO);
        System.out.printf(Locale.ROOT, "%s: %dx%d muestras, paso %.6f grados, %d teselas, elevacion %d..%d m, %.2f MB, %.1f s%n",
                salida, cab.ancho(), cab.alto(), cab.paso(), cab.cantidadTeselas(), cab.elevMinima(), cab.elevMaxima(),
                Files.size(salida) / 1048576.0, (System.nanoTime() - inicio) / 1e9);
    }
}
