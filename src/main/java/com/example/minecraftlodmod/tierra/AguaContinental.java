package com.example.minecraftlodmod.tierra;

import java.util.ArrayList;
import java.util.List;

/**
 * Nivel del agua de cada muestra de la grilla ({@code docs/tierra-real/06-hitos.md},
 * H8), en metros, o {@link #SECO}. Lógica pura sobre la grilla entera (la usa
 * {@link PreparadorDatos} una sola vez; en memoria, ~2 bytes por muestra más
 * los datos).
 *
 * <ol>
 *   <li><b>Masas de agua</b>: muestras sin clase de clima (Köppen da 0 en el
 *       agua), conectadas por los 4 vecinos (con la longitud envuelta si la
 *       grilla es global). La más grande es el <b>océano</b>.</li>
 *   <li><b>Océano</b>: nivel 0, y se inunda todo lo conectado a él por debajo
 *       de 0 m (pólders, costas donde los dos datos no coinciden).</li>
 *   <li><b>Lagos</b> (las demás masas): nivel = la mediana de la elevación de
 *       la tierra a 2 muestras de su borde (la costa firme: a 1 muestra, con
 *       polígonos generalizados, todavía suele ser fondo del lago), e inundan
 *       lo conectado por debajo de ese nivel (así cubren lo que el polígono
 *       dejó afuera). El Caspio queda cerca de -28 m sin inundar las tierras
 *       bajas de alrededor. <b>Tope:</b> un lago no puede inundar más que su
 *       propio tamaño (mínimo {@link #EXTRA_MINIMO} muestras) fuera de sí: si
 *       la mediana lo pasa (una laguna chica en un valle seco, como en el valle
 *       de la Muerte o Qattara), se busca por bisección el nivel más alto que
 *       lo cumple.</li>
 *   <li>Todo lo demás queda <b>seco</b>, aunque esté bajo el nivel del mar
 *       (valle de la Muerte, Qattara): antes salían inundadas hasta y 62.</li>
 * </ol>
 * Relleno por líneas (scanline): la pila guarda tramos, no muestras, así el
 * océano (cientos de millones de muestras) no necesita una pila gigante.
 */
public final class AguaContinental {

    public static final short SECO = Short.MIN_VALUE;
    /** Inundación mínima permitida fuera de un lago, en muestras. */
    static final int EXTRA_MINIMO = 500;

    private AguaContinental() {}

    /** Masa de agua: una muestra cualquiera, cuántas muestras tiene y su orilla (tierra junto al agua). */
    record Masa(int semilla, long muestras, int[] orilla) {
    }

    /**
     * @param elevacion metros por muestra, fila por fila (fila 0 al norte)
     * @param clase     clase de clima por muestra (0 = agua)
     * @param envolver  la grilla da la vuelta en longitud
     * @return nivel del agua por muestra, en metros, o {@link #SECO}
     */
    public static short[] calcular(short[] elevacion, byte[] clase, int ancho, int alto, boolean envolver) {
        long n = (long) ancho * alto;
        if (elevacion.length != n || clase.length != n) throw new IllegalArgumentException("Tamaños distintos");
        short[] nivel = new short[(int) n];
        java.util.Arrays.fill(nivel, SECO);
        long[] visto = new long[(int) ((n + 63) >>> 6)];

        // 1. Masas de agua
        List<Masa> masas = new ArrayList<>();
        Relleno r = new Relleno(ancho, alto, envolver);
        for (int i = 0; i < n; i++) {
            if (clase[i] != 0 || marcado(visto, i)) continue;
            ListaEnteros orilla = new ListaEnteros();
            long cuantas = r.llenar(i, j -> clase[j] == 0 && !marcado(visto, j), j -> marcar(visto, j),
                    (j, vecino) -> {
                        if (clase[vecino] != 0) orilla.agregar(vecino);
                    });
            masas.add(new Masa(i, cuantas, orilla.arreglo()));
        }
        if (masas.isEmpty()) return nivel;
        Masa oceano = masas.get(0);
        for (Masa m : masas) if (m.muestras() > oceano.muestras()) oceano = m;

        // 2. Océano (primero: tiene prioridad) y 3. lagos, del nivel más bajo al más alto
        masas.remove(oceano);
        inundar(r, oceano.semilla(), (short) 0, elevacion, clase, nivel);
        List<int[]> lagos = new ArrayList<>(); // {semilla, nivel}
        Prueba prueba = new Prueba(r, (int) n);
        for (Masa m : masas) {
            int mediana = nivelDeLago(m.orilla(), r, elevacion, clase);
            int bajo = mediana;
            for (int o : m.orilla()) bajo = Math.min(bajo, elevacion[o]);
            long tope = m.muestras() + Math.max(m.muestras(), EXTRA_MINIMO);
            lagos.add(new int[]{m.semilla(), prueba.nivelMaximo(m.semilla(), bajo, mediana, tope, elevacion, clase, nivel)});
        }
        lagos.sort(java.util.Comparator.comparingInt(l -> l[1]));
        for (int[] l : lagos) {
            if (nivel[l[0]] != SECO) continue; // ya cubierta por otra
            inundar(r, l[0], (short) Math.clamp(l[1], Short.MIN_VALUE + 1, Short.MAX_VALUE), elevacion, clase, nivel);
        }
        return nivel;
    }

    /**
     * Mediana de la elevación de la tierra a 2 muestras del agua (vecinas de la
     * orilla que no tocan agua); si no hay (lago chico en un valle angosto), la
     * de la orilla misma.
     */
    static int nivelDeLago(int[] orilla, Relleno r, short[] elevacion, byte[] clase) {
        if (orilla.length == 0) return 0;
        ListaEnteros anillo = new ListaEnteros();
        int[] v = new int[4], w = new int[4];
        for (int o : orilla) {
            int k = r.vecinos(o, v);
            for (int a = 0; a < k; a++) {
                int q = v[a];
                if (clase[q] == 0) continue;
                boolean tocaAgua = false;
                int kk = r.vecinos(q, w);
                for (int b = 0; b < kk && !tocaAgua; b++) tocaAgua = clase[w[b]] == 0;
                if (!tocaAgua) anillo.agregar(elevacion[q]);
            }
        }
        int[] valores = anillo.tamano() > 0 ? anillo.arreglo() : java.util.Arrays.stream(orilla).map(o -> elevacion[o]).toArray();
        java.util.Arrays.sort(valores);
        return valores[valores.length / 2];
    }

    /**
     * Inundaciones de prueba (sin escribir niveles) para acotar el nivel de un
     * lago: cuenta cuántas muestras cubriría y corta al pasar el tope.
     */
    static final class Prueba {
        private final Relleno r;
        private final long[] vistos;
        private final ListaEnteros tocados = new ListaEnteros();

        Prueba(Relleno r, int n) {
            this.r = r;
            this.vistos = new long[(n + 63) >>> 6];
        }

        /** El nivel más alto entre bajo y alto (metros) cuya inundación no pasa del tope. */
        int nivelMaximo(int semilla, int bajo, int alto, long tope, short[] elevacion, byte[] clase, short[] nivel) {
            if (cubre(semilla, alto, tope, elevacion, clase, nivel) <= tope) return alto;
            int lo = bajo, hi = alto; // lo cumple (o es el piso), hi no
            while (hi - lo > 1) {
                int medio = lo + (hi - lo) / 2;
                if (cubre(semilla, medio, tope, elevacion, clase, nivel) <= tope) lo = medio;
                else hi = medio;
            }
            return lo;
        }

        private long cubre(int semilla, int valor, long tope, short[] elevacion, byte[] clase, short[] nivel) {
            boolean esAgua = clase[semilla] == 0;
            long[] cuenta = {0};
            try {
                r.llenar(semilla, j -> nivel[j] == SECO && !marcado(vistos, j)
                                && (elevacion[j] < valor || (esAgua && clase[j] == 0)),
                        j -> {
                            marcar(vistos, j);
                            tocados.agregar(j);
                            if (++cuenta[0] > tope) throw CORTE;
                        }, null);
            } catch (Corte c) {
                // pasó del tope
            } finally {
                for (int k = 0; k < tocados.tamano(); k++) {
                    int j = tocados.en(k);
                    vistos[j >>> 6] &= ~(1L << (j & 63));
                }
                tocados.vaciar();
            }
            return cuenta[0];
        }
    }

    private static final class Corte extends RuntimeException {
        Corte() {
            super(null, null, false, false);
        }
    }

    private static final Corte CORTE = new Corte();

    /** Lista de enteros sin objetos. */
    static final class ListaEnteros {
        private int[] datos = new int[16];
        private int n;

        void agregar(int v) {
            if (n == datos.length) datos = java.util.Arrays.copyOf(datos, n * 2);
            datos[n++] = v;
        }

        int tamano() {
            return n;
        }

        int en(int k) {
            return datos[k];
        }

        void vaciar() {
            n = 0;
        }

        int[] arreglo() {
            return java.util.Arrays.copyOf(datos, n);
        }
    }

    /** Desde la masa de la semilla: su agua y lo conectado por debajo del nivel, sin pisar lo ya inundado. */
    private static void inundar(Relleno r, int semilla, short valor, short[] elevacion, byte[] clase, short[] nivel) {
        boolean esAgua = clase[semilla] == 0;
        r.llenar(semilla, j -> nivel[j] == SECO && (elevacion[j] < valor || (esAgua && clase[j] == 0)),
                j -> nivel[j] = valor, null);
    }

    private static boolean marcado(long[] bits, int i) {
        return (bits[i >>> 6] & (1L << (i & 63))) != 0;
    }

    private static void marcar(long[] bits, int i) {
        bits[i >>> 6] |= 1L << (i & 63);
    }

    // ------------------------------------------------------------------ relleno por líneas

    interface Condicion {
        boolean dentro(int i);
    }

    interface Accion {
        void hacer(int i);
    }

    interface Vecinos {
        void ver(int i, int vecino);
    }

    /** Relleno por líneas con 4 vecinos; las filas no se envuelven, las columnas sí si la grilla es global. */
    static final class Relleno {
        private final int ancho, alto;
        private final boolean envolver;
        private int[] pila = new int[1 << 16];
        private int tope;

        Relleno(int ancho, int alto, boolean envolver) {
            this.ancho = ancho;
            this.alto = alto;
            this.envolver = envolver;
        }

        /** @return cuántas muestras se llenaron. {@code vecinos} (puede ser null) ve cada par muestra/vecino. */
        long llenar(int semilla, Condicion dentro, Accion accion, Vecinos vecinos) {
            long cuenta = 0;
            tope = 0;
            empujar(semilla);
            while (tope > 0) {
                int i = pila[--tope];
                if (!dentro.dentro(i)) continue;
                int fila = i / ancho, base = fila * ancho;
                // Hasta dónde llega el tramo a la izquierda (con la vuelta, como mucho la fila entera)
                int l = i - base;
                for (int pasos = 0; pasos < ancho - 1; pasos++) {
                    int c = izquierda(l);
                    if (c < 0 || !dentro.dentro(base + c)) break;
                    l = c;
                }
                // Recorrerlo hacia la derecha; de las filas de al lado se apila el comienzo de cada tramo
                boolean arriba = false, abajo = false;
                int c = l;
                for (int pasos = 0; pasos < ancho && c >= 0; pasos++) {
                    int idx = base + c;
                    if (!dentro.dentro(idx)) break;
                    accion.hacer(idx);
                    cuenta++;
                    if (vecinos != null) verVecino(idx, fila, c, vecinos);
                    if (fila > 0) {
                        boolean d = dentro.dentro(idx - ancho);
                        if (d && !arriba) empujar(idx - ancho);
                        arriba = d;
                    }
                    if (fila < alto - 1) {
                        boolean d = dentro.dentro(idx + ancho);
                        if (d && !abajo) empujar(idx + ancho);
                        abajo = d;
                    }
                    c = derecha(c);
                }
            }
            return cuenta;
        }

        /** Vecinos (4, con la vuelta si corresponde) de la muestra i en {@code salida}; devuelve cuántos. */
        int vecinos(int i, int[] salida) {
            int fila = i / ancho, c = i - fila * ancho, k = 0;
            if (fila > 0) salida[k++] = i - ancho;
            if (fila < alto - 1) salida[k++] = i + ancho;
            int iz = izquierda(c), de = derecha(c);
            if (iz >= 0) salida[k++] = fila * ancho + iz;
            if (de >= 0) salida[k++] = fila * ancho + de;
            return k;
        }

        private int izquierda(int c) {
            return c > 0 ? c - 1 : envolver ? ancho - 1 : -1;
        }

        private int derecha(int c) {
            return c < ancho - 1 ? c + 1 : envolver ? 0 : -1;
        }

        private void verVecino(int idx, int fila, int c, Vecinos vecinos) {
            if (fila > 0) vecinos.ver(idx, idx - ancho);
            if (fila < alto - 1) vecinos.ver(idx, idx + ancho);
            if (c > 0) vecinos.ver(idx, idx - 1);
            else if (envolver) vecinos.ver(idx, idx + ancho - 1);
            if (c < ancho - 1) vecinos.ver(idx, idx + 1);
            else if (envolver) vecinos.ver(idx, idx - ancho + 1);
        }

        private void empujar(int i) {
            if (tope == pila.length) pila = java.util.Arrays.copyOf(pila, pila.length * 2);
            pila[tope++] = i;
        }
    }
}
