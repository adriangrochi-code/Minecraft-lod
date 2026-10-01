package com.example.minecraftlodmod.tierra;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntBinaryOperator;
import java.util.zip.Deflater;

/** Escritor de GeoTIFF chiquitos para los tests (lo mínimo que lee {@link GeoTiff}). */
final class TiffDePrueba {

    /** float32 en teselas, Deflate, predictor 3 (como ETOPO 2022). */
    static void flotanteEnTeselas(Path archivo, ByteOrder orden, int ancho, int alto, int lado,
                                  double lonEsquina, double latEsquina, double paso, ValorFlotante valor) throws IOException {
        int tx = (ancho + lado - 1) / lado, tz = (alto + lado - 1) / lado;
        List<byte[]> bloques = new ArrayList<>();
        for (int bz = 0; bz < tz; bz++) {
            for (int bx = 0; bx < tx; bx++) {
                byte[] crudo = new byte[lado * lado * 4];
                for (int f = 0; f < lado; f++) {
                    int fila = Math.min(bz * lado + f, alto - 1);
                    byte[] planos = new byte[lado * 4];
                    for (int c = 0; c < lado; c++) {
                        int col = Math.min(bx * lado + c, ancho - 1);
                        int bits = Float.floatToRawIntBits(valor.en(col, fila));
                        for (int p = 0; p < 4; p++) planos[p * lado + c] = (byte) (bits >>> (24 - 8 * p));
                    }
                    for (int i = planos.length - 1; i > 0; i--) planos[i] -= planos[i - 1];
                    System.arraycopy(planos, 0, crudo, f * lado * 4, planos.length);
                }
                bloques.add(deflate(crudo));
            }
        }
        escribir(archivo, orden, ancho, alto, 32, 3, 8, 3, lado, lado, bloques, true, lonEsquina, latEsquina, paso);
    }

    /** int16 en tiras de 3 filas, sin compresión, predictor 2. */
    static void enteroEnTiras(Path archivo, ByteOrder orden, int ancho, int alto,
                              double lonEsquina, double latEsquina, double paso, IntBinaryOperator valor) throws IOException {
        int filasTira = 3;
        List<byte[]> bloques = new ArrayList<>();
        for (int f0 = 0; f0 < alto; f0 += filasTira) {
            int filas = Math.min(filasTira, alto - f0);
            ByteBuffer b = ByteBuffer.allocate(filas * ancho * 2).order(orden);
            for (int f = 0; f < filas; f++) {
                short previo = 0;
                for (int c = 0; c < ancho; c++) {
                    short v = (short) valor.applyAsInt(c, f0 + f);
                    b.putShort((short) (v - previo));
                    previo = v;
                }
            }
            bloques.add(b.array());
        }
        escribir(archivo, orden, ancho, alto, 16, 2, 1, 2, ancho, filasTira, bloques, false, lonEsquina, latEsquina, paso);
    }

    interface ValorFlotante {
        float en(int col, int fila);
    }

    private static void escribir(Path archivo, ByteOrder orden, int ancho, int alto, int bits, int formato,
                                 int compresion, int predictor, int anchoB, int altoB, List<byte[]> bloques,
                                 boolean teselas, double lon, double lat, double paso) throws IOException {
        // Cabecera (8) | IFD | datos extra (doubles, offsets) | bloques
        int nTags = 13;
        int ifd = 8;
        int extra = ifd + 2 + nTags * 12 + 4;
        int escala = extra, tiepoint = escala + 24, offsets = tiepoint + 48, tamanos = offsets + 4 * bloques.size();
        int datos = tamanos + 4 * bloques.size();
        ByteBuffer b = ByteBuffer.allocate(datos + bloques.stream().mapToInt(x -> x.length).sum()).order(orden);
        b.put(orden == ByteOrder.LITTLE_ENDIAN ? new byte[]{'I', 'I'} : new byte[]{'M', 'M'});
        b.putShort((short) 42).putInt(ifd);
        b.putShort((short) nTags);
        tag(b, 256, 4, 1, ancho);
        tag(b, 257, 4, 1, alto);
        tag(b, 258, 3, 1, bits);
        tag(b, 259, 3, 1, compresion);
        tag(b, 277, 3, 1, 1);
        tag(b, 317, 3, 1, predictor);
        if (teselas) {
            tag(b, 322, 4, 1, anchoB);
            tag(b, 323, 4, 1, altoB);
            tag(b, 324, 4, bloques.size(), offsets);
            tag(b, 325, 4, bloques.size(), tamanos);
        } else {
            tag(b, 273, 4, bloques.size(), offsets);
            tag(b, 278, 4, 1, altoB);
            tag(b, 279, 4, bloques.size(), tamanos);
            tag(b, 284, 3, 1, 1); // relleno para que la cantidad de tags sea la misma
        }
        tag(b, 339, 3, 1, formato);
        tag(b, 33550, 12, 3, escala);
        tag(b, 33922, 12, 6, tiepoint);
        b.putInt(0);
        b.position(escala);
        b.putDouble(paso).putDouble(paso).putDouble(0);
        b.putDouble(0).putDouble(0).putDouble(0).putDouble(lon).putDouble(lat).putDouble(0);
        int pos = datos;
        for (byte[] bl : bloques) {
            b.putInt(pos);
            pos += bl.length;
        }
        for (byte[] bl : bloques) b.putInt(bl.length);
        for (byte[] bl : bloques) b.put(bl);
        Files.write(archivo, b.array());
    }

    private static void tag(ByteBuffer b, int tag, int tipo, int cuenta, int valor) {
        b.putShort((short) tag).putShort((short) tipo).putInt(cuenta);
        if (tipo == 3 && cuenta == 1) {
            b.putShort((short) valor).putShort((short) 0);
        } else {
            b.putInt(valor);
        }
    }

    private static byte[] deflate(byte[] crudo) {
        Deflater d = new Deflater();
        d.setInput(crudo);
        d.finish();
        ByteArrayOutputStream s = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (!d.finished()) s.write(buf, 0, d.deflate(buf));
        d.end();
        return s.toByteArray();
    }

    private TiffDePrueba() {}
}
