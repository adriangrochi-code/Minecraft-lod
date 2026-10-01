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
 *       entrada.tif salida.lodt [--reduccion N] [--recorte latSur latNorte lonOeste lonEste]
 * </pre>
 *
 * {@code --reduccion N} promedia bloques de N×N píxeles (de 15″ a 30″ con 2);
 * {@code --recorte} se ajusta a la grilla de píxeles de origen (para los datos
 * de prueba de {@code src/test/resources/tierra/}). La clase de bioma queda en
 * 0 hasta H4 ({@code docs/tierra-real/06-hitos.md}).
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
        if (reduccion < 1) throw new IllegalArgumentException("La reducción debe ser >= 1");
        try (GeoTiff tif = GeoTiff.abrir(entrada)) {
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
            });
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("Uso: PreparadorDatos entrada.tif salida.lodt [--reduccion N] [--recorte latSur latNorte lonOeste lonEste]");
            System.exit(2);
        }
        int reduccion = 1;
        Recorte recorte = null;
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--reduccion" -> reduccion = Integer.parseInt(args[++i]);
                case "--recorte" -> recorte = new Recorte(Double.parseDouble(args[++i]), Double.parseDouble(args[++i]),
                        Double.parseDouble(args[++i]), Double.parseDouble(args[++i]));
                default -> throw new IllegalArgumentException("Opción desconocida: " + args[i]);
            }
        }
        Path salida = Path.of(args[1]);
        long inicio = System.nanoTime();
        FormatoLodt.Cabecera cab = preparar(Path.of(args[0]), salida, reduccion, recorte, FormatoLodt.LADO_POR_DEFECTO);
        System.out.printf(Locale.ROOT, "%s: %dx%d muestras, paso %.6f grados, %d teselas, elevacion %d..%d m, %.2f MB, %.1f s%n",
                salida, cab.ancho(), cab.alto(), cab.paso(), cab.cantidadTeselas(), cab.elevMinima(), cab.elevMaxima(),
                Files.size(salida) / 1048576.0, (System.nanoTime() - inicio) / 1e9);
    }
}
