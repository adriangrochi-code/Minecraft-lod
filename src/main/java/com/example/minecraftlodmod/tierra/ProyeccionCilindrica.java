package com.example.minecraftlodmod.tierra;

/**
 * Equirectangular ({@code 05-borde.md}): x = lon × k, z = -lat × k con k =
 * bloques por grado. El norte queda hacia -z y el este hacia +x, como en
 * Minecraft. La longitud de la inversa no se envuelve (la circunnavegación de
 * H10 decide qué pasa al cruzar ±180°); la fuente de datos sí la envuelve.
 */
public final class ProyeccionCilindrica implements Proyeccion {

    private final double metrosPorBloque;
    private final double k;

    public ProyeccionCilindrica(double metrosPorBloque) {
        if (!(metrosPorBloque > 0)) throw new IllegalArgumentException("Escala inválida: " + metrosPorBloque);
        this.metrosPorBloque = metrosPorBloque;
        this.k = Proyeccion.super.bloquesPorGrado();
    }

    @Override
    public double latitud(double x, double z) {
        return -z / k;
    }

    @Override
    public double longitud(double x, double z) {
        return x / k;
    }

    @Override
    public double x(double latitud, double longitud) {
        return longitud * k;
    }

    @Override
    public double z(double latitud, double longitud) {
        return -latitud * k;
    }

    @Override
    public double metrosPorBloque() {
        return metrosPorBloque;
    }

    @Override
    public double bloquesPorGrado() {
        return k;
    }
}
