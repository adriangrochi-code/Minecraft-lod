package com.example.minecraftlodmod.config;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.gui.entries.EnumListEntry;
import me.shedaniel.clothconfig2.gui.entries.TextListEntry;
import com.example.minecraftlodmod.benchmark.SesionCalibracion;
import com.example.minecraftlodmod.render.Escalado;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

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
        EnumListEntry<ParametrosCalidad.Seleccion> preset = e.startEnumSelector(texto("preset"),
                        ParametrosCalidad.Seleccion.class, c.seleccion.get())
                .setDefaultValue(c.seleccion.getDefault())
                .setEnumNameProvider(valor -> Component.translatable(
                        CLAVE + "preset." + valor.name().toLowerCase(Locale.ROOT)))
                .setTooltip(texto("preset.tooltip"))
                .setSaveConsumer(c.seleccion::set)
                .build();
        general.addEntry(e.startBooleanToggle(texto("lodActivo"), c.lodActivo.get())
                .setDefaultValue(c.lodActivo.getDefault())
                .setTooltip(texto("lodActivo.tooltip"))
                .setSaveConsumer(c.lodActivo::set)
                .build());
        general.addEntry(e.startBooleanToggle(texto("generacionAproximada"), c.generacionAproximada.get())
                .setDefaultValue(c.generacionAproximada.getDefault())
                .setTooltip(texto("generacionAproximada.tooltip"))
                .setSaveConsumer(c.generacionAproximada::set)
                .build());
        general.addEntry(e.startBooleanToggle(texto("pregenerar"), c.pregenerar.get())
                .setDefaultValue(c.pregenerar.getDefault())
                .setTooltip(texto("pregenerar.tooltip"))
                .setSaveConsumer(c.pregenerar::set)
                .build());
        general.addEntry(e.startIntSlider(texto("radioPregeneracion"), c.radioPregeneracion.get(),
                        16, ParametrosCalidad.RADIO_MAX)
                .setDefaultValue(c.radioPregeneracion.getDefault())
                .setTooltip(texto("radioPregeneracion.tooltip"))
                .setSaveConsumer(c.radioPregeneracion::set)
                .build());
        general.addEntry(preset);
        general.addEntry(e.startIntSlider(texto("fpsObjetivo"), c.fpsObjetivo.get(),
                        ParametrosCalidad.FPS_MIN, ParametrosCalidad.FPS_MAX)
                .setDefaultValue(c.fpsObjetivo.getDefault())
                .setTooltip(texto("fpsObjetivo.tooltip"))
                .setSaveConsumer(c.fpsObjetivo::set)
                .build());
        general.addEntry(e.startBooleanToggle(texto("texturasLod"), c.texturasLod.get())
                .setDefaultValue(c.texturasLod.getDefault())
                .setTooltip(texto("texturasLod.tooltip"))
                .setSaveConsumer(c.texturasLod::set)
                .build());
        general.addEntry(e.startBooleanToggle(texto("descartarCuevas"), c.descartarCuevas.get())
                .setDefaultValue(c.descartarCuevas.getDefault())
                .setTooltip(texto("descartarCuevas.tooltip"))
                .setSaveConsumer(c.descartarCuevas::set)
                .build());
        general.addEntry(e.startBooleanToggle(texto("ocultarTapado"), c.ocultarTapado.get())
                .setDefaultValue(c.ocultarTapado.getDefault())
                .setTooltip(texto("ocultarTapado.tooltip"))
                .setSaveConsumer(c.ocultarTapado::set)
                .build());
        general.addEntry(e.startBooleanToggle(texto("oclusionAmbiental"), c.oclusionAmbiental.get())
                .setDefaultValue(c.oclusionAmbiental.getDefault())
                .setTooltip(texto("oclusionAmbiental.tooltip"))
                .setSaveConsumer(c.oclusionAmbiental::set)
                .build());
        general.addEntry(e.startBooleanToggle(texto("autoAjuste"), c.autoAjuste.get())
                .setDefaultValue(c.autoAjuste.getDefault())
                .setTooltip(texto("autoAjuste.tooltip"))
                .setSaveConsumer(c.autoAjuste::set)
                .build());
        general.addEntry(new BotonCalibrar(preset::getValue));

        ConfigCategory experimental = builder.getOrCreateCategory(Component.translatable(CLAVE + "experimental"));
        experimental.addEntry(e.startBooleanToggle(texto("hudRendimiento"), c.hudRendimiento.get())
                .setDefaultValue(c.hudRendimiento.getDefault())
                .setTooltip(texto("hudRendimiento.tooltip"))
                .setSaveConsumer(c.hudRendimiento::set)
                .build());
        experimental.addEntry(e.startBooleanToggle(texto("logDepuracion"), c.logDepuracion.get())
                .setDefaultValue(c.logDepuracion.getDefault())
                .setTooltip(texto("logDepuracion.tooltip"))
                .setSaveConsumer(c.logDepuracion::set)
                .build());
        // Solo los modos que esta GPU puede usar (a la 1060 no se le ofrece DLSS).
        ModoEscalado[] modos = Escalado.modosDisponibles().toArray(ModoEscalado[]::new);
        experimental.addEntry(e.startSelector(texto("escalado"), modos,
                        CompatibilidadEscalado.efectivo(c.escalado.get(), List.of(modos)))
                .setDefaultValue(ModoEscalado.APAGADO)
                .setNameProvider(valor -> Component.translatable(
                        CLAVE + "escalado." + valor.name().toLowerCase(Locale.ROOT)))
                .setTooltip(texto("escalado.tooltip"))
                .setSaveConsumer(c.escalado::set)
                .build());
        experimental.addEntry(e.startIntSlider(texto("fsrEscalaPorcentaje"), c.fsrEscalaPorcentaje.get(), 50, 99)
                .setDefaultValue(c.fsrEscalaPorcentaje.getDefault())
                .setTooltip(texto("fsrEscalaPorcentaje.tooltip"))
                .setSaveConsumer(c.fsrEscalaPorcentaje::set)
                .build());
        experimental.addEntry(e.startDoubleField(texto("fsrNitidez"), c.fsrNitidez.get())
                .setMin(0.0)
                .setMax(2.0)
                .setDefaultValue(c.fsrNitidez.getDefault())
                .setTooltip(texto("fsrNitidez.tooltip"))
                .setSaveConsumer(c.fsrNitidez::set)
                .build());
        experimental.addEntry(e.startBooleanToggle(texto("invertirJitter"), c.invertirJitter.get())
                .setDefaultValue(c.invertirJitter.getDefault())
                .setTooltip(texto("invertirJitter.tooltip"))
                .setSaveConsumer(c.invertirJitter::set)
                .build());
        experimental.addEntry(new BotonVulkan());

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

    /**
     * Preset desde el que calibrar según lo elegido en pantalla (aunque no
     * se haya guardado): AUTOMATICO parte de la recomendación por hardware,
     * PERSONALIZADO del preset Medio.
     */
    static QualityPreset presetParaCalibrar(ParametrosCalidad.Seleccion seleccion) {
        if (seleccion.preset() != null) {
            return seleccion.preset();
        }
        return seleccion == ParametrosCalidad.Seleccion.AUTOMATICO
                ? QualityPreset.recomendarPorHardware(ConfigLod.nucleosCpu(), ConfigLod.ramTotalMb())
                : QualityPreset.MEDIO;
    }

    /** "Calibrar desde este preset" (sección 11): Cloth no trae una entrada de botón. */
    private static final class BotonCalibrar extends TextListEntry {
        private static final int ANCHO = 150;
        private final Button boton;

        BotonCalibrar(Supplier<ParametrosCalidad.Seleccion> seleccion) {
            super(texto("calibrar"), Component.empty());
            boton = Button.builder(texto("calibrar.boton"), b -> {
                        if (SesionCalibracion.enCurso()) {
                            SesionCalibracion.cancelarManual();
                        } else {
                            SesionCalibracion.iniciar(presetParaCalibrar(seleccion.get()));
                        }
                    })
                    .size(ANCHO, 20)
                    .build();
        }

        @Override
        public void render(GuiGraphics graficos, int indice, int y, int x, int ancho, int alto,
                           int mouseX, int mouseY, boolean resaltado, float delta) {
            // Iniciar solo desde el menú principal (la calibración abre otro mundo);
            // una calibración en curso se puede cancelar siempre.
            boolean enCurso = SesionCalibracion.enCurso();
            boolean disponible = enCurso || Minecraft.getInstance().level == null;
            boton.active = disponible;
            boton.setMessage(texto(enCurso ? "calibrar.cancelar" : "calibrar.boton"));
            boton.setTooltip(Tooltip.create(texto(enCurso ? "calibrar.cancelar.tooltip"
                    : disponible ? "calibrar.tooltip" : "calibrar.enMundo")));
            graficos.drawString(Minecraft.getInstance().font, getFieldName(), x, y + 6, 0xFFFFFF);
            boton.setX(x + ancho - ANCHO);
            boton.setY(y);
            boton.render(graficos, mouseX, mouseY, delta);
        }

        @Override
        public int getItemHeight() {
            return 24;
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int boton) {
            return this.boton.mouseClicked(mouseX, mouseY, boton);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(boton);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(boton);
        }
    }

    /** Prende/apaga el VulkanMod integrado para el próximo arranque (ver {@link ConmutadorVulkan}). */
    private static final class BotonVulkan extends TextListEntry {
        private static final int ANCHO = 150;
        private final Button boton;

        BotonVulkan() {
            super(texto("vulkan"), Component.empty());
            boton = Button.builder(Component.empty(), b -> ConmutadorVulkan.alternar()).size(ANCHO, 20).build();
        }

        @Override
        public void render(GuiGraphics graficos, int indice, int y, int x, int ancho, int alto,
                           int mouseX, int mouseY, boolean resaltado, float delta) {
            ConmutadorVulkan.Estado estado = ConmutadorVulkan.estado();
            String clave = "vulkan." + estado.name().toLowerCase(Locale.ROOT);
            boton.active = estado != ConmutadorVulkan.Estado.NO_DISPONIBLE;
            boton.setMessage(texto(clave));
            boton.setTooltip(Tooltip.create(texto(clave + ".tooltip")));
            graficos.drawString(Minecraft.getInstance().font, getFieldName(), x, y + 6, 0xFFFFFF);
            boton.setX(x + ancho - ANCHO);
            boton.setY(y);
            boton.render(graficos, mouseX, mouseY, delta);
        }

        @Override
        public int getItemHeight() {
            return 24;
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int boton) {
            return this.boton.mouseClicked(mouseX, mouseY, boton);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(boton);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(boton);
        }
    }
}
