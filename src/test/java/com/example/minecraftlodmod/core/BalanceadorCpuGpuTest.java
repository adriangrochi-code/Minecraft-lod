package com.example.minecraftlodmod.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BalanceadorCpuGpuTest {

    private static final double OBJETIVO = 25; // 40 fps
    private static final double SIN_TIRONES = 30;

    @Test
    void diagnosticaSegunCuantoTrabajaLaGpu() {
        assertEquals(BalanceadorCpuGpu.Limite.GPU, BalanceadorCpuGpu.diagnosticar(30, 28));
        assertEquals(BalanceadorCpuGpu.Limite.CPU, BalanceadorCpuGpu.diagnosticar(30, 12));
        assertEquals(BalanceadorCpuGpu.Limite.DESCONOCIDO, BalanceadorCpuGpu.diagnosticar(30, Double.NaN));
    }

    @Test
    void conLaGpuAlLimitePrimeroPasaTrabajoAlCpuSinTocarLoVisible() {
        BalanceadorCpuGpu b = new BalanceadorCpuGpu(2.5, 160, 2);
        double antes = b.distanciaUnBuffer();
        assertTrue(b.ajustar(35, SIN_TIRONES, 34, OBJETIVO, false));
        assertTrue(b.distanciaUnBuffer() < antes, "más buffers por dirección: la GPU se saltea caras de espaldas");
        assertEquals(2.5, b.umbralPx());
        assertEquals(160, b.radioChunks());
        assertEquals(2, b.limiteConcurrencia(), "la generación no es el problema");
    }

    @Test
    void conLaGpuAlLimiteYEscaladoBajaLaResolucionAntesQueElDetalle() {
        BalanceadorCpuGpu b = new BalanceadorCpuGpu(2.5, 160, 2);
        for (int i = 0; i < 3; i++) {
            b.ajustar(35, SIN_TIRONES, 34, OBJETIVO, true); // agota el agrupado de caras
        }
        assertEquals(BalanceadorCpuGpu.UN_BUFFER_MIN, b.distanciaUnBuffer());
        b.ajustar(35, SIN_TIRONES, 34, OBJETIVO, true);
        assertEquals(5, b.reduccionEscala());
        assertEquals(2.5, b.umbralPx());
    }

    @Test
    void conElCpuAlLimitePrimeroBajaLaGeneracionYDespuesAgrupaCaras() {
        BalanceadorCpuGpu b = new BalanceadorCpuGpu(2.5, 160, 2);
        b.ajustar(35, SIN_TIRONES, 10, OBJETIVO, false);
        assertEquals(1, b.limiteConcurrencia());
        double antes = b.distanciaUnBuffer();
        b.ajustar(35, SIN_TIRONES, 10, OBJETIVO, false);
        assertTrue(b.distanciaUnBuffer() > antes, "menos llamadas de dibujo");
        assertEquals(2.5, b.umbralPx());
    }

    @Test
    void unCambioDeLadoSeConfirmaAntesDeMoverPerillas() {
        BalanceadorCpuGpu b = new BalanceadorCpuGpu(2.5, 160, 1);
        b.ajustar(35, SIN_TIRONES, 10, OBJETIVO, false); // CPU: agrupa más
        double agrupado = b.distanciaUnBuffer();
        assertFalse(b.ajustar(35, SIN_TIRONES, 34, OBJETIVO, false), "pasó a GPU: se espera un ciclo");
        assertEquals(agrupado, b.distanciaUnBuffer());
        assertTrue(b.ajustar(35, SIN_TIRONES, 34, OBJETIVO, false), "sigue en GPU: ahora sí");
        assertTrue(b.distanciaUnBuffer() < agrupado);
    }

    @Test
    void sinMedicionDeGpuUsaElOrdenGenerico() {
        BalanceadorCpuGpu b = new BalanceadorCpuGpu(2.5, 160, 1);
        b.ajustar(35, SIN_TIRONES, Double.NaN, OBJETIVO, false);
        assertTrue(b.umbralPx() > 2.5);
        assertEquals(BalanceadorCpuGpu.UN_BUFFER_INICIAL, b.distanciaUnBuffer());
    }

    @Test
    void conTironesBajaLaGeneracionAunqueElPromedioAlcance() {
        BalanceadorCpuGpu b = new BalanceadorCpuGpu(2.5, 160, 3);
        assertTrue(b.ajustar(20, 90, 10, OBJETIVO, false));
        assertTrue(b.conTirones());
        assertEquals(2, b.limiteConcurrencia());
        assertEquals(2.5, b.umbralPx());
    }

    @Test
    void recuperaLoVisibleSoloTrasVariosCiclosConMargen() {
        BalanceadorCpuGpu b = new BalanceadorCpuGpu(2.5, 160, 1);
        for (int i = 0; i < 40; i++) {
            b.ajustar(60, 70, Double.NaN, OBJETIVO, false);
        }
        assertTrue(b.enPisoAbsoluto());
        int radio = b.radioChunks();
        assertFalse(b.ajustar(10, 15, Double.NaN, OBJETIVO, false), "un solo ciclo con margen no alcanza");
        assertTrue(b.ajustar(10, 15, Double.NaN, OBJETIVO, false));
        assertTrue(b.radioChunks() > radio, "primero vuelve el radio");
    }

    @Test
    void elDetalleNoPasaDeSeisPixeles() {
        BalanceadorCpuGpu b = new BalanceadorCpuGpu(4, 96, 1);
        for (int i = 0; i < 40; i++) {
            b.ajustar(60, 70, Double.NaN, OBJETIVO, false);
        }
        assertEquals(6, b.umbralPx());
        BalanceadorCpuGpu minimo = new BalanceadorCpuGpu(6, 32, 1);
        for (int i = 0; i < 40; i++) {
            minimo.ajustar(60, 70, Double.NaN, OBJETIVO, false);
        }
        assertEquals(6, minimo.umbralPx(), "el preset Mínimo ya está en el techo: baja el radio");
        assertTrue(minimo.radioChunks() < 32);
    }

    @Test
    void nuncaPasaDeLosTechosDelPreset() {
        BalanceadorCpuGpu b = new BalanceadorCpuGpu(2.5, 160, 2);
        for (int i = 0; i < 50; i++) {
            b.ajustar(5, 6, 4, OBJETIVO, true);
        }
        assertEquals(2.5, b.umbralPx());
        assertEquals(160, b.radioChunks());
        assertEquals(2, b.limiteConcurrencia());
        assertEquals(0, b.reduccionEscala());
    }

    @Test
    void conMargenDeSobraSumaDetalleExtraHastaElMinimo() {
        BalanceadorCpuGpu b = new BalanceadorCpuGpu(2.5, 160, 2);
        for (int i = 0; i < 50; i++) {
            b.ajustar(5, 6, 4, OBJETIVO, false);
        }
        assertEquals(BalanceadorCpuGpu.DETALLE_EXTRA_MIN, b.detalleExtra(), "con todo en el preset, sigue sumando detalle");
        assertEquals(2.5, b.umbralPx(), "el umbral del preset no se toca: el extra va aparte");
    }

    @Test
    void elDetalleExtraEsLoPrimeroQueSeSacaYTardaEnVolver() {
        BalanceadorCpuGpu b = new BalanceadorCpuGpu(2.5, 160, 2);
        for (int i = 0; i < 50; i++) {
            b.ajustar(5, 6, 4, OBJETIVO, false);
        }
        double conExtra = b.detalleExtra();
        double unBuffer = b.distanciaUnBuffer();
        assertTrue(b.ajustar(35, SIN_TIRONES, 34, OBJETIVO, false));
        assertTrue(b.detalleExtra() > conExtra, "se saca detalle extra");
        assertEquals(unBuffer, b.distanciaUnBuffer(), "antes que cualquier otra perilla");
        double despues = b.detalleExtra();
        for (int i = 0; i < BalanceadorCpuGpu.CICLOS_BLOQUEO_EXTRA - 2; i++) {
            b.ajustar(5, 6, 4, OBJETIVO, false);
        }
        assertEquals(despues, b.detalleExtra(), "no vuelve enseguida: no oscila");
        for (int i = 0; i < 6; i++) {
            b.ajustar(5, 6, 4, OBJETIVO, false);
        }
        assertTrue(b.detalleExtra() < despues, "pasado el bloqueo, vuelve");
    }

    @Test
    void sinMargenNoHayDetalleExtra() {
        BalanceadorCpuGpu b = new BalanceadorCpuGpu(2.5, 160, 2);
        for (int i = 0; i < 50; i++) {
            b.ajustar(22, 26, 20, OBJETIVO, false); // dentro del objetivo pero sin el 20% de margen
        }
        assertEquals(1, b.detalleExtra());
    }
}
