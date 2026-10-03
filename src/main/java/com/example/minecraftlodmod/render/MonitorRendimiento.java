package com.example.minecraftlodmod.render;

import com.example.minecraftlodmod.MinecraftLodMod;
import com.example.minecraftlodmod.config.ConfigLod;
import com.example.minecraftlodmod.config.ParametrosCalidad;
import com.example.minecraftlodmod.generation.GeneradorLocal;
import com.example.minecraftlodmod.generation.PregeneradorChunks;
import com.mojang.blaze3d.platform.GlUtil;
import com.mojang.logging.LogUtils;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.slf4j.Logger;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Monitor de rendimiento del cliente: mide cada cuadro y, una vez por
 * segundo, arma una {@link Muestra} con FPS, tiempos de cuadro, CPU, RAM,
 * tick del servidor integrado, costo del LOD y ritmo de generación.
 *
 *  - HUD (opción {@code hudRendimiento}), arriba a la izquierda, oculto con F1
 *    o con F3 abierto: FPS | MIN | AVG (desde que se entró al mundo), CPU %,
 *    RAM, GPU % y VRAM, con colores según el valor ({@link ColoresHud}); debajo,
 *    una línea con el estado del LOD.
 *  - Log de depuración (opción {@code logDepuracion}): una línea por segundo
 *    en {@code logs/minecraftlodmod-depuracion.log} con todo eso más la
 *    posición y velocidad del jugador, y líneas de EVENTO (entrar o salir de
 *    un mundo, cambios de config, tirones de FPS). Separado del
 *    latest.log para poder mandarlo y compararlo entre equipos.
 *
 * GPU % y VRAM: en Windows, los contadores del sistema ({@link MedidorGpuWindows});
 * si no, el tiempo de GPU del cuadro ({@link BalanceCpuGpu}, sin dato con Vulkan)
 * y {@link MedidorVram}.
 */
public final class MonitorRendimiento {

    private static final Logger LOG = LogUtils.getLogger();
    static final long VENTANA_NANOS = 1_000_000_000L;
    /** Un cuadro que tarda más que esto y más que 3× el promedio es un tirón (se anota en el log). */
    static final double TIRON_MINIMO_MS = 50;
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** Una muestra por segundo. */
    public record Muestra(double fps, double promedioMs, double peorMs, double fpsUnoPorCientoBajo,
                          double cpuJuego, double cpuSistema, long ramUsadaMb, long ramMaximaMb,
                          double msServidor, RenderLod.Resumen lod, double extraidosPorSegundo,
                          double aproximadosPorSegundo, int pendientes, PregeneradorChunks.Estado pregeneracion,
                          double gpuPorcentaje, long vramUsadaMb, long vramTotalMb, long vramLibreMb,
                          double fpsMinimo, double fpsPromedio) {
    }

    /** MIN y AVG del HUD: desde que se entró al mundo, sin los primeros segundos (carga). */
    static final long DESCARTE_SESION_NANOS = 5_000_000_000L;
    private long inicioSesionNanos = System.nanoTime();
    private double fpsMinimoSesion = Double.POSITIVE_INFINITY, sumaFpsSesion;
    private int muestrasSesion;

    private final RenderLod render;
    private final GeneradorLocal generador;
    private final BalanceCpuGpu balance;
    private final EstadisticaFrames frames = new EstadisticaFrames();
    private final com.sun.management.OperatingSystemMXBean so;

    private long ultimoFrameNanos, inicioVentanaNanos = System.nanoTime();
    private long extraidosAntes, aproximadosAntes;
    private double promedioAnteriorMs;
    private volatile Muestra ultima;
    /** Quien recibe cada muestra (el benchmark); null = nadie. Solo hilo de render. */
    private static java.util.function.Consumer<Muestra> oyente;

    /** El benchmark recibe la muestra de cada segundo; null para dejar de escuchar. */
    public static void escuchar(java.util.function.Consumer<Muestra> nuevo) {
        oyente = nuevo;
    }
    private BufferedWriter log;
    private Vec3 posicionAnterior;

    public MonitorRendimiento(RenderLod render, GeneradorLocal generador, BalanceCpuGpu balance) {
        this.render = render;
        this.generador = generador;
        this.balance = balance;
        var bean = ManagementFactory.getOperatingSystemMXBean();
        this.so = bean instanceof com.sun.management.OperatingSystemMXBean s ? s : null;
    }

    /** Bus del mod, solo cliente. */
    public void registrarCapa(RegisterGuiLayersEvent evento) {
        evento.registerAboveAll(ResourceLocation.fromNamespaceAndPath(MinecraftLodMod.MOD_ID, "hud_rendimiento"),
                this::dibujarHud);
    }

    @SubscribeEvent
    public void alTerminarCuadro(RenderFrameEvent.Post evento) {
        long ahora = System.nanoTime();
        if (ultimoFrameNanos != 0) {
            double ms = (ahora - ultimoFrameNanos) / 1e6;
            frames.agregar(ms);
            if (ms > TIRON_MINIMO_MS && ms > 3 * promedioAnteriorMs && promedioAnteriorMs > 0) {
                evento("TIRON", String.format(Locale.ROOT, "cuadro de %.1f ms (promedio %.1f ms)", ms, promedioAnteriorMs));
            }
        }
        ultimoFrameNanos = ahora;
        if (configCambio) {
            configCambio = false;
            evento("CONFIG", resumenConfig());
        }
        if (ahora - inicioVentanaNanos < VENTANA_NANOS) {
            return;
        }
        EstadisticaFrames.Resumen r = frames.cerrar((ahora - inicioVentanaNanos) / 1e9);
        double segundos = (ahora - inicioVentanaNanos) / 1e9;
        inicioVentanaNanos = ahora;
        promedioAnteriorMs = r.promedioMs();
        if (Minecraft.getInstance().level != null && ahora - inicioSesionNanos > DESCARTE_SESION_NANOS
                && r.fps() > 0) {
            fpsMinimoSesion = Math.min(fpsMinimoSesion, r.fps());
            sumaFpsSesion += r.fps();
            muestrasSesion++;
        }
        ultima = muestrear(r, segundos);
        escribirMuestra(ultima);
        java.util.function.Consumer<Muestra> o = oyente;
        if (o != null) {
            o.accept(ultima);
        }
    }

    private Muestra muestrear(EstadisticaFrames.Resumen r, double segundos) {
        Minecraft mc = Minecraft.getInstance();
        Runtime rt = Runtime.getRuntime();
        MinecraftServer servidor = mc.getSingleplayerServer();
        long extraidos = generador.chunksExtraidosTotal(), aproximados = generador.chunksAproximadosTotal();
        double porSegExtraidos = (extraidos - extraidosAntes) / segundos;
        double porSegAproximados = (aproximados - aproximadosAntes) / segundos;
        extraidosAntes = extraidos;
        aproximadosAntes = aproximados;
        boolean conServidor = servidor != null && generador.store() != null;
        boolean medir = ConfigLod.CLIENTE.hudRendimiento.get() || ConfigLod.CLIENTE.logDepuracion.get()
                || com.example.minecraftlodmod.benchmark.SesionCalibracion.enCurso();
        // Primero los contadores de Windows (los del Administrador de tareas: cualquier placa, también con
        // Vulkan); si no hay, la medición del juego.
        MedidorGpuWindows windows = MedidorGpuWindows.INSTANCIA;
        double gpu = medir ? windows.gpuPorcentaje() : -1;
        if (gpu < 0) {
            double gpuMs = balance.ultimoGpuMs();
            gpu = Double.isNaN(gpuMs) || r.promedioMs() <= 0 ? -1 : Math.min(100, 100 * gpuMs / r.promedioMs());
        }
        MedidorVram.Vram vram = medir ? MedidorVram.INSTANCIA.medir() : MedidorVram.Vram.SIN_DATO;
        if (medir && windows.vramUsadaMb() >= 0 && windows.vramTotalMb() > 0) {
            vram = new MedidorVram.Vram(windows.vramUsadaMb(), windows.vramTotalMb(), -1);
        }
        return new Muestra(r.fps(), r.promedioMs(), r.peorMs(), r.fpsUnoPorCientoBajo(),
                so == null ? -1 : so.getProcessCpuLoad() * 100, so == null ? -1 : so.getCpuLoad() * 100,
                (rt.totalMemory() - rt.freeMemory()) >> 20, rt.maxMemory() >> 20,
                servidor == null ? -1 : servidor.getAverageTickTimeNanos() / 1e6,
                mc.level == null ? null : render.resumen(),
                conServidor ? porSegExtraidos : 0, conServidor ? porSegAproximados : 0,
                conServidor ? generador.cantidadPendientes() : 0,
                conServidor ? generador.estadoPregeneracion() : null,
                gpu, vram.usadaMb(), vram.totalMb(), vram.libreMb(),
                muestrasSesion == 0 ? -1 : fpsMinimoSesion, muestrasSesion == 0 ? -1 : sumaFpsSesion / muestrasSesion);
    }

    private void dibujarHud(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        Muestra m = ultima;
        if (m == null || !ConfigLod.CLIENTE.hudRendimiento.get() || mc.options.hideGui
                || mc.getDebugOverlay().showDebugScreen()) {
            return;
        }
        Font fuente = mc.font;
        boolean detalle = ConfigLod.CLIENTE.hudDetalleLod.get();
        if (m != hudDe || detalle != hudConDetalle) {
            hudConDetalle = detalle;
            // Los datos cambian una vez por ventana: armar y medir el texto en cada cuadro era
            // trabajo (y basura) por nada.
            hudDe = m;
            hudLineas = lineasHud(m);
            hudAncho = 0;
            for (Component linea : hudLineas) {
                hudAncho = Math.max(hudAncho, fuente.width(linea));
            }
        }
        Component[] lineas = hudLineas;
        int anchoMaximo = hudAncho;
        float escala = Math.min(1f, (g.guiWidth() - 6f) / Math.max(1, anchoMaximo));
        g.pose().pushPose();
        g.pose().scale(escala, escala, 1f);
        int y = 3;
        for (Component linea : lineas) {
            // Arriba a la izquierda, texto con sombra y sin fondo (como el contador de FPS clásico).
            g.drawString(fuente, linea, 3, y, ColoresHud.BLANCO, true);
            y += fuente.lineHeight + 2;
        }
        g.pose().popPose();
    }

    /** Texto del HUD de la última muestra ({@link #dibujarHud}). */
    private Muestra hudDe;
    private Component[] hudLineas;
    private boolean hudConDetalle;
    private int hudAncho;

    private Component[] lineasHud(Muestra m) {
        Component linea1 = lineaPrincipal(m);
        if (m.lod() == null || !ConfigLod.CLIENTE.hudDetalleLod.get()) {
            return new Component[]{linea1};
        }
        net.minecraft.network.chat.MutableComponent linea2 = Component.literal("v" + version() + "  ·  ")
                .append(Component.translatable("minecraftlodmod.hud.linea2",
                m.lod().activo() ? decimal(m.lod().msDibujo()) : "off", millones(m.lod().verticesDibujados()),
                m.lod().piezas(), m.lod().vramMb(), m.lod().mallasEnCola(), entero(m.extraidosPorSegundo()),
                m.pendientes(), entero(m.aproximadosPorSegundo()), pregeneracion(m.pregeneracion())))
                .append(Component.translatable("minecraftlodmod.hud.limite", balance.diagnostico()));
        if (com.example.minecraftlodmod.network.EspejoServidor.store() != null) {
            linea2 = linea2.append(Component.translatable("minecraftlodmod.hud.remoto",
                    com.example.minecraftlodmod.network.EspejoServidor.resumen()));
        }
        return new Component[]{linea1, linea2};
    }

    /** "45 | MIN 45 | AVG 30 | CPU 34% | RAM 2.1/4.0 GB | GPU 70% | VRAM 1.2/6.0 GB", con colores. */
    static Component lineaPrincipal(Muestra m) {
        net.minecraft.network.chat.MutableComponent l = Component.empty();
        l.append(valor(entero(m.fps()), ColoresHud.fps(m.fps())));
        separador(l).append(etiqueta("MIN ")).append(valor(entero(m.fpsMinimo()), ColoresHud.fps(m.fpsMinimo())));
        separador(l).append(etiqueta("AVG ")).append(valor(entero(m.fpsPromedio()), ColoresHud.fps(m.fpsPromedio())));
        separador(l).append(etiqueta("CPU ")).append(valor(porcentaje(m.cpuJuego()), ColoresHud.uso(m.cpuJuego())));
        double ram = m.ramMaximaMb() > 0 ? 100.0 * m.ramUsadaMb() / m.ramMaximaMb() : -1;
        separador(l).append(etiqueta("RAM ")).append(valor(gb(m.ramUsadaMb()) + "/" + gb(m.ramMaximaMb()) + " GB",
                ColoresHud.uso(ram)));
        separador(l).append(etiqueta("GPU ")).append(valor(porcentaje(m.gpuPorcentaje()), ColoresHud.uso(m.gpuPorcentaje())));
        separador(l).append(etiqueta("VRAM "));
        if (m.vramUsadaMb() >= 0 && m.vramTotalMb() > 0) {
            l.append(valor(gb(m.vramUsadaMb()) + "/" + gb(m.vramTotalMb()) + " GB",
                    ColoresHud.uso(100.0 * m.vramUsadaMb() / m.vramTotalMb())));
        } else if (m.vramLibreMb() >= 0) {
            l.append(valor(gb(m.vramLibreMb()) + " GB", ColoresHud.BLANCO))
                    .append(etiqueta(" " + Component.translatable("minecraftlodmod.hud.libre").getString()));
        } else {
            l.append(valor("-", ColoresHud.GRIS));
        }
        return l;
    }

    private static net.minecraft.network.chat.MutableComponent separador(net.minecraft.network.chat.MutableComponent l) {
        return l.append(Component.literal(" | ").withStyle(s -> s.withColor(ColoresHud.GRIS)));
    }

    private static Component etiqueta(String texto) {
        return Component.literal(texto).withStyle(s -> s.withColor(ColoresHud.BLANCO));
    }

    private static Component valor(String texto, int color) {
        return Component.literal(texto).withStyle(s -> s.withColor(color));
    }

    private static String porcentaje(double v) {
        return v < 0 || Double.isNaN(v) ? "-" : Math.round(v) + "%";
    }

    private static String gb(long mb) {
        return String.format(Locale.ROOT, "%.1f", mb / 1024.0);
    }

    private static String pregeneracion(PregeneradorChunks.Estado e) {
        if (e == null || !ConfigLod.CLIENTE.pregenerar.get()) {
            return "off";
        }
        if (e.completo()) {
            return "ok";
        }
        return e.anillo() + "/" + e.radio();
    }

    // ---------------------------------------------------------------- log de depuración

    @SubscribeEvent
    public void alConectar(ClientPlayerNetworkEvent.LoggingIn evento) {
        inicioSesionNanos = System.nanoTime();
        fpsMinimoSesion = Double.POSITIVE_INFINITY;
        sumaFpsSesion = 0;
        muestrasSesion = 0;
        evento("MUNDO", "entrada" + (Minecraft.getInstance().getSingleplayerServer() != null ? " (singleplayer)" : " (servidor)"));
        evento("CONFIG", resumenConfig());
    }

    @SubscribeEvent
    public void alDesconectar(ClientPlayerNetworkEvent.LoggingOut evento) {
        evento("MUNDO", "salida");
    }

    /**
     * Bus del mod: la config cambió (menú del mod o archivo). Llega desde el
     * hilo que vigila los archivos: acá solo se marca, y el hilo de render
     * escribe el evento en el próximo cuadro (el resumen consulta cosas que
     * solo se pueden leer desde ahí).
     */
    public void alRecargarConfig(ModConfigEvent.Reloading evento) {
        if (evento.getConfig().getSpec() == ConfigLod.SPEC_CLIENTE) {
            configCambio = true;
        }
    }

    private volatile boolean configCambio;

    private void escribirMuestra(Muestra m) {
        BufferedWriter w = abrirSiHaceFalta();
        if (w == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        StringBuilder s = new StringBuilder();
        s.append(LocalDateTime.now().format(HORA)).append(" MUESTRA");
        s.append(String.format(Locale.ROOT, " fps=%.0f ms=%.2f peor=%.1f bajo1%%=%.0f cpu=%.0f%% cpuSistema=%.0f%% ram=%d/%dMB servidor=%s",
                m.fps(), m.promedioMs(), m.peorMs(), m.fpsUnoPorCientoBajo(), m.cpuJuego(), m.cpuSistema(),
                m.ramUsadaMb(), m.ramMaximaMb(), m.msServidor() < 0 ? "-" : String.format(Locale.ROOT, "%.1fms", m.msServidor())));
        s.append(String.format(Locale.ROOT, " gpu=%s vram=%d/%dMB vramLibre=%dMB fpsMin=%.0f fpsProm=%.0f",
                m.gpuPorcentaje() < 0 ? "-" : String.format(Locale.ROOT, "%.0f%%", m.gpuPorcentaje()),
                m.vramUsadaMb(), m.vramTotalMb(), m.vramLibreMb(), m.fpsMinimo(), m.fpsPromedio()));
        if (mc.player != null) {
            Vec3 p = mc.player.position();
            double velocidad = posicionAnterior == null ? 0 : p.distanceTo(posicionAnterior);
            posicionAnterior = p;
            s.append(String.format(Locale.ROOT, " pos=%.0f,%.0f,%.0f dim=%s vel=%.1fb/s fov=%.0f",
                    p.x, p.y, p.z, mc.player.level().dimension().location(), velocidad,
                    mc.options.fov().get().doubleValue()));
        }
        if (m.lod() != null) {
            RenderLod.Resumen l = m.lod();
            s.append(String.format(Locale.ROOT, " lod=%s dibujo=%.2fms llamadas=%d vertDibujados=%d piezas=%d vert=%d vram=%dMB ocultas=%d mallasEnCola=%d radio=%d",
                    l.activo() ? "on" : "off", l.msDibujo(), l.llamadas(), l.verticesDibujados(), l.piezas(),
                    l.vertices(), l.vramMb(), l.ocultas(), l.mallasEnCola(), l.radioChunks()));
        }
        s.append(String.format(Locale.ROOT, " extraidos=%.0f/s pendientes=%d aproximados=%.0f/s pregen=%s",
                m.extraidosPorSegundo(), m.pendientes(), m.aproximadosPorSegundo(), pregeneracion(m.pregeneracion())));
        s.append(" balance=").append(balance.resumen());
        escribir(w, s.toString());
    }

    private void evento(String tipo, String texto) {
        BufferedWriter w = abrirSiHaceFalta();
        if (w != null) {
            escribir(w, LocalDateTime.now().format(HORA) + " EVENTO " + tipo + " " + texto);
        }
    }

    /** Abre el archivo al prender la opción y lo cierra al apagarla. */
    private BufferedWriter abrirSiHaceFalta() {
        boolean activo = ConfigLod.CLIENTE.logDepuracion.get();
        if (!activo) {
            cerrarLog();
            return null;
        }
        if (log != null) {
            return log;
        }
        try {
            Path archivo = Minecraft.getInstance().gameDirectory.toPath().resolve("logs")
                    .resolve("minecraftlodmod-depuracion.log");
            Files.createDirectories(archivo.getParent());
            log = Files.newBufferedWriter(archivo, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
            escribir(log, "");
            escribir(log, LocalDateTime.now() + " INICIO " + sistema());
            escribir(log, LocalDateTime.now().format(HORA) + " EVENTO CONFIG " + resumenConfig());
            LOG.info("LOD: log de depuración en {}", archivo);
        } catch (IOException e) {
            LOG.warn("LOD: no se pudo abrir el log de depuración", e);
            log = null;
        }
        return log;
    }

    private void escribir(BufferedWriter w, String linea) {
        try {
            w.write(linea);
            w.newLine();
            w.flush(); // una línea por segundo: si el juego se cuelga, lo último queda escrito
        } catch (IOException e) {
            LOG.warn("LOD: falló la escritura del log de depuración; se cierra", e);
            cerrarLog();
        }
    }

    private void cerrarLog() {
        if (log != null) {
            try {
                log.close();
            } catch (IOException ignorada) {
                // nada que hacer: el archivo queda como estaba
            }
            log = null;
        }
    }

    private static String sistema() {
        Minecraft mc = Minecraft.getInstance();
        Runtime rt = Runtime.getRuntime();
        return String.format(Locale.ROOT, "version=%s java=%s nucleos=%d ramMax=%dMB gpu=\"%s\" gl=\"%s\" ventana=%dx%d so=\"%s %s\" mods=%d",
                version(), System.getProperty("java.version"), rt.availableProcessors(), rt.maxMemory() >> 20,
                GlUtil.getRenderer(), GlUtil.getOpenGLVersion(), mc.getWindow().getWidth(), mc.getWindow().getHeight(),
                System.getProperty("os.name"), System.getProperty("os.version"),
                net.neoforged.fml.ModList.get().size());
    }

    /** Versión del mod (la de gradle.properties, la misma del nombre del jar). */
    public static String version() {
        return net.neoforged.fml.ModList.get().getModContainerById(MinecraftLodMod.MOD_ID)
                .map(c -> c.getModInfo().getVersion().toString()).orElse("?");
    }

    public static String resumenConfig() {
        ConfigLod.Cliente c = ConfigLod.CLIENTE;
        ParametrosCalidad q = ConfigLod.calidadCliente();
        Minecraft mc = Minecraft.getInstance();
        return String.format(Locale.ROOT, "preset=%s radio=%d umbral=%.2f hilos=%d cache=%dMB lod=%s texturas=%s cuevas=%s ao=%s aoCostados=%s ssao=%s agua=%s plantas=%s px=%.1f-%.1f relieve=%s aproximado=%s pregen=%s(%d) escalado=%s(%d%%) distanciaVanilla=%d graficos=%s",
                c.seleccion.get(), q.radioLodChunks(), q.umbralPx(), q.hilosGeneracion(), q.cacheRamMb(),
                c.lodActivo.get(), c.texturasLod.get(), c.descartarCuevas.get(), c.oclusionAmbiental.get(), c.oclusionCostados.get(),
                c.oclusionPantalla.get(), c.aguaTranslucida.get(), c.siluetasPlantas.get(), c.pixelesMinimos.get(), c.pixelesMaximos.get(),
                c.ocultarTapado.get(), c.generacionAproximada.get(), c.pregenerar.get(), c.radioPregeneracion.get(),
                c.escalado.get(), c.fsrEscalaPorcentaje.get(), mc.options.getEffectiveRenderDistance(),
                mc.options.graphicsMode().get());
    }

    private static String entero(double v) {
        return v < 0 ? "-" : String.valueOf(Math.round(v));
    }

    private static String decimal(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static String millones(long v) {
        return v >= 1_000_000 ? String.format(Locale.ROOT, "%.1fM", v / 1e6) : v >= 1000 ? (v / 1000) + "k" : String.valueOf(v);
    }
}
