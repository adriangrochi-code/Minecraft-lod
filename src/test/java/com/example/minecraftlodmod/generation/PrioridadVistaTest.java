package com.example.minecraftlodmod.generation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrioridadVistaTest {

    @Test
    void loQueSeMiraVaAntesQueLoDeAtrasAunqueEsteMasLejos() {
        // Mirando a +X: un punto a 500 bloques adelante antes que uno a 300 atrás.
        assertTrue(PrioridadVista.costo(500, 0, 1, 0) < PrioridadVista.costo(-300, 0, 1, 0));
        // El margen (a 90°) queda entre medio.
        double adelante = PrioridadVista.costo(300, 0, 1, 0), costado = PrioridadVista.costo(0, 300, 1, 0),
                atras = PrioridadVista.costo(-300, 0, 1, 0);
        assertTrue(adelante < costado && costado < atras);
    }

    @Test
    void cercaYSinDireccionTodoCuentaComoVista() {
        assertEquals(PrioridadVista.costo(0, 30, 0, 0), PrioridadVista.costo(0, -30, 1, 0), 1e-9,
                "A 30 bloques no importa la dirección");
        assertEquals(PrioridadVista.costo(0, 500, 0, 0), 500.0 * 500, 1e-6, "Sin mirada: solo distancia");
    }

    @Test
    void dentroDeLaAperturaDada() {
        assertTrue(PrioridadVista.dentro(1000, 100, 1, 0, Math.toRadians(10)));
        assertFalse(PrioridadVista.dentro(1000, 400, 1, 0, Math.toRadians(10)));
        assertTrue(PrioridadVista.dentro(10, 40, 1, 0, Math.toRadians(10)), "Muy cerca siempre dentro");
    }
}
