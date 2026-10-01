package com.example.minecraftlodmod.tierra;

import java.util.List;

/**
 * Elige la "clave" de bioma de una columna a partir del clima (clase de
 * Köppen-Geiger, Beck et al. 2023), la elevación y la latitud. Lógica pura;
 * qué bioma de Minecraft corresponde a cada clave lo dice el
 * {@code world_preset} ({@link FuenteBiomasTierra}, campo {@code biomas}),
 * editable por datapack ({@code docs/tierra-real/02-datos.md}).
 *
 * Reglas, en orden:
 * <ol>
 *   <li><b>Mar</b> (sin clase de clima y bajo el nivel del mar): por latitud
 *       ({@code oceano_calido} hasta 23,5°, {@code templado} 35°,
 *       {@code normal} 50°, {@code frio} 63°, más allá {@code helado}) y
 *       {@code _profundo} pasando la plataforma continental (-200 m).</li>
 *   <li><b>Playa</b>: tierra a menos de 2 bloques sobre el mar junto al mar
 *       ({@code playa}, o {@code playa_fria} con clima D/E o latitud &gt; 60°).</li>
 *   <li><b>Pisos de altura</b> (salvo climas polares ET/EF, que ya son hielo o
 *       tundra): sobre la línea de nieve {@code nieve}, 1500 m más arriba
 *       {@code cumbre}, y en los 700 m bajo la nieve {@code roca_alta}. La línea
 *       de nieve: 5000 m hasta 20° de latitud, bajando 90 m por grado.</li>
 *   <li><b>Clima</b>: el símbolo de Köppen ({@code Af}, {@code BWh}, …), o
 *       {@code sin_clima} en tierra sin dato (islas chicas que el mapa de
 *       clima da como mar).</li>
 * </ol>
 * La altura de los pisos además la refuerza vanilla: la temperatura baja con
 * y sobre 80, así que la nieve cae sola en las montañas templadas.
 */
public final class ClasificadorBiomas {

    /** Símbolos de Köppen por número de clase (1..30) del mapa de Beck et al.; 0 = sin dato. */
    public static final List<String> KOPPEN = List.of("",
            "Af", "Am", "Aw", "BWh", "BWk", "BSh", "BSk",
            "Csa", "Csb", "Csc", "Cwa", "Cwb", "Cwc", "Cfa", "Cfb", "Cfc",
            "Dsa", "Dsb", "Dsc", "Dsd", "Dwa", "Dwb", "Dwc", "Dwd", "Dfa", "Dfb", "Dfc", "Dfd",
            "ET", "EF");

    public static final String SIN_CLIMA = "sin_clima", PLAYA = "playa", PLAYA_FRIA = "playa_fria";
    public static final String NIEVE = "nieve", CUMBRE = "cumbre", ROCA_ALTA = "roca_alta";
    /** Pasando el borde de la Tierra plana (grietas y farlands congeladas). */
    public static final String FARLANDS = "farlands";
    private static final List<String> OCEANOS = List.of("oceano_calido", "oceano_templado", "oceano_normal",
            "oceano_frio", "oceano_helado");
    public static final double PLATAFORMA_M = -200;

    /** Todas las claves que el {@code world_preset} tiene que mapear a un bioma. */
    public static final List<String> CLAVES;

    static {
        java.util.ArrayList<String> c = new java.util.ArrayList<>(KOPPEN.subList(1, KOPPEN.size()));
        for (String o : OCEANOS) {
            c.add(o);
            c.add(o + "_profundo");
        }
        c.addAll(List.of(SIN_CLIMA, PLAYA, PLAYA_FRIA, NIEVE, CUMBRE, ROCA_ALTA, FARLANDS));
        CLAVES = List.copyOf(c);
    }

    private ClasificadorBiomas() {}

    /**
     * @param elevacion       metros sobre el nivel del mar
     * @param claseKoppen     0..30
     * @param juntoAlMar      hay mar (sin clima y bajo el nivel) a pocos bloques
     * @param metrosPorBloque escala del mundo (para "2 bloques sobre el mar")
     */
    public static String clave(double elevacion, double latitud, int claseKoppen, boolean juntoAlMar, double metrosPorBloque) {
        double lat = Math.abs(latitud);
        boolean conClima = claseKoppen > 0 && claseKoppen < KOPPEN.size();
        if (!conClima && elevacion < 0) {
            int banda = lat <= 23.5 ? 0 : lat <= 35 ? 1 : lat <= 50 ? 2 : lat <= 63 ? 3 : 4;
            return elevacion < PLATAFORMA_M ? OCEANOS.get(banda) + "_profundo" : OCEANOS.get(banda);
        }
        String simbolo = conClima ? KOPPEN.get(claseKoppen) : null;
        boolean polar = simbolo != null && simbolo.startsWith("E");
        if (juntoAlMar && elevacion >= 0 && elevacion < 2 * metrosPorBloque) {
            boolean frio = lat > 60 || (simbolo != null && (simbolo.startsWith("D") || polar));
            return frio ? PLAYA_FRIA : PLAYA;
        }
        if (!polar) {
            double nieve = lineaDeNieve(lat);
            if (elevacion >= nieve + 1500) return CUMBRE;
            if (elevacion >= nieve) return NIEVE;
            if (elevacion >= nieve - 700) return ROCA_ALTA;
        }
        return conClima ? simbolo : SIN_CLIMA;
    }

    /** Altura aproximada de la línea de nieve permanente, en metros, según la latitud. */
    public static double lineaDeNieve(double latitudAbs) {
        return latitudAbs < 20 ? 5000 : Math.max(0, 5000 - (latitudAbs - 20) * 90);
    }
}
