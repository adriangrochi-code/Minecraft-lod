package com.example.minecraftlodmod.render;

/**
 * Perspectiva atmosférica: mezcla el color de un supervóxel lejano hacia el
 * color del cielo/niebla según su distancia a la cámara, imitando cómo el
 * aire real dispersa la luz y hace que el terreno lejano se vea pálido y
 * difuso en vez de nítido-pero-chico. Es la pieza clave para que un
 * horizonte de gran distancia se vea "real" y no solo "lejos".
 *
 * Lógica pura (sin GPU): produce el factor de mezcla 0.0-1.0 (0 = color
 * original sin modificar, 1 = completamente el color del cielo) dado una
 * distancia y los parámetros de densidad/inicio de niebla. render/ aplica
 * este factor como un lerp de color, típicamente en el fragment shader
 * (o precalculado por vértice si se prefiere más barato).
 *
 * También sirve para disimular el borde donde termina el radio de
 * renderizado (sección "niebla en el borde" del documento de arquitectura):
 * con densidad ajustada para que el factor llegue a 1.0 antes del borde real,
 * el corte del mundo generado queda oculto dentro de la niebla, no expuesto.
 */
public final class AtmosphericPerspective {

    private AtmosphericPerspective() {
    }

    /**
     * Factor de niebla exponencial al cuadrado (exp2), el mismo tipo de
     * curva que usa Minecraft vanilla para su niebla — crece suave cerca,
     * y se satura antes de llegar al límite de render, dejando margen para
     * que la generación/streaming no se note aunque vaya con retraso justo
     * en el borde.
     *
     * @param distancia      distancia de la cámara al supervóxel, en bloques
     * @param inicioNiebla   distancia a partir de la cual empieza a notarse (bloques)
     * @param finNiebla      distancia a la que el factor llega a 1.0 (totalmente cielo)
     * @return factor de mezcla, siempre entre 0.0 y 1.0
     */
    public static double factorDeNiebla(double distancia, double inicioNiebla, double finNiebla) {
        if (distancia <= inicioNiebla) return 0.0;
        if (distancia >= finNiebla) return 1.0;

        double t = (distancia - inicioNiebla) / (finNiebla - inicioNiebla);
        // exp2: se satura más rápido que lineal, se ve más natural que un fade lineal
        double factor = 1.0 - Math.exp(-4.0 * t * t);
        return Math.clamp(factor, 0.0, 1.0);
    }

    /** Resultado de mezclar un color RGB (0-255 por canal) hacia el color de cielo/niebla. */
    public record ColorMezclado(int r, int g, int b) {
    }

    public static ColorMezclado mezclarConCielo(int r, int g, int b,
                                                 int cieloR, int cieloG, int cieloB,
                                                 double factor) {
        double f = Math.clamp(factor, 0.0, 1.0);
        return new ColorMezclado(
                (int) Math.round(r + (cieloR - r) * f),
                (int) Math.round(g + (cieloG - g) * f),
                (int) Math.round(b + (cieloB - b) * f)
        );
    }

    /**
     * Atajo: dado un supervóxel (color + distancia) y el color de cielo
     * actual (que en Minecraft cambia con la hora del día/bioma), calcula
     * directamente el color final a usar en el vértice.
     */
    public static ColorMezclado colorFinal(int voxelR, int voxelG, int voxelB, double distancia,
                                            double inicioNiebla, double finNiebla,
                                            int cieloR, int cieloG, int cieloB) {
        double factor = factorDeNiebla(distancia, inicioNiebla, finNiebla);
        return mezclarConCielo(voxelR, voxelG, voxelB, cieloR, cieloG, cieloB, factor);
    }
}
