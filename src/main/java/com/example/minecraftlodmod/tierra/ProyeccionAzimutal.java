package com.example.minecraftlodmod.tierra;

/**
 * Azimutal equidistante centrada en el polo norte, la "tierra plana"
 * ({@code 05-borde.md}): el polo norte en (0, 0), distancia al centro
 * r = (90° - lat) × k, ángulo = longitud con lon 0 hacia +z y lon 90° E hacia
 * +x. Así, cerca del meridiano de Greenwich el norte queda hacia -z y el este
 * hacia +x, como en Minecraft (del otro lado del disco quedan invertidos: es
 * inevitable en este mapa).
 *
 * Distancias norte-sur reales; el este-oeste se estira por c / sin c (c =
 * distancia angular al polo): ×17 a 80° S. Pasando r = 180° × k (el polo sur,
 * el borde) la latitud da menos de -90: son las farlands congeladas (H7).
 */
public final class ProyeccionAzimutal implements Proyeccion {

    private final double metrosPorBloque;
    private final double k;

    public ProyeccionAzimutal(double metrosPorBloque) {
        if (!(metrosPorBloque > 0)) throw new IllegalArgumentException("Escala inválida: " + metrosPorBloque);
        this.metrosPorBloque = metrosPorBloque;
        this.k = Proyeccion.super.bloquesPorGrado();
    }

    @Override
    public double latitud(double x, double z) {
        return 90.0 - Math.sqrt(x * x + z * z) / k;
    }

    @Override
    public double longitud(double x, double z) {
        if (x == 0 && z == 0) return 0;
        return Math.toDegrees(Math.atan2(x, z));
    }

    @Override
    public double x(double latitud, double longitud) {
        return (90.0 - latitud) * k * Math.sin(Math.toRadians(longitud));
    }

    @Override
    public double z(double latitud, double longitud) {
        return (90.0 - latitud) * k * Math.cos(Math.toRadians(longitud));
    }

    @Override
    public double metrosPorBloque() {
        return metrosPorBloque;
    }

    @Override
    public double bloquesPorGrado() {
        return k;
    }

    /** Distancia del centro al polo sur (el borde del disco), en bloques. */
    public double radioDisco() {
        return 180.0 * k;
    }
}
