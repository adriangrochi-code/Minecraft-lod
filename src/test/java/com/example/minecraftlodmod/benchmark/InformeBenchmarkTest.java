package com.example.minecraftlodmod.benchmark;

import com.example.minecraftlodmod.config.ParametrosCalidad;
import com.example.minecraftlodmod.config.QualityPreset;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InformeBenchmarkTest {

    @Test
    void elInformeTraeSistemaPuntosYResultado() {
        EscalonesCalibracion tabla = EscalonesCalibracion.soloMedir(ParametrosCalidad.de(QualityPreset.MEDIO));
        CalibradorBenchmark c = new CalibradorBenchmark(tabla, new CalibradorBenchmark.Ventana[]{
                new CalibradorBenchmark.Ventana(0, 0, 100, true), new CalibradorBenchmark.Ventana(0, 0, 100, false)});
        while (c.registrarFrame(10) != CalibradorBenchmark.Evento.TERMINADO) {
        }
        Map<String, String> sistema = new LinkedHashMap<>();
        sistema.put("GPU", "Radeon R7");
        String texto = InformeBenchmark.armar("medición", sistema, "preset=MEDIO", List.of("llanura", "vuelo"),
                new boolean[]{true, false}, c.resultado(), c.escalones(), c.umbralMs(), c.umbralTironMs(), true);
        assertTrue(texto.contains("Radeon R7"));
        assertTrue(texto.contains("*llanura"), "Los puntos que cuentan van marcados");
        assertTrue(texto.contains(" vuelo"));
        assertTrue(texto.contains("PASA"));
        assertTrue(texto.contains("alcanza el objetivo"));
        assertFalse(texto.contains("(!)"), "Sin puntos medidos a medio armar, sin la nota");
    }
}
