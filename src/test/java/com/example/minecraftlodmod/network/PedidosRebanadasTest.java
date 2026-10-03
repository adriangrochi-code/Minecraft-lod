package com.example.minecraftlodmod.network;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PedidosRebanadasTest {

    private static final RebanadaId A = new RebanadaId(0, 0, 0), B = new RebanadaId(1, 0, 0);

    @Test
    void loQueFaltaSePideUnaVezEnOrden() {
        PedidosRebanadas p = new PedidosRebanadas();
        p.querer(A, 0);
        p.querer(B, 0);
        p.querer(A, 1);
        assertEquals(List.of(A, B), p.aMandar(2, 10));
        assertEquals(List.of(), p.aMandar(3, 10));
        assertEquals(2, p.enVuelo());
    }

    @Test
    void noPasaDelTopeEnVuelo() {
        PedidosRebanadas p = new PedidosRebanadas();
        for (int i = 0; i < PedidosRebanadas.MAX_EN_VUELO + 5; i++) {
            p.querer(new RebanadaId(i, 0, 0), 0);
        }
        assertEquals(PedidosRebanadas.MAX_EN_VUELO, p.aMandar(1, 1000).size());
        p.recibida(new RebanadaId(0, 0, 0), 2, 0);
        assertEquals(1, p.aMandar(3, 1000).size(), "al llegar una, sale otra");
    }

    @Test
    void recibidaNoSeVuelveAPedirHastaQueVence() {
        PedidosRebanadas p = new PedidosRebanadas();
        p.querer(A, 0);
        p.aMandar(0, 10);
        p.recibida(A, 10, 77);
        p.querer(A, 20);
        assertTrue(p.aMandar(21, 10).isEmpty());
        p.querer(A, 10 + PedidosRebanadas.VIGENCIA_NANOS + 1);
        assertEquals(List.of(A), p.aMandar(10 + PedidosRebanadas.VIGENCIA_NANOS + 2, 10));
        assertEquals(77, p.huella(A), "se vuelve a pedir con la huella que ya tiene");
    }

    @Test
    void pedidaSinRespuestaSeDaPorPerdida() {
        PedidosRebanadas p = new PedidosRebanadas();
        p.querer(A, 0);
        p.aMandar(0, 10);
        p.querer(A, PedidosRebanadas.ESPERA_MAXIMA_NANOS + 1);
        assertEquals(List.of(A), p.aMandar(PedidosRebanadas.ESPERA_MAXIMA_NANOS + 2, 10));
        assertEquals(1, p.enVuelo());
    }

    @Test
    void lasHuellasSonPorDimensionYSobrevivenAlDisco() throws Exception {
        PedidosRebanadas p = new PedidosRebanadas();
        p.recibida(A, 0, 5);
        p.dimension((byte) 1);
        assertEquals(0, p.huella(A), "en el Nether la misma región es otra");
        p.recibida(A, 0, 9);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        p.escribirHuellas(new DataOutputStream(bytes));

        PedidosRebanadas otra = new PedidosRebanadas();
        otra.leerHuellas(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
        assertEquals(5, otra.huella(A));
        otra.dimension((byte) 1);
        assertEquals(9, otra.huella(A));
        otra.reiniciar();
        assertEquals(9, otra.huella(A), "reiniciar (cambio de dimensión) no olvida las huellas");
    }
}
