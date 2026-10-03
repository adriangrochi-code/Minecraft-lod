package com.example.minecraftlodmod.render;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RayosVisibilidadTest {

    private static final RayosVisibilidad.Opacidad VACIO = (x, y, z) -> false;

    /** Pared opaca en x = 5, de y 0..9 y z -10..10. */
    private static final RayosVisibilidad.Opacidad PARED = (x, y, z) -> x == 5 && y >= 0 && y <= 9 && z >= -10 && z <= 10;

    private static boolean ver(double camX, double camY, double camZ, double x, double y, double z,
                               RayosVisibilidad.Opacidad o) {
        return RayosVisibilidad.visible(camX, camY, camZ, x, y, z, x + 0.6, y + 1.8, z + 0.6, o, 128);
    }

    @Test
    void sinBloquesSeVe() {
        assertTrue(ver(0.5, 1.5, 0.5, 10.2, 1, 0.2, VACIO));
    }

    @Test
    void detrasDeUnaParedNoSeVe() {
        assertFalse(ver(0.5, 1.5, 0.5, 10.2, 1, 0.2, PARED));
    }

    @Test
    void asomandoSobreLaParedSeVe() {
        // La pared llega hasta y = 9 (arriba en 10); la entidad está de 9.5 a 11.3.
        assertTrue(ver(0.5, 12.5, 0.5, 10.2, 9.5, 0.2, PARED));
    }

    @Test
    void alCostadoDeLaParedSeVe() {
        assertTrue(ver(0.5, 1.5, 0.5, 10.2, 1, 30.2, PARED));
    }

    @Test
    void camaraDentroDeLaCajaSeVe() {
        assertTrue(RayosVisibilidad.visible(1, 1, 1, 0, 0, 0, 2, 2, 2, (x, y, z) -> true, 128));
    }

    @Test
    void masLejosQueElMaximoSeDaPorVisible() {
        assertTrue(RayosVisibilidad.visible(0.5, 1.5, 0.5, 200, 1, 0, 200.6, 2.8, 0.6, PARED, 64));
    }

    @Test
    void elBloqueDeLaCamaraYLosDeLaCajaNoTapan() {
        Set<Long> opacos = new HashSet<>();
        opacos.add(clave(0, 1, 0)); // donde está la cámara
        opacos.add(clave(10, 1, 0)); // donde está la entidad (metida en un bloque)
        opacos.add(clave(10, 2, 0));
        assertTrue(ver(0.5, 1.5, 0.5, 10.2, 1, 0.2, (x, y, z) -> opacos.contains(clave(x, y, z))));
    }

    @Test
    void rayoHaciaAtrasYAbajoTambienSeCorta() {
        assertFalse(ver(10.5, 3.5, 0.5, 0.2, 1, 0.2, PARED));
    }

    private static long clave(int x, int y, int z) {
        return ((long) x << 40) ^ ((long) y << 20) ^ z;
    }
}
