package com.example.minecraftlodmod.render;

/**
 * Colores del HUD de rendimiento según el valor (lógica pura): los porcentajes
 * de uso van de verde a amarillo a rojo al acercarse al 100%, y los FPS se
 * ponen rojos por debajo de 30.
 */
final class ColoresHud {

    static final int VERDE = 0x55FF55, AMARILLO = 0xFFFF55, ROJO = 0xFF5555, BLANCO = 0xFFFFFF, GRIS = 0xAAAAAA;

    /** FPS por debajo de esto, en rojo. */
    static final double FPS_ROJO = 30;
    /** FPS por debajo de esto (y desde {@link #FPS_ROJO}), en amarillo. */
    static final double FPS_AMARILLO = 60;

    private ColoresHud() {
    }

    /**
     * Uso en % (0-100): verde hasta la mitad, de ahí pasa a amarillo (75%) y a
     * rojo (90% o más), en degradé. Negativo = sin dato: gris.
     */
    static int uso(double porcentaje) {
        if (porcentaje < 0 || Double.isNaN(porcentaje)) {
            return GRIS;
        }
        if (porcentaje <= 50) {
            return VERDE;
        }
        if (porcentaje <= 75) {
            return mezclar(VERDE, AMARILLO, (porcentaje - 50) / 25);
        }
        if (porcentaje < 90) {
            return mezclar(AMARILLO, ROJO, (porcentaje - 75) / 15);
        }
        return ROJO;
    }

    /** FPS: rojo por debajo de 30, amarillo hasta 60, verde desde ahí. Negativo = sin dato. */
    static int fps(double fps) {
        if (fps < 0 || Double.isNaN(fps)) {
            return GRIS;
        }
        return fps < FPS_ROJO ? ROJO : fps < FPS_AMARILLO ? AMARILLO : VERDE;
    }

    /** Interpolación lineal por canal entre dos colores 0xRRGGBB (t de 0 a 1). */
    static int mezclar(int a, int b, double t) {
        t = Math.max(0, Math.min(1, t));
        int r = (int) Math.round(((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
        int g = (int) Math.round(((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t);
        int bl = (int) Math.round((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
        return r << 16 | g << 8 | bl;
    }
}
