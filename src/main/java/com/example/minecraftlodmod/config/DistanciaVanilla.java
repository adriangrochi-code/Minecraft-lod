package com.example.minecraftlodmod.config;

/**
 * Modo híbrido: con el LOD dibujando, vanilla no necesita una distancia de
 * render grande (el LOD cubre lo de más allá con mucho menos costo por área),
 * así que se la acota según el preset. Nunca sube la que eligió el jugador.
 * Afecta el dibujo (vanilla, Sodium, Vulkan) y lo que carga el servidor
 * integrado (mixins sobre {@code Options#getEffectiveRenderDistance} e
 * {@code IntegratedServer#tickServer}).
 */
public final class DistanciaVanilla {

    private DistanciaVanilla() {
    }

    /** Tope en chunks según el radio del LOD del preset (más radio = equipo más fuerte). */
    public static int topeParaRadio(int radioLodChunks) {
        if (radioLodChunks <= 32) {
            return 5;
        }
        if (radioLodChunks <= 96) {
            return 6;
        }
        if (radioLodChunks <= 160) {
            return 8;
        }
        if (radioLodChunks <= 224) {
            return 10;
        }
        return 12;
    }

    /** La distancia a usar: la pedida, acotada al tope si corresponde (mínimo 2, como vanilla). */
    public static int acotar(int pedida, int tope) {
        return Math.max(2, Math.min(pedida, tope));
    }

    /**
     * Tope vigente en chunks, o {@link Integer#MAX_VALUE} si no se acota (opción
     * apagada, LOD apagado o config todavía sin cargar).
     */
    public static int topeActual() {
        try {
            if (!ConfigLod.SPEC_CLIENTE.isLoaded() || !ConfigLod.CLIENTE.vanillaReducida.get()
                    || !ConfigLod.CLIENTE.lodActivo.get()) {
                return Integer.MAX_VALUE;
            }
            return topeParaRadio(ConfigLod.calidadCliente().radioLodChunks());
        } catch (RuntimeException e) {
            return Integer.MAX_VALUE;
        }
    }
}
