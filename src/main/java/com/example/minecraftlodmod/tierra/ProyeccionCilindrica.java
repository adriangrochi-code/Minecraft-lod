package com.example.minecraftlodmod.tierra;

/**
 * Equirectangular ({@code 05-borde.md}): x = lon × k, z = -lat × k con k =
 * bloques por grado. El norte queda hacia -z y el este hacia +x, como en
 * Minecraft. La vuelta al ecuador ({@link #periodoX}) es un múltiplo de 16
 * bloques ({@link Costura}): k sale de ella, así el mundo es periódico en x
 * bloque a bloque (H10). La longitud de la inversa no se envuelve; quien
 * consulta envuelve x ({@link AlturaTierra}).
 */
public final class ProyeccionCilindrica implements Proyeccion {

    private final double metrosPorBloque;
    private final double k;
    private final double periodo;

    public ProyeccionCilindrica(double metrosPorBloque) {
        if (!(metrosPorBloque > 0)) throw new IllegalArgumentException("Escala inválida: " + metrosPorBloque);
        this.metrosPorBloque = metrosPorBloque;
        this.periodo = Costura.circunferencia(metrosPorBloque);
        this.k = periodo / 360.0;
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
    public double periodoX() {
        return periodo;
    }

    @Override
    public double bloquesPorGrado() {
        return k;
    }
}
