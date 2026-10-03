package com.example.minecraftlodmod.config;

/**
 * Límites de lo que un servidor sirve por red (sección 10, y el punto
 * abierto de "información revelada" de la sección 14). Lógica pura.
 */
public record LimitesRed(int radioMaximoChunks, double nodosPorSegundo, double rafagaNodos) {

    /**
     * @param radioConfigurado 0 = usar el radio del preset con que genera el
     *                         servidor; si no, nunca más que ese radio (no
     *                         tiene sentido servir más allá de lo generado)
     */
    public static LimitesRed resolver(int radioConfigurado, ParametrosCalidad calidadServidor,
                                      int nodosPorSegundo, int rafagaNodos) {
        int radioPreset = calidadServidor.radioLodChunks();
        int radio = radioConfigurado <= 0 ? radioPreset : Math.min(radioConfigurado, radioPreset);
        // La ráfaga nunca por debajo de lo que se recupera en un segundo.
        return new LimitesRed(radio, nodosPorSegundo, Math.max(rafagaNodos, nodosPorSegundo));
    }
}
