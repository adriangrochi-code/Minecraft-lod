package com.example.minecraftlodmod.tierra;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Lector mínimo de GeoTIFF de una banda, lo justo para los datos de
 * {@code docs/tierra-real/02-datos.md} (ETOPO 2022: teselas internas de
 * 256×256, Deflate, predictor de punto flotante, float32) sin dependencias.
 *
 * Soporta: TIFF clásico (no BigTIFF) en los dos órdenes de bytes, teselas o
 * tiras, sin compresión o Deflate (8 y 32946), predictor 1/2/3, muestras
 * int16 / uint16 / int32 / float32 / float64, y la georreferencia por
 * {@code ModelPixelScale} + {@code ModelTiepoint} (sin rotación). Lo demás
 * (LZW, JPEG, varias bandas) da error claro en vez de leer mal.
 *
 * Lectura por ventanas ({@link #leerVentana}) con una caché chica de bloques
 * internos ya descomprimidos: el preparador recorre la grilla por teselas de
 * salida, así que cada bloque interno se descomprime pocas veces.
 * No es thread-safe.
 */
public final class GeoTiff implements AutoCloseable {

    private static final int TAG_ANCHO = 256, TAG_ALTO = 257, TAG_BITS = 258, TAG_COMPRESION = 259;
    private static final int TAG_OFFSETS_TIRA = 273, TAG_MUESTRAS_PIXEL = 277, TAG_FILAS_TIRA = 278;
    private static final int TAG_BYTES_TIRA = 279, TAG_PREDICTOR = 317, TAG_ANCHO_BLOQUE = 322;
    private static final int TAG_ALTO_BLOQUE = 323, TAG_OFFSETS_BLOQUE = 324, TAG_BYTES_BLOQUE = 325;
    private static final int TAG_FORMATO_MUESTRA = 339, TAG_ESCALA_PIXEL = 33550, TAG_TIEPOINT = 33922;
    private static final int TAG_GEOCLAVES = 34735, TAG_SIN_DATO = 42113;
    private static final int GEOCLAVE_TIPO_RASTER = 1025, RASTER_ES_PUNTO = 2;

    private final FileChannel canal;
    private final ByteOrder orden;
    public final int ancho, alto;
    private final int bitsMuestra, formatoMuestra, compresion, predictor;
    private final int anchoBloque, altoBloque, bloquesPorFila;
    private final long[] offsetsBloque, bytesBloque;
    /** Longitud y latitud del centro del píxel (0, 0); la fila 0 es la del norte. */
    public final double lonOrigen, latOrigen;
    public final double pasoLon, pasoLat;
    /** Valor de "sin dato" declarado (NaN si no hay). */
    public final double sinDato;

    private final Map<Integer, float[]> cacheBloques;

    private GeoTiff(FileChannel canal, ByteOrder orden, Map<Integer, long[]> tags, Map<Integer, String> textos) throws IOException {
        this.canal = canal;
        this.orden = orden;
        this.ancho = (int) unico(tags, TAG_ANCHO);
        this.alto = (int) unico(tags, TAG_ALTO);
        this.bitsMuestra = (int) unico(tags, TAG_BITS);
        this.formatoMuestra = (int) opcional(tags, TAG_FORMATO_MUESTRA, 1);
        this.compresion = (int) opcional(tags, TAG_COMPRESION, 1);
        this.predictor = (int) opcional(tags, TAG_PREDICTOR, 1);
        if (opcional(tags, TAG_MUESTRAS_PIXEL, 1) != 1) {
            throw new IOException("GeoTIFF con más de una banda: no soportado");
        }
        if (compresion != 1 && compresion != 8 && compresion != 32946) {
            throw new IOException("Compresión TIFF " + compresion + " no soportada (solo ninguna o Deflate)");
        }
        if (predictor < 1 || predictor > 3) {
            throw new IOException("Predictor TIFF " + predictor + " no soportado");
        }
        tipoValido(formatoMuestra, bitsMuestra);
        if (tags.containsKey(TAG_ANCHO_BLOQUE)) {
            this.anchoBloque = (int) unico(tags, TAG_ANCHO_BLOQUE);
            this.altoBloque = (int) unico(tags, TAG_ALTO_BLOQUE);
            this.offsetsBloque = tags.get(TAG_OFFSETS_BLOQUE);
            this.bytesBloque = tags.get(TAG_BYTES_BLOQUE);
        } else {
            this.anchoBloque = ancho;
            this.altoBloque = (int) Math.min(opcional(tags, TAG_FILAS_TIRA, alto), alto);
            this.offsetsBloque = tags.get(TAG_OFFSETS_TIRA);
            this.bytesBloque = tags.get(TAG_BYTES_TIRA);
        }
        this.bloquesPorFila = (ancho + anchoBloque - 1) / anchoBloque;
        int bloques = bloquesPorFila * ((alto + altoBloque - 1) / altoBloque);
        if (offsetsBloque == null || bytesBloque == null || offsetsBloque.length < bloques || bytesBloque.length < bloques) {
            throw new IOException("Tabla de bloques TIFF incompleta");
        }

        long[] escala = tags.get(TAG_ESCALA_PIXEL);
        long[] tiepoint = tags.get(TAG_TIEPOINT);
        if (escala == null || tiepoint == null || tiepoint.length < 6) {
            throw new IOException("GeoTIFF sin ModelPixelScale/ModelTiepoint");
        }
        this.pasoLon = Double.longBitsToDouble(escala[0]);
        this.pasoLat = Double.longBitsToDouble(escala[1]);
        double i = Double.longBitsToDouble(tiepoint[0]), j = Double.longBitsToDouble(tiepoint[1]);
        double x = Double.longBitsToDouble(tiepoint[3]), y = Double.longBitsToDouble(tiepoint[4]);
        // PixelIsArea (lo habitual, y ETOPO): el tiepoint es la esquina del píxel.
        double medio = esPixelPunto(tags.get(TAG_GEOCLAVES)) ? 0 : 0.5;
        this.lonOrigen = x + (medio - i) * pasoLon;
        this.latOrigen = y - (medio - j) * pasoLat;
        String sd = textos.get(TAG_SIN_DATO);
        this.sinDato = sd == null ? Double.NaN : Double.parseDouble(sd.trim());

        this.cacheBloques = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, float[]> e) {
                return size() > Math.max(8, 2 * bloquesPorFila);
            }
        };
    }

    public static GeoTiff abrir(Path archivo) throws IOException {
        FileChannel canal = FileChannel.open(archivo, StandardOpenOption.READ);
        try {
            ByteBuffer cab = leer(canal, 0, 8, ByteOrder.LITTLE_ENDIAN);
            ByteOrder orden;
            if (cab.get(0) == 'I' && cab.get(1) == 'I') orden = ByteOrder.LITTLE_ENDIAN;
            else if (cab.get(0) == 'M' && cab.get(1) == 'M') orden = ByteOrder.BIG_ENDIAN;
            else throw new IOException("No es un TIFF");
            cab.order(orden);
            int magia = cab.getShort(2) & 0xFFFF;
            if (magia == 43) throw new IOException("BigTIFF no soportado");
            if (magia != 42) throw new IOException("No es un TIFF");
            long ifd = cab.getInt(4) & 0xFFFFFFFFL;

            int n = leer(canal, ifd, 2, orden).getShort(0) & 0xFFFF;
            ByteBuffer entradas = leer(canal, ifd + 2, n * 12, orden);
            Map<Integer, long[]> tags = new java.util.HashMap<>();
            Map<Integer, String> textos = new java.util.HashMap<>();
            for (int k = 0; k < n; k++) {
                int base = k * 12;
                int tag = entradas.getShort(base) & 0xFFFF;
                int tipo = entradas.getShort(base + 2) & 0xFFFF;
                long cuenta = entradas.getInt(base + 4) & 0xFFFFFFFFL;
                int tamTipo = tamanoTipo(tipo);
                if (tamTipo == 0) continue;
                long bytes = cuenta * tamTipo;
                if (bytes > Integer.MAX_VALUE) throw new IOException("Tag TIFF demasiado grande: " + tag);
                ByteBuffer valor = bytes <= 4
                        ? entradas.slice(base + 8, 4).order(orden)
                        : leer(canal, entradas.getInt(base + 8) & 0xFFFFFFFFL, (int) bytes, orden);
                if (tipo == 2) {
                    byte[] b = new byte[(int) bytes];
                    valor.get(0, b);
                    int fin = b.length;
                    while (fin > 0 && b[fin - 1] == 0) fin--;
                    textos.put(tag, new String(b, 0, fin, java.nio.charset.StandardCharsets.US_ASCII));
                    continue;
                }
                long[] v = new long[(int) cuenta];
                for (int m = 0; m < cuenta; m++) {
                    v[m] = switch (tipo) {
                        case 1, 7 -> valor.get(m) & 0xFF;
                        case 3 -> valor.getShort(m * 2) & 0xFFFF;
                        case 4 -> valor.getInt(m * 4) & 0xFFFFFFFFL;
                        case 8 -> valor.getShort(m * 2);
                        case 9 -> valor.getInt(m * 4);
                        case 11 -> Double.doubleToRawLongBits(valor.getFloat(m * 4));
                        case 12 -> Double.doubleToRawLongBits(valor.getDouble(m * 8));
                        case 16 -> valor.getLong(m * 8);
                        default -> 0;
                    };
                }
                tags.put(tag, v);
            }
            return new GeoTiff(canal, orden, tags, textos);
        } catch (IOException | RuntimeException e) {
            canal.close();
            throw e;
        }
    }

    /**
     * Copia la ventana [col0, col0+anchoV) × [fila0, fila0+altoV) a
     * {@code destino} (fila por fila, de norte a sur). Lo que cae fuera de la
     * imagen se rellena con el borde más cercano.
     */
    public void leerVentana(int col0, int fila0, int anchoV, int altoV, float[] destino) throws IOException {
        for (int f = 0; f < altoV; f++) {
            int fila = Math.clamp(fila0 + f, 0, alto - 1);
            int bf = fila / altoBloque;
            int dentroF = fila - bf * altoBloque;
            int c = 0;
            while (c < anchoV) {
                int col = Math.clamp(col0 + c, 0, ancho - 1);
                int bc = col / anchoBloque;
                float[] bloque = bloque(bf * bloquesPorFila + bc);
                int dentroC = col - bc * anchoBloque;
                if (col0 + c < 0 || col0 + c >= ancho) {
                    destino[f * anchoV + c] = bloque[dentroF * anchoBloque + dentroC];
                    c++;
                    continue;
                }
                int cuantos = Math.min(anchoV - c, Math.min(anchoBloque - dentroC, ancho - col));
                System.arraycopy(bloque, dentroF * anchoBloque + dentroC, destino, f * anchoV + c, cuantos);
                c += cuantos;
            }
        }
    }

    private float[] bloque(int indice) throws IOException {
        float[] b = cacheBloques.get(indice);
        if (b == null) {
            b = decodificar(indice);
            cacheBloques.put(indice, b);
        }
        return b;
    }

    private float[] decodificar(int indice) throws IOException {
        int bytesPorMuestra = bitsMuestra / 8;
        int bytesFila = anchoBloque * bytesPorMuestra;
        byte[] crudo = new byte[bytesFila * altoBloque];
        long tam = bytesBloque[indice];
        if (tam > 0) {
            ByteBuffer comprimido = leer(canal, offsetsBloque[indice], (int) tam, orden);
            if (compresion == 1) {
                comprimido.get(0, crudo, 0, Math.min(crudo.length, (int) tam));
            } else {
                Inflater inf = new Inflater();
                try {
                    inf.setInput(comprimido);
                    int total = 0;
                    while (total < crudo.length && !inf.finished()) {
                        int n = inf.inflate(crudo, total, crudo.length - total);
                        if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                        total += n;
                    }
                } catch (DataFormatException e) {
                    throw new IOException("Bloque TIFF " + indice + " corrupto", e);
                } finally {
                    inf.end();
                }
            }
        }
        if (predictor == 3) deshacerPredictorFlotante(crudo, anchoBloque, altoBloque, bytesPorMuestra);
        ByteBuffer bb = ByteBuffer.wrap(crudo).order(predictor == 3 ? ByteOrder.BIG_ENDIAN : orden);
        float[] valores = new float[anchoBloque * altoBloque];
        for (int f = 0; f < altoBloque; f++) {
            double previo = 0;
            for (int c = 0; c < anchoBloque; c++) {
                int i = f * anchoBloque + c;
                double v = muestra(bb, i * bytesPorMuestra);
                if (predictor == 2) {
                    v = acumularEntero(previo, v);
                    previo = v;
                }
                valores[i] = (float) v;
            }
        }
        return valores;
    }

    private double muestra(ByteBuffer bb, int pos) {
        if (formatoMuestra == 3) return bitsMuestra == 32 ? bb.getFloat(pos) : bb.getDouble(pos);
        boolean conSigno = formatoMuestra == 2;
        return switch (bitsMuestra) {
            case 16 -> conSigno ? bb.getShort(pos) : bb.getShort(pos) & 0xFFFF;
            default -> conSigno ? bb.getInt(pos) : bb.getInt(pos) & 0xFFFFFFFFL;
        };
    }

    /** Predictor 2 (diferencia horizontal) en enteros: suma con desborde del ancho de la muestra. */
    private double acumularEntero(double previo, double delta) {
        long suma = (long) previo + (long) delta;
        if (bitsMuestra == 16) return formatoMuestra == 2 ? (short) suma : suma & 0xFFFF;
        return formatoMuestra == 2 ? (int) suma : suma & 0xFFFFFFFFL;
    }

    /**
     * Predictor 3 (TIFF Technical Note 3): cada fila guarda los bytes de las
     * muestras separados por plano (primero el más significativo de todas las
     * muestras, después el siguiente…) y con diferencia horizontal de bytes.
     * Se deshace la diferencia y se vuelve a intercalar en big-endian.
     */
    static void deshacerPredictorFlotante(byte[] datos, int anchoB, int altoB, int bytesPorMuestra) {
        int bytesFila = anchoB * bytesPorMuestra;
        byte[] fila = new byte[bytesFila];
        for (int f = 0; f < altoB; f++) {
            int base = f * bytesFila;
            for (int i = 1; i < bytesFila; i++) {
                datos[base + i] += datos[base + i - 1];
            }
            System.arraycopy(datos, base, fila, 0, bytesFila);
            for (int c = 0; c < anchoB; c++) {
                for (int p = 0; p < bytesPorMuestra; p++) {
                    datos[base + c * bytesPorMuestra + p] = fila[p * anchoB + c];
                }
            }
        }
    }

    private static void tipoValido(int formato, int bits) throws IOException {
        boolean ok = (formato == 3 && (bits == 32 || bits == 64))
                || ((formato == 1 || formato == 2) && (bits == 16 || bits == 32));
        if (!ok) throw new IOException("Tipo de muestra TIFF no soportado: formato " + formato + ", " + bits + " bits");
    }

    private static boolean esPixelPunto(long[] geoclaves) {
        if (geoclaves == null) return false;
        for (int k = 4; k + 3 < geoclaves.length; k += 4) {
            if (geoclaves[k] == GEOCLAVE_TIPO_RASTER && geoclaves[k + 1] == 0) {
                return geoclaves[k + 3] == RASTER_ES_PUNTO;
            }
        }
        return false;
    }

    private static int tamanoTipo(int tipo) {
        return switch (tipo) {
            case 1, 2, 6, 7 -> 1;
            case 3, 8 -> 2;
            case 4, 9, 11 -> 4;
            case 5, 10, 12, 16, 17 -> 8;
            default -> 0;
        };
    }

    private static long unico(Map<Integer, long[]> tags, int tag) throws IOException {
        long[] v = tags.get(tag);
        if (v == null || v.length == 0) throw new IOException("Falta el tag TIFF " + tag);
        return v[0];
    }

    private static long opcional(Map<Integer, long[]> tags, int tag, long porDefecto) {
        long[] v = tags.get(tag);
        return v == null || v.length == 0 ? porDefecto : v[0];
    }

    private static ByteBuffer leer(FileChannel canal, long pos, int n, ByteOrder orden) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(n).order(orden);
        while (b.hasRemaining()) {
            if (canal.read(b, pos + b.position()) < 0) throw new IOException("TIFF truncado");
        }
        return b.flip();
    }

    @Override
    public void close() throws IOException {
        canal.close();
    }
}
