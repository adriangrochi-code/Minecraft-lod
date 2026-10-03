package com.example.minecraftlodmod.render;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;

/**
 * Descarte de celdas fuera del campo de visión: los 4 planos laterales de
 * {@code proyección × vista}, en coordenadas relativas a la cámara (las mismas
 * con que se corre cada celda al dibujarla). Sin near ni far: cada camino de
 * dibujo arma la profundidad a su manera (OpenGL/Vulkan, far estirado para
 * shaderpacks) y lo lejano ya lo acota el plan. Lógica pura (solo JOML).
 */
final class CampoVision {

    /** Planos (a, b, c, d): adentro si a·x + b·y + c·z + d ≥ 0. */
    private final float[] planos = new float[16];

    CampoVision(Matrix4fc proyeccion, Matrix4fc vista) {
        Matrix4f m = new Matrix4f(proyeccion).mul(vista);
        int[] cuales = {Matrix4f.PLANE_NX, Matrix4f.PLANE_PX, Matrix4f.PLANE_NY, Matrix4f.PLANE_PY};
        Vector4f p = new Vector4f();
        for (int i = 0; i < 4; i++) {
            m.frustumPlane(cuales[i], p);
            planos[i * 4] = p.x;
            planos[i * 4 + 1] = p.y;
            planos[i * 4 + 2] = p.z;
            planos[i * 4 + 3] = p.w;
        }
    }

    /** true si la caja [min, max] (relativa a la cámara) toca el campo de visión. */
    boolean tocaCaja(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        for (int i = 0; i < 16; i += 4) {
            float a = planos[i], b = planos[i + 1], c = planos[i + 2];
            // La esquina más adentro según este plano: si ni esa entra, la caja está afuera.
            float x = a >= 0 ? maxX : minX;
            float y = b >= 0 ? maxY : minY;
            float z = c >= 0 ? maxZ : minZ;
            if (a * x + b * y + c * z + planos[i + 3] < 0) {
                return false;
            }
        }
        return true;
    }
}
