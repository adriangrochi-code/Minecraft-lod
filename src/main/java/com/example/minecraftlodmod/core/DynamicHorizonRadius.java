package com.example.minecraftlodmod.core;

/**
 * Calcula el radio de LOD efectivo según la altura del jugador sobre el
 * terreno circundante, para que el "horizonte dinámico" sea a la vez más
 * realista (se ve más lejos desde arriba, como en la vida real) y más
 * eficiente (no se gasta generación/memoria en radio que igual estaría
 * tapado por el relieve cercano cuando el jugador está a baja altura).
 *
 * Diseño clave: el radio nunca supera {@code radioBaseChunks} (el techo ya
 * calibrado por el preset de calidad — ver QualityPreset), así que esto
 * nunca le pide al hardware más de lo que ya se determinó que puede
 * sostener. Lo único que varía es CUÁNTO de ese techo se usa: a baja
 * altura, un radio menor (fracción mínima configurable); a gran altura, se
 * acerca asintóticamente al techo completo.
 *
 * La curva es una saturación exponencial (misma familia que
 * {@link com.example.minecraftlodmod.render.AtmosphericPerspective}, por
 * consistencia de "sensación" entre efectos del mod): crece rápido cerca del
 * suelo y se aplana antes de llegar al máximo, en vez de un crecimiento
 * lineal sin límite natural.
 */
public final class DynamicHorizonRadius {

    private DynamicHorizonRadius() {
    }

    /** Fracción del radio del preset a usar cuando el jugador está al nivel del terreno (altura 0). */
    public static final double FRACCION_MINIMA_POR_DEFECTO = 0.35;

    /** Qué tan rápido se acerca al máximo con la altura — más alto = satura más rápido. */
    public static final double VELOCIDAD_CRECIMIENTO_POR_DEFECTO = 0.02;

    public static int calcular(int radioBaseChunks, double alturaSobreTerreno) {
        return calcular(radioBaseChunks, alturaSobreTerreno,
                FRACCION_MINIMA_POR_DEFECTO, VELOCIDAD_CRECIMIENTO_POR_DEFECTO);
    }

    /**
     * @param radioBaseChunks       techo del preset de calidad actual — nunca se supera
     * @param alturaSobreTerreno    altura del jugador sobre el terreno/heightmap circundante, en bloques
     * @param fraccionMinima        fracción del radio a usar a altura 0 (ej. 0.35 = 35%)
     * @param velocidadCrecimiento  controla qué tan rápido se satura la curva con la altura
     */
    public static int calcular(int radioBaseChunks, double alturaSobreTerreno,
                                double fraccionMinima, double velocidadCrecimiento) {
        double altura = Math.max(0.0, alturaSobreTerreno);
        double factor = 1.0 - Math.exp(-velocidadCrecimiento * altura);
        double multiplicador = fraccionMinima + (1.0 - fraccionMinima) * factor;

        return (int) Math.round(radioBaseChunks * multiplicador);
    }
}
