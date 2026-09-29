package com.example.minecraftlodmod.config;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * Pantalla de config con Cloth Config (árbol de la sección 11). Solo se
 * carga si Cloth está instalado — ver {@link PantallaConfig}.
 *
 * Edita {@link ConfigLod#CLIENTE} y guarda el archivo; la generación toma
 * los valores nuevos al abrir el próximo mundo.
 */
final class PantallaCloth {

    private static final String CLAVE = "minecraftlodmod.configuration.";

    private PantallaCloth() {
    }

    static Screen crear(Screen anterior) {
        ConfigLod.Cliente c = ConfigLod.CLIENTE;
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(anterior)
                .setTitle(Component.translatable(CLAVE + "title"))
                .setSavingRunnable(ConfigLod.SPEC_CLIENTE::save);
        ConfigEntryBuilder e = builder.entryBuilder();

        ConfigCategory general = builder.getOrCreateCategory(Component.translatable(CLAVE + "general"));
        general.addEntry(e.startEnumSelector(texto("preset"), ParametrosCalidad.Seleccion.class, c.seleccion.get())
                .setDefaultValue(c.seleccion.getDefault())
                .setEnumNameProvider(valor -> Component.translatable(
                        CLAVE + "preset." + valor.name().toLowerCase(Locale.ROOT)))
                .setTooltip(texto("preset.tooltip"))
                .setSaveConsumer(c.seleccion::set)
                .build());
        general.addEntry(e.startIntSlider(texto("fpsObjetivo"), c.fpsObjetivo.get(),
                        ParametrosCalidad.FPS_MIN, ParametrosCalidad.FPS_MAX)
                .setDefaultValue(c.fpsObjetivo.getDefault())
                .setTooltip(texto("fpsObjetivo.tooltip"))
                .setSaveConsumer(c.fpsObjetivo::set)
                .build());
        general.addEntry(e.startBooleanToggle(texto("autoAjuste"), c.autoAjuste.get())
                .setDefaultValue(c.autoAjuste.getDefault())
                .setTooltip(texto("autoAjuste.tooltip"))
                .setSaveConsumer(c.autoAjuste::set)
                .build());
        // Botón "Calibrar desde este preset": llega con benchmark/ (sección 9).
        general.addEntry(e.startTextDescription(texto("calibrar.pendiente")).build());

        ConfigCategory personalizado = builder.getOrCreateCategory(Component.translatable(CLAVE + "personalizado"));
        personalizado.addEntry(e.startTextDescription(texto("personalizado.descripcion")).build());
        personalizado.addEntry(e.startIntSlider(texto("radioLodChunks"), c.radioLodChunks.get(),
                        ParametrosCalidad.RADIO_MIN, ParametrosCalidad.RADIO_MAX)
                .setDefaultValue(c.radioLodChunks.getDefault())
                .setSaveConsumer(c.radioLodChunks::set)
                .build());
        personalizado.addEntry(e.startDoubleField(texto("umbralPx"), c.umbralPx.get())
                .setMin(ParametrosCalidad.UMBRAL_MIN)
                .setMax(ParametrosCalidad.UMBRAL_MAX)
                .setDefaultValue(c.umbralPx.getDefault())
                .setTooltip(texto("umbralPx.tooltip"))
                .setSaveConsumer(c.umbralPx::set)
                .build());
        personalizado.addEntry(e.startIntSlider(texto("hilosGeneracion"), c.hilosGeneracion.get(),
                        ParametrosCalidad.HILOS_MIN, Math.max(2, Math.min(ParametrosCalidad.HILOS_MAX, ConfigLod.nucleosCpu())))
                .setDefaultValue(c.hilosGeneracion.getDefault())
                .setSaveConsumer(c.hilosGeneracion::set)
                .build());
        personalizado.addEntry(e.startIntSlider(texto("cacheRamMb"), c.cacheRamMb.get(),
                        ParametrosCalidad.CACHE_MIN_MB, ParametrosCalidad.CACHE_MAX_MB)
                .setDefaultValue(c.cacheRamMb.getDefault())
                .setSaveConsumer(c.cacheRamMb::set)
                .build());
        personalizado.addEntry(e.startIntSlider(texto("colapsoDesdeNivel"), c.colapsoDesdeNivel.get(),
                        ParametrosCalidad.COLAPSO_MIN, ParametrosCalidad.COLAPSO_MAX)
                .setDefaultValue(c.colapsoDesdeNivel.getDefault())
                .setTooltip(texto("colapsoDesdeNivel.tooltip"))
                .setSaveConsumer(c.colapsoDesdeNivel::set)
                .build());

        return builder.build();
    }

    private static Component texto(String clave) {
        return Component.translatable(CLAVE + clave);
    }
}
