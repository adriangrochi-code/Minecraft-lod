package com.example.minecraftlodmod.tierra;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Formato {@code .lodt} de los datos de la Tierra real
 * ({@code docs/tierra-real/02-datos.md}), big-endian:
 *
 * <pre>
 *   magic "LODT" | version: 1 byte | 3 bytes reservados
 *   | ancho, alto: int (muestras)  | lado: int (muestras por lado de tesela)
 *   | lonOrigen, latOrigen: double (centro de la muestra (0, 0); fila 0 = norte)
 *   | paso: double (grados entre muestras, igual en lat y lon)
 *   | elevMinima, elevMaxima: short (metros, de todas las muestras: el fondo
 *     de la fosa más honda fija el piso de la dimensión, ver AlturaTierra)
 *   (versión 2: cada tesela trae además el nivel del agua, ver abajo)
 *   | índice: por tesela, en orden de filas, offset: long + tamaño: int
 *   | teselas
 * </pre>
 *
 * Cada tesela (lado × lado, completa aunque esté en el borde: lo de afuera
 * repite el borde) va con Deflate: primero la elevación en metros como short
 * con diferencia horizontal por fila (el primero de cada fila crudo; el mar y
 * las llanuras quedan casi en ceros y comprimen mucho), después la clase de
 * bioma, un byte por muestra, y (versión 2) el nivel del agua en metros
 * ({@link AguaContinental}; {@link AguaContinental#SECO} = sin agua), también
 * con diferencia por fila. La versión 1 se sigue leyendo: el nivel se deduce
 * como antes (mar = sin clima y bajo 0 m). Mismo patrón que {@code storage/RegionHeader}:
 * el índice al principio permite leer una tesela sola.
 */
public final class FormatoLodt {

    static final byte[] MAGIA = {'L', 'O', 'D', 'T'};
    static final int VERSION = 2;
    static final int BYTES_CABECERA = 4 + 4 + 4 * 3 + 8 * 3 + 2 * 2;
    static final int BYTES_ENTRADA_INDICE = 12;
    public static final int LADO_POR_DEFECTO = 256;

    private FormatoLodt() {}

    /** Geometría de la grilla y rango de elevación de sus muestras. */
    public record Cabecera(int ancho, int alto, int lado, double lonOrigen, double latOrigen, double paso,
                           short elevMinima, short elevMaxima, int version) {

        public Cabecera {
            if (ancho <= 0 || alto <= 0 || lado <= 0 || lado > 4096 || !(paso > 0) || elevMinima > elevMaxima
                    || version < 1 || version > VERSION) {
                throw new IllegalArgumentException("Cabecera .lodt inválida");
            }
        }

        /** Solo geometría (el escritor completa el rango de elevación). */
        public Cabecera(int ancho, int alto, int lado, double lonOrigen, double latOrigen, double paso) {
            this(ancho, alto, lado, lonOrigen, latOrigen, paso, (short) 0, (short) 0, VERSION);
        }

        public Cabecera(int ancho, int alto, int lado, double lonOrigen, double latOrigen, double paso,
                        short elevMinima, short elevMaxima) {
            this(ancho, alto, lado, lonOrigen, latOrigen, paso, elevMinima, elevMaxima, VERSION);
        }

        Cabecera conRango(short minima, short maxima) {
            return new Cabecera(ancho, alto, lado, lonOrigen, latOrigen, paso, minima, maxima, version);
        }

        public int teselasX() {
            return (ancho + lado - 1) / lado;
        }

        public int teselasZ() {
            return (alto + lado - 1) / lado;
        }

        public int cantidadTeselas() {
            return teselasX() * teselasZ();
        }

        /** La grilla da la vuelta completa en longitud (las columnas se envuelven). */
        public boolean global() {
            return Math.abs(ancho * paso - 360.0) < paso * 1e-3;
        }

        long bytesHastaTeselas() {
            return BYTES_CABECERA + (long) cantidadTeselas() * BYTES_ENTRADA_INDICE;
        }

        void escribir(ByteBuffer b) {
            b.put(MAGIA).put((byte) version).put((byte) 0).put((byte) 0).put((byte) 0);
            b.putInt(ancho).putInt(alto).putInt(lado);
            b.putDouble(lonOrigen).putDouble(latOrigen).putDouble(paso);
            b.putShort(elevMinima).putShort(elevMaxima);
        }

        static Cabecera leer(ByteBuffer b) throws IOException {
            byte[] magia = new byte[4];
            b.get(magia);
            if (!java.util.Arrays.equals(magia, MAGIA)) throw new IOException("No es un archivo .lodt");
            int version = b.get() & 0xFF;
            if (version < 1 || version > VERSION) throw new IOException("Versión .lodt " + version + " no soportada");
            b.position(b.position() + 3);
            int ancho = b.getInt(), alto = b.getInt(), lado = b.getInt();
            double lon = b.getDouble(), lat = b.getDouble(), paso = b.getDouble();
            short minima = b.getShort(), maxima = b.getShort();
            try {
                return new Cabecera(ancho, alto, lado, lon, lat, paso, minima, maxima, version);
            } catch (IllegalArgumentException e) {
                throw new IOException(e.getMessage());
            }
        }
    }

    static byte[] codificarTesela(short[] elevacion, byte[] bioma, short[] nivel, int lado) {
        int n = lado * lado;
        ByteBuffer crudo = ByteBuffer.allocate(n * 5);
        porFilas(crudo, elevacion, lado);
        crudo.put(bioma, 0, n);
        porFilas(crudo, nivel, lado);
        Deflater def = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            def.setInput(crudo.array());
            def.finish();
            ByteArrayOutputStream salida = new ByteArrayOutputStream(n / 4);
            byte[] buf = new byte[16384];
            while (!def.finished()) {
                salida.write(buf, 0, def.deflate(buf));
            }
            return salida.toByteArray();
        } finally {
            def.end();
        }
    }

    private static void porFilas(ByteBuffer b, short[] valores, int lado) {
        for (int f = 0; f < lado; f++) {
            short previo = 0;
            for (int c = 0; c < lado; c++) {
                short v = valores[f * lado + c];
                b.putShort((short) (v - previo));
                previo = v;
            }
        }
    }

    private static void desdeFilas(ByteBuffer b, short[] valores, int lado) {
        for (int f = 0; f < lado; f++) {
            short previo = 0;
            for (int c = 0; c < lado; c++) {
                previo = (short) (previo + b.getShort());
                valores[f * lado + c] = previo;
            }
        }
    }

    static void decodificarTesela(ByteBuffer comprimido, int lado, int version, short[] elevacion, byte[] bioma,
                                  short[] nivel) throws IOException {
        int n = lado * lado;
        byte[] crudo = new byte[version >= 2 ? n * 5 : n * 3];
        Inflater inf = new Inflater();
        try {
            inf.setInput(comprimido);
            int total = 0;
            while (total < crudo.length) {
                int leidos = inf.inflate(crudo, total, crudo.length - total);
                if (leidos == 0 && (inf.finished() || inf.needsInput() || inf.needsDictionary())) break;
                total += leidos;
            }
            if (total != crudo.length) throw new IOException("Tesela .lodt truncada");
        } catch (DataFormatException e) {
            throw new IOException("Tesela .lodt corrupta", e);
        } finally {
            inf.end();
        }
        ByteBuffer b = ByteBuffer.wrap(crudo);
        desdeFilas(b, elevacion, lado);
        b.get(bioma, 0, n);
        if (version >= 2) {
            desdeFilas(b, nivel, lado);
        } else {
            for (int i = 0; i < n; i++) nivel[i] = nivelDeducido(elevacion[i], bioma[i]);
        }
    }

    /** Nivel del agua sin calcular las masas (versión 1): mar donde no hay clima y está bajo 0 m. */
    static short nivelDeducido(short elevacion, byte clase) {
        return clase == 0 && elevacion < 0 ? 0 : AguaContinental.SECO;
    }
}
