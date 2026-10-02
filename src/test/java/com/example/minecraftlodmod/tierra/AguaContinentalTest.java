package com.example.minecraftlodmod.tierra;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AguaContinentalTest {

    private static final short S = AguaContinental.SECO;

    /**
     * Grilla de 12×6 (fila 0 al norte). Leyenda: 'o' océano (clase 0, -100 m),
     * 'L' lago (clase 0, -40 m), 'd' tierra baja (-50 m, clase 1),
     * 'p' pólder junto al mar (-5 m, clase 1), números = tierra a esa altura en metros×10.
     */
    private static final String[] MAPA = {
            "oooo55555555",
            "ooop5d5LL555",
            "ooop5d5L3555",
            "oooo55555555",
            "oooo59999999",
            "oooo5dd99999",
    };

    private static short[] elev;
    private static byte[] clase;

    static void armar(String[] mapa) {
        int w = mapa[0].length(), h = mapa.length;
        elev = new short[w * h];
        clase = new byte[w * h];
        for (int f = 0; f < h; f++) {
            for (int c = 0; c < w; c++) {
                char ch = mapa[f].charAt(c);
                int i = f * w + c;
                switch (ch) {
                    case 'o' -> { elev[i] = -100; clase[i] = 0; }
                    case 'L' -> { elev[i] = -40; clase[i] = 0; }
                    case 'd' -> { elev[i] = -50; clase[i] = 1; }
                    case 'p' -> { elev[i] = -5; clase[i] = 1; }
                    default -> { elev[i] = (short) ((ch - '0') * 10); clase[i] = 1; }
                }
            }
        }
    }

    @Test
    void oceanoLagoPolderYDepresionSeca() {
        armar(MAPA);
        int w = 12;
        short[] n = AguaContinental.calcular(elev, clase, w, 6, false);
        assertEquals(0, n[0], "océano");
        assertEquals(0, n[1 * w + 3], "pólder junto al mar: se inunda");
        assertEquals(S, n[1 * w + 5], "depresión sin conexión: seca");
        assertEquals(S, n[5 * w + 5], "otra depresión seca");
        // El lago: la tierra firme a 2 muestras está a 50 m (mediana): nivel 50, y la orilla de 30 m
        // (fila 2, col 8), más baja que el nivel y pegada al lago, se inunda
        assertEquals(50, n[1 * w + 7]);
        assertEquals(50, n[1 * w + 8]);
        assertEquals(50, n[2 * w + 7]);
        assertEquals(50, n[2 * w + 8]);
        assertEquals(S, n[0 * w + 4], "tierra alta: seca");
    }

    @Test
    void envuelveLaLongitud() {
        // El océano cruza el borde izquierdo/derecho: con envolver es una sola masa
        armar(new String[]{
                "oo55oo",
                "o5LL5o",
                "oo55oo"});
        short[] n = AguaContinental.calcular(elev, clase, 6, 3, true);
        assertEquals(0, n[0]);
        assertEquals(0, n[5], "misma masa que la columna 0 por la vuelta");
        assertEquals(50, n[6 + 2], "lago con orilla de 50 m");
        short[] sinVuelta = AguaContinental.calcular(elev, clase, 6, 3, false);
        // Sin vuelta hay dos masas de océano de igual tamaño: una es "el océano", la otra un "lago" (nivel de su orilla)
        assertEquals(0, Math.min(sinVuelta[0], sinVuelta[5]));
    }

    @Test
    void lagunaChicaEnUnValleSecoNoLoInunda() {
        // Valle de 60×40 a -50 m rodeado de cerros de 900 m, con una laguna de 1 muestra (Köppen sin clima)
        // en el medio: la mediana de los cerros (900) inundaría el valle entero (2400 muestras > 1 + 500).
        int w = 64, h = 44;
        elev = new short[w * h];
        clase = new byte[w * h];
        java.util.Arrays.fill(elev, (short) 900);
        java.util.Arrays.fill(clase, (byte) 4);
        for (int f = 2; f < 42; f++) for (int c = 2; c < 62; c++) elev[f * w + c] = -50;
        // océano en la esquina para que la laguna no sea "el océano"
        for (int f = 0; f < 44; f++) for (int c = 0; c < 2; c++) {
            elev[f * w + c] = -2000;
            clase[f * w + c] = 0;
        }
        for (int f = 0; f < 44; f++) elev[f * w + 2] = 900; // el valle no toca el mar
        int laguna = 20 * w + 30;
        clase[laguna] = 0;
        elev[laguna] = -60;
        short[] n = AguaContinental.calcular(elev, clase, w, h, false);
        assertEquals(0, n[0], "océano");
        assertEquals(n[laguna], n[laguna]);
        assertEquals(true, n[laguna] < 900, "nivel acotado: " + n[laguna]);
        int inundadas = 0;
        for (int i = 0; i < w * h; i++) if (n[i] != S && n[i] != 0) inundadas++;
        assertEquals(true, inundadas <= 1 + AguaContinental.EXTRA_MINIMO + 1, "inundadas " + inundadas);
    }

    @Test
    void sinAguaTodoSeco() {
        armar(new String[]{"5d5", "555"});
        short[] n = AguaContinental.calcular(elev, clase, 3, 2, false);
        for (short v : n) assertEquals(S, v);
    }

    @Test
    void oceanoGrandeNoDesbordaLaPila() {
        // 2000×1000 de océano: el relleno por líneas apila tramos, no muestras
        int w = 2000, h = 1000;
        elev = new short[w * h];
        clase = new byte[w * h];
        java.util.Arrays.fill(elev, (short) -100);
        short[] n = AguaContinental.calcular(elev, clase, w, h, true);
        assertEquals(0, n[w * h - 1]);
        assertEquals(0, n[w * h / 2 + 7]);
    }
}
