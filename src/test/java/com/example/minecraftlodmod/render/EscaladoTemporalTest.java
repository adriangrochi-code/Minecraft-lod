package com.example.minecraftlodmod.render;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EscaladoTemporalTest {

    private static final float FOV = (float) Math.toRadians(70);

    @Test
    void haltonEsLaSecuenciaConocida() {
        assertEquals(0.5, SecuenciaJitter.halton(1, 2), 1e-12);
        assertEquals(0.25, SecuenciaJitter.halton(2, 2), 1e-12);
        assertEquals(0.75, SecuenciaJitter.halton(3, 2), 1e-12);
        assertEquals(1.0 / 3, SecuenciaJitter.halton(1, 3), 1e-12);
        assertEquals(2.0 / 3, SecuenciaJitter.halton(2, 3), 1e-12);
        assertEquals(1.0 / 9, SecuenciaJitter.halton(3, 3), 1e-12);
    }

    @Test
    void elJitterQuedaDentroDelPixelYPromediaCero() {
        int fases = SecuenciaJitter.fases(960, 1920);
        assertEquals(32, fases, "Escala 2x: 8 × 2² fases, como FSR 2");
        double sx = 0, sy = 0;
        for (int c = 0; c < fases; c++) {
            double[] j = SecuenciaJitter.desplazamiento(c, fases);
            assertTrue(j[0] >= -0.5 && j[0] < 0.5 && j[1] >= -0.5 && j[1] < 0.5);
            sx += j[0];
            sy += j[1];
        }
        assertEquals(0, sx / fases, 0.05);
        assertEquals(0, sy / fases, 0.05);
        assertEquals(8, SecuenciaJitter.fases(1920, 1920), "Sin escala: 8 fases");
    }

    @Test
    void conLaCamaraQuietaElMovimientoEsCeroAunqueHayaJitter() {
        Matrix4f vista = new Matrix4f().rotateX(0.3f).rotateY(1.1f);
        Matrix4f proyeccion = new Matrix4f().perspective(FOV, 16f / 9, 0.05f, 800);
        Matrix4f conJitter = MovimientoCamara.conJitter(proyeccion, 0.3, -0.2, 960, 540);
        Matrix4f inversa = new Matrix4f(conJitter).mul(vista).invert();
        Matrix4f sinJitter = new Matrix4f(MovimientoCamara.sinJitter(conJitter, 0.3, -0.2, 960, 540)).mul(vista);
        float[] v = MovimientoCamara.velocidad(0.37f, 0.61f, 0.998f, inversa, sinJitter, sinJitter, 0, 0, 0);
        assertEquals(0, v[0], 1e-5);
        assertEquals(0, v[1], 1e-5);
    }

    @Test
    void sacarElJitterDevuelveLaProyeccionOriginal() {
        Matrix4f proyeccion = new Matrix4f().perspective(FOV, 16f / 9, 0.05f, 800);
        Matrix4f vuelta = MovimientoCamara.sinJitter(MovimientoCamara.conJitter(proyeccion, 0.4, 0.1, 1280, 720),
                0.4, 0.1, 1280, 720);
        assertTrue(vuelta.equals(proyeccion, 1e-6f));
    }

    @Test
    void alAvanzarLaCamaraElPuntoVieneDeMasCercaDelCentro() {
        Matrix4f vista = new Matrix4f();
        Matrix4f proyeccion = new Matrix4f().perspective(FOV, 1, 0.05f, 800);
        // Un punto a 10 bloques adelante y 2 a la derecha: dónde está en pantalla y a qué profundidad.
        Vector4f clip = new Vector4f(2, 0, -10, 1).mul(new Matrix4f(proyeccion).mul(vista));
        float u = (clip.x / clip.w + 1) / 2, v = (clip.y / clip.w + 1) / 2, prof = (clip.z / clip.w + 1) / 2;
        Matrix4f vp = new Matrix4f(proyeccion).mul(vista);
        // La cámara avanzó 1 bloque hacia -z: actual − anterior = (0, 0, -1).
        float[] vel = MovimientoCamara.velocidad(u, v, prof, new Matrix4f(vp).invert(), vp, vp, 0, 0, -1);
        assertTrue(vel[0] < 0, "Antes el punto estaba más lejos: más cerca del centro de la pantalla");
        assertEquals(0, vel[1], 1e-5);
    }
}
