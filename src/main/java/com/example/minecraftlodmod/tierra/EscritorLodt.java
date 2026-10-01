package com.example.minecraftlodmod.tierra;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Escribe un {@code .lodt} (ver {@link FormatoLodt}) desde una fuente de
 * muestras, tesela por tesela: nunca tiene en memoria más que una tesela. Se
 * escribe a un temporal y se renombra al final, así un archivo a medias nunca
 * queda con el nombre final.
 */
public final class EscritorLodt {

    /** Muestras de origen; las que caen fuera de la grilla las resuelve el escritor (repite el borde). */
    public interface Muestras {
        /**
         * Llena la ventana [col0, col0+lado) × [fila0, fila0+lado) de la grilla
         * de salida, recortada a la grilla: {@code ancho} × {@code alto}
         * muestras, fila por fila.
         */
        void leer(int col0, int fila0, int ancho, int alto, short[] elevacion, byte[] bioma) throws IOException;
    }

    private EscritorLodt() {}

    /** @return la cabecera escrita, con el rango de elevación de las muestras */
    public static FormatoLodt.Cabecera escribir(Path destino, FormatoLodt.Cabecera cab, Muestras muestras) throws IOException {
        Path temporal = destino.resolveSibling(destino.getFileName() + ".parcial");
        int lado = cab.lado();
        int n = cab.cantidadTeselas();
        ByteBuffer indice = ByteBuffer.allocate(n * FormatoLodt.BYTES_ENTRADA_INDICE);
        short[] elev = new short[lado * lado];
        byte[] bioma = new byte[lado * lado];
        short[] ventanaElev = new short[lado * lado];
        byte[] ventanaBioma = new byte[lado * lado];
        int minima = Short.MAX_VALUE, maxima = Short.MIN_VALUE;
        try (FileChannel canal = FileChannel.open(temporal, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            long pos = cab.bytesHastaTeselas();
            for (int tz = 0; tz < cab.teselasZ(); tz++) {
                for (int tx = 0; tx < cab.teselasX(); tx++) {
                    int col0 = tx * lado, fila0 = tz * lado;
                    int anchoV = Math.min(lado, cab.ancho() - col0);
                    int altoV = Math.min(lado, cab.alto() - fila0);
                    muestras.leer(col0, fila0, anchoV, altoV, ventanaElev, ventanaBioma);
                    for (int i = 0; i < anchoV * altoV; i++) {
                        minima = Math.min(minima, ventanaElev[i]);
                        maxima = Math.max(maxima, ventanaElev[i]);
                    }
                    for (int f = 0; f < lado; f++) {
                        int ff = Math.min(f, altoV - 1);
                        for (int c = 0; c < lado; c++) {
                            int cc = Math.min(c, anchoV - 1);
                            elev[f * lado + c] = ventanaElev[ff * anchoV + cc];
                            bioma[f * lado + c] = ventanaBioma[ff * anchoV + cc];
                        }
                    }
                    byte[] datos = FormatoLodt.codificarTesela(elev, bioma, lado);
                    escribirTodo(canal, ByteBuffer.wrap(datos), pos);
                    indice.putLong(pos).putInt(datos.length);
                    pos += datos.length;
                }
            }
            cab = cab.conRango((short) minima, (short) maxima);
            ByteBuffer cabecera = ByteBuffer.allocate(FormatoLodt.BYTES_CABECERA);
            cab.escribir(cabecera);
            escribirTodo(canal, cabecera.flip(), 0);
            escribirTodo(canal, indice.flip(), FormatoLodt.BYTES_CABECERA);
            canal.force(true);
        }
        Files.move(temporal, destino, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return cab;
    }

    private static void escribirTodo(FileChannel canal, ByteBuffer b, long pos) throws IOException {
        while (b.hasRemaining()) {
            pos += canal.write(b, pos);
        }
    }
}
