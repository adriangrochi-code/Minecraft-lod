package com.example.minecraftlodmod.tierra;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.IntBinaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FuenteTierraTest {

    @TempDir
    Path dir;

    private LectorLodt grilla(String nombre, int ancho, int alto, double lon0, double lat0, double paso, IntBinaryOperator v) throws IOException {
        Path p = dir.resolve(nombre);
        EscritorLodt.escribir(p, new FormatoLodt.Cabecera(ancho, alto, 16, lon0, lat0, paso), (c0, f0, a, h, e, b, nv) -> {
            for (int f = 0; f < h; f++) {
                for (int c = 0; c < a; c++) e[f * a + c] = (short) v.applyAsInt(c0 + c, f0 + f);
            }
        });
        return LectorLodt.abrir(p, 1 << 22);
    }

    @Test
    void pesosSumanUno() {
        for (double t = 0; t < 1; t += 0.05) {
            assertEquals(1.0, FuenteTierra.pesoA(t) + FuenteTierra.pesoB(t) + FuenteTierra.pesoC(t) + FuenteTierra.pesoD(t), 1e-12);
        }
    }

    @Test
    void pasaPorLasMuestrasYReproduceUnPlanoExacto() throws IOException {
        // plano: elevación = 3·col - 2·fila; Catmull-Rom reproduce funciones lineales
        try (LectorLodt l = grilla("p.lodt", 50, 40, 10.5, 49.5, 1.0, (c, f) -> 3 * c - 2 * f)) {
            FuenteTierra fuente = new FuenteTierra(l);
            for (int f = 0; f < 40; f++) {
                for (int c = 0; c < 50; c++) {
                    assertEquals(3 * c - 2 * f, fuente.elevacion(49.5 - f, 10.5 + c), 1e-9);
                }
            }
            // entre muestras, lejos del borde (en el borde se repite la muestra)
            for (double lat = 47.2; lat > 13; lat -= 1.37) {
                for (double lon = 12.3; lon < 57; lon += 0.91) {
                    double col = lon - 10.5, fila = 49.5 - lat;
                    assertEquals(3 * col - 2 * fila, fuente.elevacion(lat, lon), 1e-9, lat + ", " + lon);
                }
            }
        }
    }

    @Test
    void caminoRapidoYLentoDanLoMismo() throws IOException {
        // Teselas de 16: los puntos cerca de los bordes de tesela van por el camino lento
        IntBinaryOperator v = (c, f) -> (int) (1000 * Math.sin(c * 0.37) * Math.cos(f * 0.23));
        try (LectorLodt l = grilla("r.lodt", 64, 64, 0.5, 63.5, 1.0, v)) {
            FuenteTierra fuente = new FuenteTierra(l);
            for (double fila = 1.0; fila < 62; fila += 0.25) {
                for (double col = 1.0; col < 62; col += 0.25) {
                    double esperado = 0;
                    int i = (int) Math.floor(col), j = (int) Math.floor(fila);
                    double tu = col - i, tv = fila - j;
                    double[] pu = {FuenteTierra.pesoA(tu), FuenteTierra.pesoB(tu), FuenteTierra.pesoC(tu), FuenteTierra.pesoD(tu)};
                    double[] pv = {FuenteTierra.pesoA(tv), FuenteTierra.pesoB(tv), FuenteTierra.pesoC(tv), FuenteTierra.pesoD(tv)};
                    for (int dj = 0; dj < 4; dj++) {
                        for (int di = 0; di < 4; di++) {
                            int cc = Math.clamp(i - 1 + di, 0, 63), ff = Math.clamp(j - 1 + dj, 0, 63);
                            esperado += pu[di] * pv[dj] * (short) v.applyAsInt(cc, ff);
                        }
                    }
                    assertEquals(esperado, fuente.elevacion(63.5 - fila, 0.5 + col), 1e-6);
                }
            }
        }
    }

    @Test
    void grillaGlobalEnvuelveLaLongitud() throws IOException {
        // 36 columnas de 10°: global; valor = columna × 100
        try (LectorLodt l = grilla("g.lodt", 36, 18, -175, 85, 10, (c, f) -> c * 100)) {
            FuenteTierra fuente = new FuenteTierra(l);
            assertEquals(0, fuente.elevacion(5, -175), 1e-9);
            assertEquals(0, fuente.elevacion(5, 185), 1e-9, "la vuelta completa cae en la misma muestra");
            assertEquals(3500, fuente.elevacion(5, 175), 1e-9);
            // Justo en la costura (180°) la bicúbica mezcla la columna 35 con la 0
            double costura = fuente.elevacion(5, 180);
            assertEquals(costura, fuente.elevacion(5, -180), 1e-9);
            assertEquals(1750, costura, 1e-9, "punto medio simétrico de 3500 y 0");
        }
    }
}
