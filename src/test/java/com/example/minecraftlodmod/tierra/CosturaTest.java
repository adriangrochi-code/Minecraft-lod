package com.example.minecraftlodmod.tierra;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** H10: el mundo cilíndrico es periódico en x, sin escalón en el antimeridiano. */
class CosturaTest {

    private static LectorLodt lector;
    private static FuenteTierra fuente;

    @BeforeAll
    static void abrir(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("himalaya-bengala-30s.lodt");
        try (var entrada = CosturaTest.class.getResourceAsStream("/tierra/himalaya-bengala-30s.lodt")) {
            Files.copy(entrada, p);
        }
        lector = LectorLodt.abrir(p, 64L << 20);
        fuente = new FuenteTierra(lector);
    }

    @AfterAll
    static void cerrar() throws IOException {
        lector.close();
    }

    @Test
    void circunferenciaMultiploDe16() {
        assertEquals(5_003_776, Costura.circunferencia(8));
        assertEquals(6_671_712, Costura.circunferencia(6));
        assertEquals(5_003_776, new ProyeccionCilindrica(8).periodoX());
        assertEquals(0, new ProyeccionAzimutal(8).periodoX());
        // 1° de longitud sigue siendo 1° de latitud (la vuelta redondeada cambia k en < 1 bloque por vuelta)
        assertEquals(5_003_776 / 360.0, new ProyeccionCilindrica(8).bloquesPorGrado(), 1e-9);
    }

    @Test
    void envolverYSalto() {
        double c = 5_003_776;
        assertEquals(0.5, Costura.envolver(c + 0.5, c));
        assertEquals(-c / 2, Costura.envolver(c / 2, c));
        assertEquals(c / 2 - 0.5, Costura.envolver(-c / 2 - 0.5, c));
        assertEquals(123.5, Costura.envolver(123.5 - 3 * c, c));
        assertEquals(7.5, Costura.envolver(7.5, 0), "sin período no se envuelve");
        assertEquals(0, Costura.salto(c / 2 + 31, c, 32));
        assertEquals(-c, Costura.salto(c / 2 + 33, c, 32));
        assertEquals(c, Costura.salto(-c / 2 - 33, c, 32));
        // Tras saltar queda 32 bloques adentro del otro lado: no rebota
        assertEquals(0, Costura.salto(c / 2 + 33 - c, c, 32));
        assertEquals(0, Costura.salto(1e9, 0, 32));
    }

    @Test
    void alturaPeriodicaBloqueABloque() {
        AlturaTierra a = new AlturaTierra(fuente, new ProyeccionCilindrica(8), 1.0);
        int c = (int) a.periodoX();
        Proyeccion p = a.proyeccion();
        int x0 = (int) p.x(28, 86.9), z0 = (int) p.z(28, 86.9); // Himalaya, con detalle
        for (int dx = 0; dx < 64; dx += 3) {
            for (int dz = 0; dz < 64; dz += 5) {
                int x = x0 + dx, z = z0 + dz;
                int h = a.altura(x, z);
                assertEquals(h, a.altura(x + c, z));
                assertEquals(h, a.altura(x - 2 * c, z));
                assertEquals(a.nivelAguaY(x + 0.5, z + 0.5), a.nivelAguaY(x + c + 0.5, z + 0.5));
                assertEquals(a.claseClima(x + 0.5, z + 0.5), a.claseClima(x - c + 0.5, z + 0.5));
            }
        }
    }

    @Test
    void detalleSinEscalonEnLaCostura() {
        double c = Costura.circunferencia(8);
        Random r = new Random(1);
        for (int i = 0; i < 200; i++) {
            double z = r.nextInt(2_000_000) - 1_000_000 + 0.5;
            double e = 500 + r.nextDouble() * 3000, rango = 2000;
            double antes = DetalleTierra.conDetalle(c / 2 - 0.5, z, e, rango, c);
            double despues = DetalleTierra.conDetalle(-c / 2 + 0.5, z, e, rango, c);
            // a un bloque de distancia: lo que cambia el detalle en un bloque (no un escalón)
            assertTrue(Math.abs(antes - despues) < 12, antes + " vs " + despues);
            // exactamente en la costura, los dos lados coinciden
            assertEquals(DetalleTierra.conDetalle(-c / 2, z, e, rango, c),
                    DetalleTierra.conDetalle(Math.nextDown(c / 2), z, e, rango, c), 1e-3);
            // lejos de la costura, igual que sin período
            assertEquals(DetalleTierra.conDetalle(1000.5, z, e, rango), DetalleTierra.conDetalle(1000.5, z, e, rango, c));
        }
    }

    @Test
    void cuevasPeriodicasYSinEscalon() {
        double c = Costura.circunferencia(8);
        for (int y = 0; y < 200; y += 7) {
            for (int z = 0; z < 400; z += 13) {
                double base = 30 + y;
                assertEquals(CuevasTierra.densidad(1234, y, z, base, c), CuevasTierra.densidad(1234 + c, y, z, base, c));
                assertEquals(CuevasTierra.densidad(c / 2 - 1, y, z, base, c),
                        CuevasTierra.densidad(-c / 2 - 1, y, z, base, c));
                // El bloque de un lado y el del otro: densidades vecinas, no de dos cuevas distintas
                double a = CuevasTierra.densidad(c / 2 - 1, y, z, base, c), b = CuevasTierra.densidad(-c / 2, y, z, base, c);
                assertTrue(Math.abs(a - b) < 6, a + " vs " + b);
            }
        }
    }

    @Test
    void presetsConLaMismaVuelta() throws IOException {
        for (int escala : new int[]{8, 6}) {
            String json;
            try (var entrada = CosturaTest.class.getResourceAsStream(
                    "/data/minecraftlodmod/worldgen/noise_settings/tierra_real_" + escala + ".json")) {
                json = new String(entrada.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            assertTrue(json.contains("\"periodo_x\": " + Costura.circunferencia(escala)), "tierra_real_" + escala);
        }
    }
}
