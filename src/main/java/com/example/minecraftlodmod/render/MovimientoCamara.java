package com.example.minecraftlodmod.render;

import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Matrices para los vectores de movimiento de los escaladores temporales, y
 * la misma cuenta que hace el shader {@code escalado_movimiento} (para
 * testearla en Java).
 *
 * Minecraft no genera vectores de movimiento: se reconstruyen desde la
 * profundidad, suponiendo que lo que se ve está quieto y solo se mueve la
 * cámara (las entidades que se mueven quedan con el vector de la cámara).
 * Las posiciones son relativas a la cámara, como las dibuja Minecraft; entre
 * cuadros se corrige con la diferencia de posición de la cámara.
 *
 * Convención (la de XeSS y DLSS): el vector va del cuadro actual al
 * anterior, sin el jitter, en coordenadas de textura (0..1).
 */
public final class MovimientoCamara {

    private MovimientoCamara() {
    }

    /**
     * Proyección con el jitter aplicado: corre el resultado {@code jx, jy}
     * píxeles de una imagen de {@code ancho × alto}.
     */
    public static Matrix4f conJitter(Matrix4f proyeccion, double jx, double jy, int ancho, int alto) {
        return new Matrix4f().translation((float) (2 * jx / ancho), (float) (2 * jy / alto), 0).mul(proyeccion);
    }

    /** Inversa de {@link #conJitter}: la proyección sin el jitter. */
    public static Matrix4f sinJitter(Matrix4f conJitter, double jx, double jy, int ancho, int alto) {
        return new Matrix4f().translation((float) (-2 * jx / ancho), (float) (-2 * jy / alto), 0).mul(conJitter);
    }

    /**
     * Vector de movimiento (du, dv) del texel en (u, v) con profundidad
     * {@code prof} (0..1, OpenGL).
     *
     * @param inversaActualConJitter inversa de proyecciónConJitter × vista del cuadro actual
     * @param actualSinJitter        proyecciónSinJitter × vista del cuadro actual
     * @param anteriorSinJitter      proyecciónSinJitter × vista del cuadro anterior
     * @param deltaCamara            cámara actual − cámara anterior (bloques)
     */
    public static float[] velocidad(float u, float v, float prof, Matrix4f inversaActualConJitter,
                                    Matrix4f actualSinJitter, Matrix4f anteriorSinJitter,
                                    float dx, float dy, float dz) {
        Vector4f p = new Vector4f(u * 2 - 1, v * 2 - 1, prof * 2 - 1, 1).mul(inversaActualConJitter);
        p.div(p.w);
        Vector4f actual = new Vector4f(p.x, p.y, p.z, 1).mul(actualSinJitter);
        Vector4f anterior = new Vector4f(p.x + dx, p.y + dy, p.z + dz, 1).mul(anteriorSinJitter);
        return new float[] {
                (anterior.x / anterior.w - actual.x / actual.w) * 0.5f,
                (anterior.y / anterior.w - actual.y / actual.w) * 0.5f
        };
    }
}
