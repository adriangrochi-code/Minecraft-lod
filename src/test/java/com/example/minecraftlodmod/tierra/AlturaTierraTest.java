package com.example.minecraftlodmod.tierra;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contra el recorte real de ETOPO 2022 30″ (20–30° N, 80–90° E: Himalaya,
 * llanura del Ganges y golfo de Bengala) en {@code src/test/resources/tierra/}.
 * Valores esperados leídos de la grilla oficial de 30″ (OPeNDAP de NCEI); el
 * recorte se armó desde la tesela de 15″ promediando 2×2, que es como NOAA
 * arma la de 30″ (coincide al metro).
 */
class AlturaTierraTest {

    private static final double PASO = 1 / 120.0;
    private static LectorLodt lector;
    private static FuenteTierra fuente;

    @BeforeAll
    static void abrir() throws IOException, URISyntaxException {
        Path p = Path.of(AlturaTierraTest.class.getResource("/tierra/himalaya-bengala-30s.lodt").toURI());
        lector = LectorLodt.abrir(p, 64L << 20);
        fuente = new FuenteTierra(lector);
    }

    @AfterAll
    static void cerrar() throws IOException {
        lector.close();
    }

    /** Centro de la muestra (col, fila) del recorte. */
    private static double lat(int fila) {
        return 30 - (fila + 0.5) * PASO;
    }

    private static double lon(int col) {
        return 80 + (col + 0.5) * PASO;
    }

    @Test
    void puntosConocidosDeLaGrillaOficial() {
        // Everest: la muestra de 30″ más alta junto a la cumbre (la grilla promedia ~1 km)
        assertEquals(8354, fuente.elevacion(lat(241), lon(831)), 0.5);
        assertEquals(8040, fuente.elevacion(lat(242), lon(831)), 0.5); // al sur
        assertEquals(8201, fuente.elevacion(lat(240), lon(831)), 0.5); // al norte
        // Golfo de Bengala (21 N, 89 E) y llanura del Ganges
        assertEquals(-88, fuente.elevacion(lat(1080), lon(1080)), 0.5);
        assertEquals(158, fuente.elevacion(lat(600), lon(300)), 0.5);
        assertEquals(8354, lector.cabecera.elevMaxima());
    }

    @Test
    void alturaEnBloquesA1en8YA1en6() {
        ProyeccionCilindrica p8 = new ProyeccionCilindrica(8);
        AlturaTierra a8 = new AlturaTierra(fuente, p8, 1.0);
        double x = p8.x(lat(241), lon(831)), z = p8.z(lat(241), lon(831));
        assertEquals(63 + 8354 / 8.0, a8.alturaExacta(x, z), 0.1);
        assertEquals(1107, a8.yCima()); // ceil(63 + 8354/8) - 1
        AlturaTierra a6 = new AlturaTierra(fuente, new ProyeccionCilindrica(6), 1.0);
        assertEquals(1455, a6.yCima()); // ceil(63 + 8354/6) - 1
        // Mar: bajo el nivel del mar en bloques
        assertTrue(a8.altura((int) p8.x(lat(1080), lon(1080)), (int) p8.z(lat(1080), lon(1080))) < 63);
    }

    @Test
    void elFondoMasHondoApoyaEnElLechoDeRoca() {
        for (double escala : new double[]{8, 6}) {
            ProyeccionCilindrica p = new ProyeccionCilindrica(escala);
            AlturaTierra a = new AlturaTierra(fuente, p, 1.0);
            int fondo = a.yFondoFosa();
            int minY = a.minYDimension();
            assertEquals(0, Math.floorMod(minY, 16));
            assertTrue(minY <= fondo && fondo - minY < 16, "piso " + minY + ", fondo " + fondo);
            assertTrue(a.esLechoDeRoca(fondo) && !a.esLechoDeRoca(fondo + 1));
            // Ninguna columna baja del fondo (la bicúbica se recorta al rango de los datos)
            int masHonda = Integer.MAX_VALUE;
            for (int f = 0; f < 1200; f += 3) {
                for (int c = 0; c < 1200; c += 3) {
                    masHonda = Math.min(masHonda, a.altura((int) Math.floor(p.x(lat(f), lon(c))), (int) Math.floor(p.z(lat(f), lon(c)))));
                }
            }
            assertTrue(masHonda >= fondo, "columna en " + masHonda + " bajo el fondo " + fondo);
        }
    }

    @Test
    void fosaDeLasMarianasConEtopo30() {
        // Mínimo de la grilla global de 30″ (OPeNDAP, 11,354 N 142,429 E): -10 570,65 m → -10 571
        int fondo8 = AlturaTierra.yBloque(-10571, 1 / 8.0);
        assertEquals(-1259, fondo8);
        assertEquals(-1264, AlturaTierra.minYDimension(fondo8));
        int fondo6 = AlturaTierra.yBloque(-10571, 1 / 6.0);
        assertEquals(-1699, fondo6);
        assertEquals(-1712, AlturaTierra.minYDimension(fondo6));
        assertEquals(-16, AlturaTierra.minYDimension(-16));
        assertEquals(-32, AlturaTierra.minYDimension(-17));
    }

    @Test
    void microbenchmarkPorColumna() {
        AlturaTierra a = new AlturaTierra(fuente, new ProyeccionCilindrica(8), 1.0);
        ProyeccionCilindrica p = new ProyeccionCilindrica(8);
        int x0 = (int) p.x(25, 82), z0 = (int) p.z(25, 82);
        long suma = 0;
        for (int k = 0; k < 3; k++) { // calentamiento
            for (int dz = 0; dz < 512; dz++) for (int dx = 0; dx < 512; dx++) suma += a.altura(x0 + dx, z0 + dz);
        }
        long inicio = System.nanoTime();
        int n = 0;
        for (int dz = 0; dz < 1024; dz++) {
            for (int dx = 0; dx < 1024; dx++) {
                suma += a.altura(x0 + dx * 3, z0 + dz * 3);
                n++;
            }
        }
        double us = (System.nanoTime() - inicio) / 1e3 / n;
        System.out.printf(Locale.ROOT, "[tierra] altura por columna (bicúbica, tesela en caché): %.3f µs (%d)%n", us, suma & 1);
        assertTrue(us < 50, "medición absurda: " + us + " µs");
    }
}
