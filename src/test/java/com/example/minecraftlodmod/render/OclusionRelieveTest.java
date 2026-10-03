package com.example.minecraftlodmod.render;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.IntBinaryOperator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OclusionRelieveTest {

    /** Relieve a partir de una función (columnaX, columnaZ) → suelo. */
    private static OclusionRelieve.Relieve relieve(IntBinaryOperator suelo) {
        int n = OclusionRelieve.COLUMNAS_POR_REGION;
        return new OclusionRelieve.Relieve() {
            @Override
            public float[] suelos(int regionX, int regionZ) {
                float[] s = new float[n * n];
                for (int cx = 0; cx < n; cx++) {
                    for (int cz = 0; cz < n; cz++) {
                        s[cx * n + cz] = suelo.applyAsInt(regionX * n + cx, regionZ * n + cz);
                    }
                }
                return s;
            }
        };
    }

    private static OclusionRelieve.Pieza pieza(double centroX, double centroZ, double lado) {
        return new OclusionRelieve.Pieza(centroX - lado / 2, centroZ - lado / 2, lado);
    }

    @Test
    void unaLlanuraNoOcultaNada() {
        OclusionRelieve.Relieve llano = relieve((x, z) -> 64);
        List<OclusionRelieve.Pieza> piezas = List.of(pieza(300, 0, 64), pieza(1500, 900, 64), pieza(-2000, 0, 512));
        boolean[] ocultas = OclusionRelieve.ocultas(0, 100, 0, piezas, llano, 4096);
        assertArrayEquals(new boolean[piezas.size()], ocultas);
    }

    @Test
    void unCordonMontanosoOcultaLoBajoQueTieneDetras() {
        // Cordón alto (suelo 300) entre x = 256 y 320, en todo z; llanura a 64 en el resto.
        OclusionRelieve.Relieve cordon = relieve((x, z) -> x >= 8 && x < 10 ? 300 : 64);
        // Detrás del cordón: el valle (tope bajo) queda oculto, la montaña gigante no.
        OclusionRelieve.Relieve conPicoLejano = relieve((x, z) -> x >= 8 && x < 10 ? 300 : x >= 40 && x < 42 ? 2000 : 64);
        List<OclusionRelieve.Pieza> piezas = List.of(pieza(900, 0, 64), pieza(-900, 0, 64), pieza(1330, 0, 64));

        boolean[] ocultas = OclusionRelieve.ocultas(0, 80, 0, piezas, cordon, 4096);
        assertTrue(ocultas[0], "El valle detrás del cordón no se ve");
        assertFalse(ocultas[1], "Del otro lado (sin cordón) sí se ve");

        boolean[] conPico = OclusionRelieve.ocultas(0, 80, 0, piezas, conPicoLejano, 4096);
        assertFalse(conPico[2], "Un pico más alto que el cordón asoma y se dibuja");
    }

    @Test
    void bajoTierraNoSeOcultaNada() {
        OclusionRelieve.Relieve cordon = relieve((x, z) -> x >= 8 && x < 10 ? 300 : 64);
        boolean[] ocultas = OclusionRelieve.ocultas(0, 20, 0, List.of(pieza(900, 0, 64)), cordon, 4096);
        assertFalse(ocultas[0], "Con la cámara bajo el suelo (cueva) no se arriesga");
    }

    @Test
    void sinDatosNoSeOcultaNada() {
        OclusionRelieve.Relieve vacio = new OclusionRelieve.Relieve() {
            public float[] suelos(int rx, int rz) { return null; }
        };
        assertFalse(OclusionRelieve.ocultas(0, 80, 0, List.of(pieza(900, 0, 64)), vacio, 4096)[0]);
    }

    @Test
    void lasColumnasQueTapanSoloCuentanLosSectoresQueCubrenEnteros() {
        // Un cuadrado lejano y chico cubre menos de un sector entero: no tapa nada.
        assertTrue(OclusionRelieve.sectoresCubiertos(0, 0, 100000, 0, 32, true) == null);
        // El mismo, como pieza tapada, toca al menos un sector.
        assertTrue(OclusionRelieve.sectoresCubiertos(0, 0, 100000, 0, 32, false) != null);
        // Con la cámara adentro, ninguno.
        assertTrue(OclusionRelieve.sectoresCubiertos(10, 10, 0, 0, 32, false) == null);
    }

    @Test
    void unValleSeOcultaAunqueEnSuRegionHayaUnPicoAlCostado() {
        // Cordón en x 256-320; detrás, un valle a 64 y en la misma región de 512 un pico a 1500 lejos de él (z alto).
        OclusionRelieve.Relieve r = relieve((x, z) -> x >= 8 && x < 10 ? 300 : (x >= 28 && x < 30 && z >= 12) ? 1500 : 64);
        boolean[] ocultas = OclusionRelieve.ocultas(0, 80, 0, List.of(pieza(700, 430, 64)), r, 4096);
        assertTrue(ocultas[0], "El pico está en otras columnas: no destapa el valle");
    }
}
