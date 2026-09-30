package com.example.minecraftlodmod.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EstadisticaFramesTest {

    @Test
    void promedioPeorYUnoPorCientoBajo() {
        EstadisticaFrames e = new EstadisticaFrames();
        for (int i = 0; i < 99; i++) {
            e.agregar(10);
        }
        e.agregar(50); // un tirón
        EstadisticaFrames.Resumen r = e.cerrar(1.0);
        assertEquals(100, r.cuadros());
        assertEquals(100, r.fps(), 1e-9);
        assertEquals(10.4, r.promedioMs(), 1e-9);
        assertEquals(50, r.peorMs(), 1e-9);
        assertEquals(10, r.percentil99Ms(), 1e-9, "Con 100 cuadros, el percentil 99 es el cuadro 99");
        assertEquals(0, e.cerrar(1.0).cuadros(), "Cerrar vacía la ventana");
    }

    @Test
    void conPocosCuadrosElUnoPorCientoBajoEsElPeor() {
        EstadisticaFrames e = new EstadisticaFrames();
        e.agregar(20);
        e.agregar(80);
        EstadisticaFrames.Resumen r = e.cerrar(0.1);
        assertEquals(80, r.percentil99Ms(), 1e-9);
        assertEquals(12.5, r.fpsUnoPorCientoBajo(), 1e-9);
        assertEquals(20, r.fps(), 1e-9);
    }
}
