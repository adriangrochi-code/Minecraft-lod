package com.example.minecraftlodmod.config;

import com.example.minecraftlodmod.benchmark.SesionCalibracion;
import com.example.minecraftlodmod.config.OpcionesLod.Accion;
import com.example.minecraftlodmod.config.OpcionesLod.Ciclo;
import com.example.minecraftlodmod.config.OpcionesLod.Deslizador;
import com.example.minecraftlodmod.config.OpcionesLod.Impacto;
import com.example.minecraftlodmod.config.OpcionesLod.Interruptor;
import com.example.minecraftlodmod.config.OpcionesLod.Opcion;
import com.example.minecraftlodmod.render.Escalado;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Opciones de video de Minecraft y del LOD con el estilo de las de Sodium
 * (reemplaza a Opciones > Video, ver {@link PantallaConfig}): pestañas
 * arriba, filas oscuras con el nombre a la izquierda y el control a la
 * derecha, panel con la descripción de la opción bajo el mouse y su impacto
 * en el rendimiento, y Deshacer / Aplicar / Hecho abajo a la derecha. No
 * depende de Cloth Config ni de ningún otro mod. Solo cliente.
 *
 * Los cambios quedan pendientes (nombre en amarillo e itálica) hasta Aplicar
 * o Hecho; Deshacer los descarta.
 */
public final class PantallaLod extends Screen {

    private static final String CLAVE = "minecraftlodmod.configuration.";
    private static final String CLAVE_PANTALLA = "minecraftlodmod.pantalla.";
    static final int MARGEN = 6, ALTO_FILA = 18, ESPACIO_GRUPO = 4, ALTO_PESTANA = 18, Y_OPCIONES = 30;
    static final int FONDO = 0x90000000, FONDO_RESALTADO = 0xE0000000;

    private record Pagina(Component titulo, List<List<Opcion>> grupos) {
    }

    private final Screen anterior;
    private final List<Pagina> paginas = new ArrayList<>();
    private OpcionesVideo video;
    private int paginaActual;
    private double desplazamiento;
    private Opcion arrastrando;
    private Opcion bajoMouse;

    public PantallaLod(Screen anterior) {
        this(anterior, 0);
    }

    /** @param pagina pestaña con la que abre (0 = Video, {@link #PAGINA_LOD} = la primera del LOD) */
    public PantallaLod(Screen anterior, int pagina) {
        super(Component.translatable("options.videoTitle"));
        this.anterior = anterior;
        armarPaginas();
        this.paginaActual = Math.max(0, Math.min(paginas.size() - 1, pagina));
    }

    /** Índice de la primera pestaña del LOD (después de Video y Gráficos). */
    public static final int PAGINA_LOD = 2;

    // ------------------------------------------------------------------ opciones

    private void armarPaginas() {
        video = new OpcionesVideo(this::abrirOriginal);
        paginas.add(new Pagina(pestana("video"), video.pantalla));
        paginas.add(new Pagina(pestana("graficos"), video.graficos));

        ConfigLod.Cliente c = ConfigLod.CLIENTE;
        Ciclo<ParametrosCalidad.Seleccion> preset = new Ciclo<>(texto("preset"), texto("preset.tooltip"), Impacto.ALTO,
                () -> Arrays.asList(ParametrosCalidad.Seleccion.values()), c.seleccion::get, c.seleccion::set,
                v -> texto("preset." + v.name().toLowerCase(Locale.ROOT)));
        java.util.function.BooleanSupplier personalizado =
                () -> preset.pendiente() == ParametrosCalidad.Seleccion.PERSONALIZADO;

        paginas.add(new Pagina(pestana("lod"), List.of(
                List.of(interruptor("lodActivo", Impacto.VARIABLE, c.lodActivo)),
                List.of(preset,
                        entero("fpsObjetivo", Impacto.NINGUNO, ParametrosCalidad.FPS_MIN, ParametrosCalidad.FPS_MAX, 1,
                                c.fpsObjetivo, v -> Component.literal(v + " FPS")).siempreQue(personalizado),
                        interruptor("autoAjuste", Impacto.BAJO, c.autoAjuste)),
                List.of(new Accion(texto("calibrar"), texto("calibrar.tooltip"), Impacto.NINGUNO,
                        () -> texto(SesionCalibracion.enCurso() ? "calibrar.accion.cancelar" : "calibrar.accion"),
                        () -> {
                            if (SesionCalibracion.enCurso()) {
                                SesionCalibracion.cancelarManual();
                            } else {
                                SesionCalibracion.iniciar(presetParaCalibrar(preset.pendiente()));
                            }
                        })
                        .conDescripcion(() -> texto(SesionCalibracion.enCurso() ? "calibrar.cancelar.tooltip"
                                : Minecraft.getInstance().level == null ? "calibrar.tooltip" : "calibrar.enMundo"))
                        .siempreQue(() -> SesionCalibracion.enCurso() || Minecraft.getInstance().level == null)))));

        Interruptor texturas = interruptor("texturasLod", Impacto.BAJO, c.texturasLod);
        Interruptor curvatura = interruptor("curvatura", Impacto.NINGUNO, c.curvatura);
        paginas.add(new Pagina(pestana("calidad"), List.of(
                List.of(texturas,
                        interruptor("texturasComoTerreno", Impacto.NINGUNO, c.texturasComoTerreno)
                                .siempreQue(texturas::pendiente),
                        interruptor("oclusionAmbiental", Impacto.NINGUNO, c.oclusionAmbiental),
                        interruptor("fundidoNiveles", Impacto.BAJO, c.fundidoNiveles),
                        interruptor("oclusionPantalla", Impacto.MEDIO, c.oclusionPantalla)
                                .siempreQue(() -> !com.example.minecraftlodmod.render.RenderLod.conVulkanMod()),
                        interruptor("nieblaLluvia", Impacto.NINGUNO, c.nieblaLluvia),
                        interruptor("nieblaSinDatos", Impacto.NINGUNO, c.nieblaSinDatos)
                                .siempreQue(() -> !com.example.minecraftlodmod.render.RenderLod.conVulkanMod()),
                        decimal("neblinaAtmosferica", Impacto.NINGUNO, 0, 1, 0.05, c.neblinaAtmosferica, "")
                                .siempreQue(() -> !com.example.minecraftlodmod.render.RenderLod.conVulkanMod())),
                List.of(interruptor("nubesLejanas", Impacto.BAJO, c.nubesLejanas)
                                .siempreQue(() -> !com.example.minecraftlodmod.render.RenderLod.conVulkanMod()),
                        curvatura,
                        new Ciclo<>(texto("radioCurvaturaKm"), texto("radioCurvaturaKm.tooltip"), Impacto.NINGUNO,
                                () -> RADIOS_PLANETA, c.radioCurvaturaKm::get, c.radioCurvaturaKm::set,
                                PantallaLod::textoRadio).siempreQue(curvatura::pendiente),
                        interruptor("horizonteReal", Impacto.VARIABLE, c.horizonteReal)
                                .siempreQue(curvatura::pendiente)),
                List.of(decimal("pixelesMaximos", Impacto.ALTO, 1, 16, 0.5, c.pixelesMaximos, " px"),
                        interruptor("descartarCuevas", Impacto.BAJO, c.descartarCuevas),
                        interruptor("ocultarTapado", Impacto.BAJO, c.ocultarTapado)),
                List.of(entero("radioLodChunks", Impacto.ALTO, ParametrosCalidad.RADIO_MIN, ParametrosCalidad.RADIO_MAX,
                                32, c.radioLodChunks, v -> Component.literal(v + " chunks")).siempreQue(personalizado),
                        decimal("umbralPx", Impacto.ALTO, ParametrosCalidad.UMBRAL_MIN, ParametrosCalidad.UMBRAL_MAX,
                                0.25, c.umbralPx, " px").siempreQue(personalizado),
                        entero("hilosGeneracion", Impacto.MEDIO, ParametrosCalidad.HILOS_MIN,
                                Math.max(2, Math.min(ParametrosCalidad.HILOS_MAX, ConfigLod.nucleosCpu())), 1,
                                c.hilosGeneracion, v -> Component.literal(String.valueOf(v))).siempreQue(personalizado),
                        entero("cacheRamMb", Impacto.BAJO, ParametrosCalidad.CACHE_MIN_MB, ParametrosCalidad.CACHE_MAX_MB,
                                32, c.cacheRamMb, v -> Component.literal(v + " MB")).siempreQue(personalizado),
                        entero("colapsoDesdeNivel", Impacto.MEDIO, ParametrosCalidad.COLAPSO_MIN,
                                ParametrosCalidad.COLAPSO_MAX, 1, c.colapsoDesdeNivel,
                                v -> Component.literal(String.valueOf(v))).siempreQue(personalizado)))));

        paginas.add(new Pagina(pestana("generacion"), List.of(
                List.of(interruptor("generacionAproximada", Impacto.MEDIO, c.generacionAproximada)),
                List.of(interruptor("pregenerar", Impacto.ALTO, c.pregenerar),
                        entero("radioPregeneracion", Impacto.NINGUNO, 16, ParametrosCalidad.RADIO_MAX, 16,
                                c.radioPregeneracion, v -> Component.literal(v + " chunks"))))));

        List<ModoEscalado> modos = Escalado.modosDisponibles();
        paginas.add(new Pagina(pestana("experimental"), List.of(
                List.of(interruptor("hudRendimiento", Impacto.BAJO, c.hudRendimiento),
                        interruptor("logDepuracion", Impacto.BAJO, c.logDepuracion)),
                List.of(new Ciclo<>(texto("escalado"), texto("escalado.tooltip"), Impacto.VARIABLE, () -> modos,
                                () -> CompatibilidadEscalado.efectivo(c.escalado.get(), modos), c.escalado::set,
                                v -> texto("escalado." + v.name().toLowerCase(Locale.ROOT))),
                        entero("fsrEscalaPorcentaje", Impacto.ALTO, 50, 99, 1, c.fsrEscalaPorcentaje,
                                v -> Component.literal(v + "%")),
                        decimal("fsrNitidez", Impacto.NINGUNO, 0, 2, 0.05, c.fsrNitidez, ""),
                        interruptor("escaladoSoloSiGana", Impacto.NINGUNO, c.escaladoSoloSiGana),
                        interruptor("invertirJitter", Impacto.NINGUNO, c.invertirJitter)),
                List.of(new Accion(texto("vulkan"), Component.empty(), Impacto.VARIABLE,
                        () -> texto("vulkan." + ConmutadorVulkan.estado().name().toLowerCase(Locale.ROOT)),
                        ConmutadorVulkan::alternar)
                        .conDescripcion(() -> texto("vulkan." + ConmutadorVulkan.estado().name()
                                .toLowerCase(Locale.ROOT) + ".tooltip"))
                        .siempreQue(() -> ConmutadorVulkan.estado() != ConmutadorVulkan.Estado.NO_DISPONIBLE)))));
    }

    private static Component pestana(String clave) {
        return Component.translatable(CLAVE_PANTALLA + "pestana." + clave);
    }

    /** Pantalla de video vanilla, por si hace falta algo que esta no tiene; al volver, esta de nuevo. */
    private void abrirOriginal() {
        aplicar();
        PantallaConfig.abrirOriginalUnaVez();
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new net.minecraft.client.gui.screens.options.VideoSettingsScreen(
                new PantallaLod(anterior, paginaActual), mc, mc.options));
    }

    /** Radios de planeta para la curvatura, en km: la Tierra, Marte, la Luna y planetas de juguete. */
    private static final List<Integer> RADIOS_PLANETA = List.of(6371, 3390, 1737, 1000, 500, 200, 100, 50, 20, 10);

    private static Component textoRadio(int km) {
        String nombre = switch (km) {
            case 6371 -> "tierra";
            case 3390 -> "marte";
            case 1737 -> "luna";
            default -> null;
        };
        return nombre == null ? Component.literal(km + " km")
                : Component.translatable(CLAVE + "radioCurvaturaKm." + nombre, km);
    }

    private static Component texto(String clave) {
        return Component.translatable(CLAVE + clave);
    }

    private static Interruptor interruptor(String clave, Impacto impacto,
                                           net.neoforged.neoforge.common.ModConfigSpec.BooleanValue valor) {
        return new Interruptor(texto(clave), texto(clave + ".tooltip"), impacto, valor);
    }

    private static Deslizador entero(String clave, Impacto impacto, int min, int max, int paso,
                                     net.neoforged.neoforge.common.ModConfigSpec.IntValue valor,
                                     java.util.function.Function<Integer, Component> formato) {
        return new Deslizador(texto(clave), texto(clave + ".tooltip"), impacto, min, max, paso,
                valor::get, valor::set, formato);
    }

    /** Decimal como deslizador entero en centésimas. */
    private static Deslizador decimal(String clave, Impacto impacto, double min, double max, double paso,
                                      net.neoforged.neoforge.common.ModConfigSpec.DoubleValue valor, String unidad) {
        return new Deslizador(texto(clave), texto(clave + ".tooltip"), impacto,
                (int) Math.round(min * 100), (int) Math.round(max * 100), (int) Math.round(paso * 100),
                () -> (int) Math.round(valor.get() * 100), v -> valor.set(v / 100.0),
                v -> Component.literal(String.format(Locale.ROOT, "%.2f", v / 100.0) + unidad));
    }

    /**
     * Preset desde el que calibrar según lo elegido en pantalla (aunque no
     * se haya aplicado): AUTOMATICO parte de la recomendación por hardware,
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

    private List<Opcion> todas() {
        List<Opcion> lista = new ArrayList<>();
        for (Pagina p : paginas) {
            p.grupos().forEach(lista::addAll);
        }
        return lista;
    }

    private boolean hayCambios() {
        return todas().stream().anyMatch(Opcion::modificada);
    }

    private void aplicar() {
        video.aplicar();
        todas().stream().filter(Opcion::modificada).forEach(Opcion::aplicar);
        ConfigLod.SPEC_CLIENTE.save();
    }

    // ------------------------------------------------------------------ geometría

    private int anchoFilas() {
        return Math.min(280, Math.max(170, (int) (width * 0.55) - 2 * MARGEN));
    }

    private int finOpciones() {
        return height - 32;
    }

    /** Alto total del contenido de la página actual (para el desplazamiento). */
    private int altoContenido() {
        int alto = 0;
        for (List<Opcion> grupo : paginas.get(paginaActual).grupos()) {
            alto += grupo.size() * ALTO_FILA + ESPACIO_GRUPO;
        }
        return alto;
    }

    private int xPestana(int indice) {
        int x = MARGEN;
        for (int i = 0; i < indice; i++) {
            x += font.width(paginas.get(i).titulo()) + 16 + 2;
        }
        return x;
    }

    private record Boton(Component texto, int x, int y, int ancho, boolean activo, Runnable accion) {
        boolean contiene(double mx, double my) {
            return mx >= x && mx < x + ancho && my >= y && my < y + 20;
        }
    }

    private List<Boton> botones() {
        int y = height - 26, ancho = 70;
        int xHecho = width - MARGEN - ancho;
        int xAplicar = xHecho - ancho - 4;
        int xDeshacer = xAplicar - ancho - 4;
        boolean cambios = hayCambios();
        return List.of(
                new Boton(Component.translatable(CLAVE_PANTALLA + "deshacer"), xDeshacer, y, ancho, cambios,
                        () -> todas().forEach(Opcion::deshacer)),
                new Boton(Component.translatable(CLAVE_PANTALLA + "aplicar"), xAplicar, y, ancho, cambios, this::aplicar),
                new Boton(Component.translatable("gui.done"), xHecho, y, ancho, true, this::onClose));
    }

    // ------------------------------------------------------------------ dibujo

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        super.render(g, mouseX, mouseY, delta);
        dibujarPestanas(g, mouseX, mouseY);
        bajoMouse = dibujarOpciones(g, mouseX, mouseY);
        dibujarDescripcion(g);
        for (Boton b : botones()) {
            boolean encima = b.activo() && b.contiene(mouseX, mouseY);
            g.fill(b.x(), b.y(), b.x() + b.ancho(), b.y() + 20, encima ? FONDO_RESALTADO : FONDO);
            int color = b.activo() ? OpcionesLod.BLANCO : OpcionesLod.GRIS;
            g.drawCenteredString(font, b.texto(), b.x() + b.ancho() / 2, b.y() + 6, color);
        }
        Component titulo = paginaActual >= PAGINA_LOD ? Component.literal("Minecraft LOD") : this.title;
        int xTitulo = width - MARGEN - font.width(titulo);
        if (xTitulo > xPestana(paginas.size()) + 8) {
            g.drawString(font, titulo, xTitulo, MARGEN + 5, OpcionesLod.GRIS);
        }
    }

    private void dibujarPestanas(GuiGraphics g, int mouseX, int mouseY) {
        for (int i = 0; i < paginas.size(); i++) {
            Component t = paginas.get(i).titulo();
            int x = xPestana(i), ancho = font.width(t) + 16;
            boolean encima = mouseX >= x && mouseX < x + ancho && mouseY >= MARGEN && mouseY < MARGEN + ALTO_PESTANA;
            boolean elegida = i == paginaActual;
            g.fill(x, MARGEN, x + ancho, MARGEN + ALTO_PESTANA, elegida || encima ? FONDO_RESALTADO : FONDO);
            if (elegida) {
                g.fill(x, MARGEN + ALTO_PESTANA - 1, x + ancho, MARGEN + ALTO_PESTANA, OpcionesLod.ACENTO);
            }
            g.drawString(font, t, x + 8, MARGEN + 5, elegida ? OpcionesLod.ACENTO : OpcionesLod.BLANCO);
        }
    }

    /** @return la opción bajo el mouse, o null */
    private Opcion dibujarOpciones(GuiGraphics g, int mouseX, int mouseY) {
        int ancho = anchoFilas();
        int y = Y_OPCIONES - (int) desplazamiento;
        Opcion encima = null;
        g.enableScissor(MARGEN, Y_OPCIONES, MARGEN + ancho, finOpciones());
        for (List<Opcion> grupo : paginas.get(paginaActual).grupos()) {
            for (Opcion o : grupo) {
                boolean dentro = mouseX >= MARGEN && mouseX < MARGEN + ancho && mouseY >= y && mouseY < y + ALTO_FILA
                        && mouseY >= Y_OPCIONES && mouseY < finOpciones();
                boolean activa = o.habilitada();
                if (dentro) {
                    encima = o;
                }
                g.fill(MARGEN, y, MARGEN + ancho, y + ALTO_FILA, dentro && activa ? FONDO_RESALTADO : FONDO);
                Component nombre = o.modificada()
                        ? o.nombre.copy().withStyle(ChatFormatting.ITALIC) : o.nombre;
                int color = !activa ? OpcionesLod.GRIS : o.modificada() ? OpcionesLod.MODIFICADO : OpcionesLod.BLANCO;
                int lugar = ancho - o.anchoControl(font) - 12;
                if (font.width(nombre) > lugar) {
                    // No entra al lado del control: se corta con "…" (el completo está en el panel).
                    String corto = font.plainSubstrByWidth(nombre.getString(), Math.max(0, lugar - font.width("…")));
                    nombre = Component.literal(corto + "…").withStyle(nombre.getStyle());
                }
                g.drawString(font, nombre, MARGEN + 6, y + 5, color);
                o.dibujarControl(g, font, MARGEN, y, ancho, ALTO_FILA, activa);
                y += ALTO_FILA;
            }
            y += ESPACIO_GRUPO;
        }
        g.disableScissor();
        // Barra de desplazamiento si no entra todo.
        int visible = finOpciones() - Y_OPCIONES, total = altoContenido();
        if (total > visible) {
            int alto = Math.max(12, visible * visible / total);
            int yBarra = Y_OPCIONES + (int) ((visible - alto) * desplazamiento / (total - visible));
            g.fill(MARGEN + ancho + 2, yBarra, MARGEN + ancho + 4, yBarra + alto, 0x80FFFFFF);
        }
        return encima;
    }

    private void dibujarDescripcion(GuiGraphics g) {
        int x = MARGEN + anchoFilas() + 12;
        int ancho = Math.min(280, width - x - MARGEN);
        if (ancho < 80) {
            return; // ventana muy angosta: sin panel
        }
        Opcion o = bajoMouse;
        List<FormattedCharSequence> lineas = new ArrayList<>();
        Component titulo;
        Component pie = null;
        if (o == null) {
            titulo = paginas.get(paginaActual).titulo();
            lineas.addAll(font.split(Component.translatable(CLAVE_PANTALLA + "ayuda"), ancho - 12));
        } else {
            titulo = o.nombre;
            lineas.addAll(font.split(o.descripcion(), ancho - 12));
            if (o.impacto != Impacto.NINGUNO) {
                pie = Component.translatable(CLAVE_PANTALLA + "impacto",
                        Component.translatable(CLAVE_PANTALLA + "impacto." + o.impacto.name().toLowerCase(Locale.ROOT)));
            }
            if (!o.habilitada()) {
                lineas.add(FormattedCharSequence.EMPTY);
                lineas.addAll(font.split(Component.translatable(CLAVE_PANTALLA + "deshabilitada")
                        .withStyle(ChatFormatting.GRAY), ancho - 12));
            }
        }
        int alto = 6 + 10 + 4 + lineas.size() * 10 + (pie != null ? 14 : 0) + 4;
        int y = Y_OPCIONES;
        g.fill(x, y, x + ancho, y + alto, FONDO_RESALTADO);
        g.fill(x, y, x + 2, y + alto, OpcionesLod.ACENTO);
        g.drawString(font, titulo, x + 8, y + 6, OpcionesLod.ACENTO);
        int yl = y + 20;
        for (FormattedCharSequence linea : lineas) {
            g.drawString(font, linea, x + 8, yl, 0xFFE0E0E0);
            yl += 10;
        }
        if (pie != null) {
            g.drawString(font, pie, x + 8, yl + 4, OpcionesLod.GRIS);
        }
    }

    // ------------------------------------------------------------------ entrada

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int boton) {
        for (int i = 0; i < paginas.size(); i++) {
            int x = xPestana(i), ancho = font.width(paginas.get(i).titulo()) + 16;
            if (mouseX >= x && mouseX < x + ancho && mouseY >= MARGEN && mouseY < MARGEN + ALTO_PESTANA) {
                if (i != paginaActual) {
                    paginaActual = i;
                    desplazamiento = 0;
                    sonido();
                }
                return true;
            }
        }
        for (Boton b : botones()) {
            if (b.contiene(mouseX, mouseY)) {
                if (b.activo()) {
                    sonido();
                    b.accion().run();
                }
                return true;
            }
        }
        Opcion o = opcionEn(mouseX, mouseY);
        if (o != null && o.habilitada()) {
            o.click(mouseX - MARGEN, anchoFilas(), boton);
            if (o instanceof Deslizador) {
                arrastrando = o;
            } else {
                sonido();
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, boton);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int boton, double dx, double dy) {
        if (arrastrando != null) {
            arrastrando.arrastrar(mouseX - MARGEN, anchoFilas());
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, boton, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int boton) {
        if (arrastrando != null) {
            arrastrando = null;
            sonido();
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, boton);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int sobra = altoContenido() - (finOpciones() - Y_OPCIONES);
        if (sobra > 0) {
            desplazamiento = Math.max(0, Math.min(sobra, desplazamiento - scrollY * ALTO_FILA));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int tecla, int scancode, int modificadores) {
        // Ctrl+Z deshace, Ctrl+S aplica (como en un editor).
        if (Screen.hasControlDown() && tecla == GLFW.GLFW_KEY_Z) {
            todas().forEach(Opcion::deshacer);
            return true;
        }
        if (Screen.hasControlDown() && tecla == GLFW.GLFW_KEY_S) {
            aplicar();
            return true;
        }
        return super.keyPressed(tecla, scancode, modificadores);
    }

    private Opcion opcionEn(double mouseX, double mouseY) {
        if (mouseX < MARGEN || mouseX >= MARGEN + anchoFilas() || mouseY < Y_OPCIONES || mouseY >= finOpciones()) {
            return null;
        }
        int y = Y_OPCIONES - (int) desplazamiento;
        for (List<Opcion> grupo : paginas.get(paginaActual).grupos()) {
            for (Opcion o : grupo) {
                if (mouseY >= y && mouseY < y + ALTO_FILA) {
                    return o;
                }
                y += ALTO_FILA;
            }
            y += ESPACIO_GRUPO;
        }
        return null;
    }

    private static void sonido() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    /** Cerrar (Hecho o Esc) aplica lo pendiente: nada se pierde sin querer. */
    @Override
    public void onClose() {
        if (hayCambios()) {
            aplicar();
        }
        Minecraft.getInstance().setScreen(anterior);
    }
}
