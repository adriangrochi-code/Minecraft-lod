package com.example.minecraftlodmod.generation;

import com.example.minecraftlodmod.core.SuperVoxel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TerrenoAproximadoTest {

    private static final SuperVoxel PASTO = new SuperVoxel((byte) 90, (byte) 160, (byte) 60, (byte) 0,
            SuperVoxel.Material.SOLIDO, (byte) 0, (short) 9).conLuzHorneada(15);
    private static final SuperVoxel PIEDRA = new SuperVoxel((byte) 120, (byte) 120, (byte) 120, (byte) 0,
            SuperVoxel.Material.SOLIDO, (byte) 0, (short) 1);
    private static final SuperVoxel AGUA = new SuperVoxel((byte) 40, (byte) 70, (byte) 200, (byte) 0,
            SuperVoxel.Material.AGUA, (byte) 0, (short) 80).conLuzHorneada(15);

    private static TerrenoAproximado.Columna[] columnas(int altura) {
        TerrenoAproximado.Columna c = new TerrenoAproximado.Columna(altura, PASTO, PIEDRA);
        return new TerrenoAproximado.Columna[]{c, c, c, c};
    }

    private static SuperVoxel en(SuperVoxel[] g, int x, int y, int z) {
        return g[(x * 2 + y) * 2 + z];
    }

    @Test
    void laSuperficieVaEnElSolidoMasAltoYDebajoElSubsuelo() {
        // Terreno hasta y = 70 (sección 4 = y 64..79): vóxel y 64-71 sólido con superficie.
        SuperVoxel[] s4 = TerrenoAproximado.grillaNivel3(4, columnas(70), AGUA, 63);
        assertEquals(PASTO, en(s4, 0, 0, 0), "64-71: cubre 7 de 8 → sólido, y es el más alto");
        assertEquals(TerrenoAproximado.AIRE, en(s4, 0, 1, 0), "72-79: vacío");
        SuperVoxel[] s3 = TerrenoAproximado.grillaNivel3(3, columnas(70), AGUA, 63);
        assertEquals(PIEDRA, en(s3, 1, 1, 1), "56-63 queda debajo de la superficie");
    }

    @Test
    void bajoElNivelDelMarSinTerrenoEsAgua() {
        // Fondo del mar en y = 40, mar hasta 62: la sección 3 (48..63) es agua.
        SuperVoxel[] s3 = TerrenoAproximado.grillaNivel3(3, columnas(40), AGUA, 63);
        assertEquals(AGUA, en(s3, 0, 0, 0));
        assertEquals(AGUA, en(s3, 0, 1, 0), "56-63: el mar (hasta 62) cubre más de la mitad");
    }

    @Test
    void unaSeccionSoloDeAireNoSeGuarda() {
        assertNull(TerrenoAproximado.grillaNivel3(10, columnas(70), AGUA, 63));
    }

    @Test
    void elNivel4SaleDeReducirElNivel3() {
        SuperVoxel[] s4 = TerrenoAproximado.grillaNivel3(4, columnas(75), AGUA, 63);
        SuperVoxel[] n4 = TerrenoAproximado.grillaNivel4(s4);
        assertNotNull(n4);
        assertEquals(1, n4.length);
        assertEquals(PASTO.idEstado(), n4[0].idEstado(), "La superficie manda en el nivel reducido");
    }

    @Test
    void lasClavesAproximadasNoChocanConLasReales() {
        assertEquals(11, TerrenoAproximado.nivelGuardado(3));
        assertEquals(12, TerrenoAproximado.nivelGuardado(4));
        assertThrows(IllegalArgumentException.class, () -> TerrenoAproximado.nivelGuardado(2));
    }
}
