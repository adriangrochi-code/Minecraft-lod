package com.example.minecraftlodmod.config;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * Registra la pantalla de config del mod (botón "Config" de la lista de
 * mods). Solo cliente: llamar únicamente con {@code Dist.CLIENT}.
 *
 * Con Cloth Config instalado se usa {@link PantallaCloth} (la pantalla de
 * la sección 11). Sin Cloth, la pantalla automática de NeoForge sobre los
 * mismos valores de {@link ConfigLod}: Cloth es opcional a propósito, para
 * no sumarle una dependencia obligatoria a quien solo quiere el LOD.
 */
public final class PantallaConfig {

    public static final String MOD_CLOTH = "cloth_config";

    private PantallaConfig() {
    }

    public static void registrar(ModContainer contenedor) {
        IConfigScreenFactory fabrica = ModList.get().isLoaded(MOD_CLOTH)
                // Lambda y no referencia a método: PantallaCloth (y con ella
                // las clases de Cloth) se carga recién al abrir la pantalla.
                ? (mod, anterior) -> PantallaCloth.crear(anterior)
                : ConfigurationScreen::new;
        contenedor.registerExtensionPoint(IConfigScreenFactory.class, fabrica);
    }
}
