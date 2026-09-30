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
 *  - HUD (opción {@code hudRendimiento}): dos líneas arriba al centro,
 *    ocultas con F1 o con F3 abierto.
 *  - Log de depuración (opción {@code logDepuracion}): una línea por segundo
 *    en {@code logs/minecraftlodmod-depuracion.log} con todo eso más la
 *    posición y velocidad del jugador, y líneas de EVENTO (entrar o salir de
 *    un mundo, cambios de config, tirones de FPS). Separado del
 *    latest.log para poder mandarlo y compararlo entre equipos.
 *
 * La GPU no se muestra: Minecraft solo la mide con F3 abierto.
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
                          double aproximadosPorSegundo, int pendientes, PregeneradorChunks.Estado pregeneracion) {
    }

    private final RenderLod render;
    private final GeneradorLocal generador;
    private final BalanceCpuGpu balance;
    private final EstadisticaFrames frames = new EstadisticaFrames();
    private final com.sun.management.OperatingSystemMXBean so;

    private long ultimoFrameNanos, inicioVentanaNanos = System.nanoTime();
    private long extraidosAntes, aproximadosAntes;
    private double promedioAnteriorMs;
    private volatile Muestra ultima;
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
        ultima = muestrear(r, segundos);
        escribirMuestra(ultima);
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
        return new Muestra(r.fps(), r.promedioMs(), r.peorMs(), r.fpsUnoPorCientoBajo(),
                so == null ? -1 : so.getProcessCpuLoad() * 100, so == null ? -1 : so.getCpuLoad() * 100,
                (rt.totalMemory() - rt.freeMemory()) >> 20, rt.maxMemory() >> 20,
                servidor == null ? -1 : servidor.getAverageTickTimeNanos() / 1e6,
                mc.level == null ? null : render.resumen(),
                conServidor ? porSegExtraidos : 0, conServidor ? porSegAproximados : 0,
                conServidor ? generador.cantidadPendientes() : 0,
                conServidor ? generador.estadoPregeneracion() : null);
    }

    private void dibujarHud(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        Muestra m = ultima;
        if (m == null || !ConfigLod.CLIENTE.hudRendimiento.get() || mc.options.hideGui
                || mc.getDebugOverlay().showDebugScreen()) {
            return;
        }
        Font fuente = mc.font;
        Component linea1 = Component.translatable("minecraftlodmod.hud.linea1",
                entero(m.fps()), decimal(m.promedioMs()), decimal(m.peorMs()), entero(m.fpsUnoPorCientoBajo()),
                entero(m.cpuJuego()), entero(m.cpuSistema()), m.ramUsadaMb(), m.ramMaximaMb(),
                m.msServidor() < 0 ? "-" : decimal(m.msServidor()));
        Component linea2 = m.lod() == null ? Component.empty() : Component.literal("v" + version() + "  ·  ")
                .append(Component.translatable("minecraftlodmod.hud.linea2",
                m.lod().activo() ? decimal(m.lod().msDibujo()) : "off", millones(m.lod().verticesDibujados()),
                m.lod().piezas(), m.lod().vramMb(), m.lod().mallasEnCola(), entero(m.extraidosPorSegundo()),
                m.pendientes(), entero(m.aproximadosPorSegundo()), pregeneracion(m.pregeneracion())))
                .append(Component.translatable("minecraftlodmod.hud.limite", balance.diagnostico()));
        // Con escala de interfaz grande las líneas no entran: se achica el texto hasta que entren.
        Component[] lineas = linea2.getString().isEmpty() ? new Component[]{linea1} : new Component[]{linea1, linea2};
        int anchoMaximo = 0;
        for (Component linea : lineas) {
            anchoMaximo = Math.max(anchoMaximo, fuente.width(linea));
        }
        float escala = Math.min(1f, (g.guiWidth() - 8f) / Math.max(1, anchoMaximo));
        g.pose().pushPose();
        g.pose().scale(escala, escala, 1f);
        float anchoVisible = g.guiWidth() / escala;
        int y = 2;
        for (Component linea : lineas) {
            int ancho = fuente.width(linea);
            int x = (int) ((anchoVisible - ancho) / 2);
            g.fill(x - 2, y - 1, x + ancho + 2, y + fuente.lineHeight, 0x90000000);
            g.drawString(fuente, linea, x, y, 0xFFFFFF, false);
            y += fuente.lineHeight + 2;
        }
        g.pose().popPose();
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
    static String version() {
        return net.neoforged.fml.ModList.get().getModContainerById(MinecraftLodMod.MOD_ID)
                .map(c -> c.getModInfo().getVersion().toString()).orElse("?");
    }

    private static String resumenConfig() {
        ConfigLod.Cliente c = ConfigLod.CLIENTE;
        ParametrosCalidad q = ConfigLod.calidadCliente();
        Minecraft mc = Minecraft.getInstance();
        return String.format(Locale.ROOT, "preset=%s radio=%d umbral=%.2f hilos=%d cache=%dMB lod=%s texturas=%s cuevas=%s ao=%s relieve=%s aproximado=%s pregen=%s(%d) escalado=%s(%d%%) distanciaVanilla=%d graficos=%s",
                c.seleccion.get(), q.radioLodChunks(), q.umbralPx(), q.hilosGeneracion(), q.cacheRamMb(),
                c.lodActivo.get(), c.texturasLod.get(), c.descartarCuevas.get(), c.oclusionAmbiental.get(),
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
