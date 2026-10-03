package com.example.minecraftlodmod.render;

import com.example.minecraftlodmod.config.ModoEscalado;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PruebaEscaladoTest {

    /** Simula cuadros: {@code msCon} cuando se escala, {@code msSin} cuando no; devuelve el reloj. */
    private static long simular(PruebaEscalado p, long reloj, double msCon, double msSin, double segundos) {
        long fin = reloj + (long) (segundos * 1e9);
        boolean usar = p.usar(ModoEscalado.FSR1, reloj, true);
        while (reloj < fin) {
            reloj += (long) ((usar ? msCon : msSin) * 1e6);
            usar = p.usar(ModoEscalado.FSR1, reloj, true);
        }
        return reloj;
    }

    @Test
    void siElEscaladoEsMasLentoSeApagaSolo() {
        PruebaEscalado p = new PruebaEscalado();
        simular(p, 1, 20, 16, 8); // limitado por CPU: escalar cuesta 4 ms
        assertFalse(p.conviene());
        assertFalse(p.usar(ModoEscalado.FSR1, 9_000_000_000L, true));
    }

    @Test
    void siGanaQuedaPrendido() {
        PruebaEscalado p = new PruebaEscalado();
        simular(p, 1, 10, 16, 8); // limitado por la GPU: la mitad de píxeles gana
        assertTrue(p.conviene());
    }

    @Test
    void enElEmpateQuedaPrendido() {
        // Límite del procesador, tope de FPS o vsync: con y sin escalado dan casi lo mismo.
        // El jugador lo pidió y la GPU trabaja menos: queda (antes se apagaba y "no se activaba").
        PruebaEscalado p = new PruebaEscalado();
        simular(p, 1, 16.3, 16, 8); // 2% más lento: dentro del margen
        assertTrue(p.conviene());
    }

    @Test
    void siPierdeMasDelMargenSeApaga() {
        PruebaEscalado p = new PruebaEscalado();
        simular(p, 1, 16.8, 16, 8); // 5% más lento
        assertFalse(p.conviene());
    }

    @Test
    void conLaOpcionApagadaSiempreEscala() {
        PruebaEscalado p = new PruebaEscalado();
        for (long t = 1; t < 10_000_000_000L; t += 20_000_000L) {
            assertTrue(p.usar(ModoEscalado.FSR1, t, false));
        }
    }

    @Test
    void seVuelveAMedirDespuesDeUnRato() {
        PruebaEscalado p = new PruebaEscalado();
        long reloj = simular(p, 1, 20, 16, 8);
        assertFalse(p.conviene());
        // Pasados los 2 minutos, ahora la GPU es el límite: la nueva medición lo prende.
        reloj = simular(p, reloj, 16, 16, 115);
        simular(p, reloj, 10, 16, 10);
        assertTrue(p.conviene());
    }
}
