package com.example.minecraftlodmod.benchmark;

import com.example.minecraftlodmod.config.ConfigLod;
import com.example.minecraftlodmod.config.ParametrosCalidad;
import com.example.minecraftlodmod.config.QualityPreset;
import com.example.minecraftlodmod.render.MonitorRendimiento;
import com.mojang.blaze3d.platform.GlUtil;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Benchmark del LOD, lado cliente (sección 9). Solo cliente. Dos modos:
 *  - "Calibrar": busca el escalón más alto que alcanza el objetivo desde el
 *    preset elegido y lo guarda como config PERSONALIZADO.
 *  - "Medir rendimiento": mide la config actual tal cual, sin cambiarla.
 *
 * Flujo:
 *  1. Se abre el mundo de benchmark ({@link PuntosBenchmark}), creándolo la
 *     primera vez: seed fija, espectador, pacífico, sin ciclo de día ni clima
 *     ni mobs: la escena tiene que ser la misma en cada corrida.
 *  2. {@link CalibradorBenchmark} recorre escalones × puntos con el frame time
 *     de cada cuadro y, una vez por segundo, las métricas del monitor de
 *     rendimiento (GPU, CPU, RAM, servidor, vértices, llamadas, VRAM). En los
 *     puntos quietos espera a que el LOD esté listo antes de medir; el vuelo
 *     avanza en línea recta mientras mide.
 *  3. Escribe el informe en {@code .minecraft/minecraftlodmod/benchmark/}.
 *  4. Calibrando, guarda el resultado como config PERSONALIZADO.
 *  5. Cierra el mundo y vuelve al menú principal.
 *
 * Mientras corre, el auto-ajuste queda congelado (mediría con perillas
 * moviéndose) y la medición de GPU, forzada.
 *
 * Mover al jugador se hace directo sobre el servidor integrado (siempre es
 * singleplayer), no con comandos: no depende de que el mundo tenga trucos.
 *
 * La sesión vive SOLO dentro del mundo de benchmark: si el jugador sale
 * antes de que termine (o por algún motivo se abre otro mundo), se cancela
 * y se vuelve a la calidad de la config.
 */
public final class SesionCalibracion {

    private static final Logger LOG = LogUtils.getLogger();

    /** Puntos quietos: espera mínima, máxima (si el LOD no termina de armarse) y medición. */
    public static final double CALENTAMIENTO_MIN_MS = 8_000, CALENTAMIENTO_MAX_MS = 60_000, MEDICION_MS = 10_000;
    /** Vuelo: una espera corta en el punto de partida y después en movimiento. */
    public static final double CALENTAMIENTO_VUELO_MS = 3_000, MEDICION_VUELO_MS = 15_000;
    /** Cada cuánto se mueve al jugador en el vuelo (cada paso son {@code VELOCIDAD × esto} bloques). */
    static final long PASO_VUELO_NANOS = 250_000_000L;
    /** Frames más largos que esto (pausas, alt-tab, guardado) no se cuentan. */
    private static final double FRAME_MAXIMO_MS = 2_000;
    /** Muestras seguidas del monitor sin nada pendiente para dar el LOD por listo. */
    static final int MUESTRAS_LISTO = 2;

    private static volatile Consumer<ParametrosCalidad> aplicador = p -> { };

    /** Sesión en curso, o null. Solo hilo de render. */
    private static SesionCalibracion activa;

    private final CalibradorBenchmark calibrador;
    private final boolean soloMedir;
    private final String titulo;
    private long ultimoFrameNanos;
    private long ultimoPasoVueloNanos;
    private boolean posicionado;
    private int muestrasSinPendientes;
    /** Servidor integrado del mundo de benchmark; si el frame corre en otro, se cancela. */
    private MinecraftServer servidorBenchmark;

    private SesionCalibracion(EscalonesCalibracion tabla, boolean soloMedir, String titulo) {
        this.calibrador = new CalibradorBenchmark(tabla, ventanas());
        this.soloMedir = soloMedir;
        this.titulo = titulo;
    }

    static CalibradorBenchmark.Ventana[] ventanas() {
        List<PuntosBenchmark.Punto> puntos = PuntosBenchmark.PUNTOS;
        CalibradorBenchmark.Ventana[] v = new CalibradorBenchmark.Ventana[puntos.size()];
        for (int i = 0; i < v.length; i++) {
            v[i] = puntos.get(i).tipo() == PuntosBenchmark.Tipo.VUELO
                    ? new CalibradorBenchmark.Ventana(CALENTAMIENTO_VUELO_MS, CALENTAMIENTO_VUELO_MS, MEDICION_VUELO_MS, false)
                    : new CalibradorBenchmark.Ventana(CALENTAMIENTO_MIN_MS, CALENTAMIENTO_MAX_MS, MEDICION_MS, true);
        }
        return v;
    }

    /**
     * Quién aplica un escalón candidato al render de LOD (lo registra
     * render/). Se llama en el hilo de render al empezar cada escalón.
     */
    public static void asignarAplicador(Consumer<ParametrosCalidad> nuevo) {
        aplicador = nuevo;
    }

    public static boolean enCurso() {
        return activa != null;
    }

    /** "Calibrar", solo desde el menú principal (sin mundo abierto). */
    public static void iniciar(QualityPreset inicial) {
        EscalonesCalibracion tabla = EscalonesCalibracion.desde(inicial);
        empezar(new SesionCalibracion(tabla, false, "calibración desde " + inicial));
        LOG.info("LOD: calibración desde {} ({} escalones posibles)", inicial, tabla.escalones().size());
    }

    /** "Medir rendimiento" de la config actual, sin cambiarla. Solo desde el menú principal. */
    public static void medir() {
        empezar(new SesionCalibracion(EscalonesCalibracion.soloMedir(ConfigLod.calidadCliente()), true,
                "medición de la config actual"));
        LOG.info("LOD: medición de rendimiento con {}", ConfigLod.calidadCliente());
    }

    private static void empezar(SesionCalibracion sesion) {
        Minecraft mc = Minecraft.getInstance();
        if (activa != null || mc.level != null) {
            return;
        }
        activa = sesion;
        NeoForge.EVENT_BUS.register(activa);
        MonitorRendimiento.escuchar(activa::alMuestrear);
        if (mc.getLevelSource().levelExists(PuntosBenchmark.NOMBRE_MUNDO)) {
            mc.createWorldOpenFlows().openWorld(PuntosBenchmark.NOMBRE_MUNDO, SesionCalibracion::cancelar);
        } else {
            mc.createWorldOpenFlows().createFreshLevel(PuntosBenchmark.NOMBRE_MUNDO, ajustesMundo(),
                    new WorldOptions(PuntosBenchmark.SEED, true, false),
                    WorldPresets::createNormalWorldDimensions, new TitleScreen());
        }
    }

    private static LevelSettings ajustesMundo() {
        GameRules reglas = new GameRules();
        reglas.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        reglas.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        reglas.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        return new LevelSettings(PuntosBenchmark.NOMBRE_MUNDO, GameType.SPECTATOR, false, Difficulty.PEACEFUL,
                true, reglas, WorldDataConfiguration.DEFAULT);
    }

    private static void cancelar() {
        if (activa != null) {
            NeoForge.EVENT_BUS.unregister(activa);
            MonitorRendimiento.escuchar(null);
            activa = null;
        }
    }

    /** Botón "Cancelar" de la pantalla de config. */
    public static void cancelarManual() {
        abortar("cancelada por el jugador");
    }

    /** Sesión interrumpida: fuera la sesión y el render vuelve a la calidad de la config. */
    private static void abortar(String motivo) {
        if (activa == null) {
            return;
        }
        LOG.info("LOD: benchmark cancelado ({})", motivo);
        cancelar();
        aplicador.accept(ConfigLod.calidadCliente());
    }

    /** Salir del mundo (menú de pausa, desconexión) antes de terminar cancela la sesión. */
    @SubscribeEvent
    public void alSalir(ClientPlayerNetworkEvent.LoggingOut evento) {
        // Abrir un mundo desde el menú también dispara LoggingOut ANTES de entrar:
        // solo cuenta una vez que la sesión arrancó dentro del benchmark.
        if (!posicionado) {
            return;
        }
        abortar("se salió del mundo de benchmark");
    }

    private static boolean esMundoBenchmark(MinecraftServer servidor) {
        Path carpeta = servidor.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName();
        return carpeta != null && carpeta.toString().equals(PuntosBenchmark.NOMBRE_MUNDO);
    }

    /** Muestra de cada segundo del monitor: métricas para el punto y si el LOD terminó de armarse. */
    private void alMuestrear(MonitorRendimiento.Muestra m) {
        if (!posicionado) {
            return;
        }
        var lod = m.lod();
        boolean sinPendientes = lod != null && lod.mallasEnCola() == 0 && m.pendientes() == 0
                && m.extraidosPorSegundo() < 1 && m.aproximadosPorSegundo() < 1;
        muestrasSinPendientes = sinPendientes ? muestrasSinPendientes + 1 : 0;
        double gpuMs = m.gpuPorcentaje() < 0 ? -1 : m.gpuPorcentaje() / 100 * m.promedioMs();
        calibrador.registrarSegundo(gpuMs, m.cpuJuego(), m.ramUsadaMb(), m.msServidor(),
                lod == null ? -1 : lod.verticesDibujados(), lod == null ? -1 : lod.llamadas(),
                m.vramUsadaMb() < 0 ? -1 : m.vramUsadaMb());
    }

    @SubscribeEvent
    public void alTerminarFrame(RenderFrameEvent.Post evento) {
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer servidor = mc.getSingleplayerServer();
        if (mc.player == null || mc.level == null || servidor == null || mc.isPaused()) {
            ultimoFrameNanos = 0;
            return;
        }
        if (posicionado && servidor != servidorBenchmark) {
            abortar("se abrió otro mundo");
            return;
        }
        if (!posicionado) {
            if (!esMundoBenchmark(servidor)) {
                abortar("el mundo abierto no es el de benchmark");
                return;
            }
            servidorBenchmark = servidor;
            // Primer frame dentro del mundo: primer escalón, primer punto.
            aplicador.accept(calibrador.escalonActual());
            mover(servidor, 0);
            posicionado = true;
        }

        long ahora = System.nanoTime();
        if (ultimoFrameNanos == 0) {
            ultimoFrameNanos = ahora;
            return;
        }
        double frameMs = (ahora - ultimoFrameNanos) / 1e6;
        ultimoFrameNanos = ahora;
        if (frameMs > FRAME_MAXIMO_MS) {
            return;
        }

        switch (calibrador.registrarFrame(frameMs, muestrasSinPendientes >= MUESTRAS_LISTO)) {
            case NADA -> avanzarVuelo(servidor, ahora);
            case CAMBIAR_PUNTO -> mover(servidor, calibrador.puntoActual());
            case CAMBIAR_ESCALON -> {
                LOG.info("LOD: benchmark, escalón {} -> {}", calibrador.indiceEscalonActual(),
                        calibrador.escalonActual());
                aplicador.accept(calibrador.escalonActual());
                mover(servidor, 0);
            }
            case TERMINADO -> terminar(mc);
        }
    }

    /** Durante la medición del vuelo, el jugador avanza en línea recta (un paso cada 250 ms). */
    private void avanzarVuelo(MinecraftServer servidor, long ahora) {
        PuntosBenchmark.Punto punto = PuntosBenchmark.PUNTOS.get(calibrador.puntoActual());
        if (punto.tipo() != PuntosBenchmark.Tipo.VUELO || !calibrador.midiendo()
                || ahora - ultimoPasoVueloNanos < PASO_VUELO_NANOS) {
            return;
        }
        ultimoPasoVueloNanos = ahora;
        double[] xz = PuntosBenchmark.posicionVuelo(punto, calibrador.msMedidos() / 1000);
        servidor.execute(() -> {
            if (servidor.getPlayerList().getPlayers().isEmpty()) {
                return;
            }
            ServerPlayer jugador = servidor.getPlayerList().getPlayers().get(0);
            jugador.teleportTo(servidor.overworld(), xz[0], punto.yReferencia(), xz[1], punto.yaw(), punto.pitch());
        });
    }

    /** Teletransporta al jugador al punto, en el hilo del servidor integrado. */
    private void mover(MinecraftServer servidor, int indice) {
        muestrasSinPendientes = 0; // lo que estaba listo era del punto anterior
        PuntosBenchmark.Punto punto = PuntosBenchmark.PUNTOS.get(indice);
        servidor.execute(() -> {
            if (servidor.getPlayerList().getPlayers().isEmpty()) {
                return;
            }
            ServerPlayer jugador = servidor.getPlayerList().getPlayers().get(0);
            ServerLevel nivel = servidor.overworld();
            nivel.setDayTime(6000); // mediodía: misma luz en cada corrida
            nivel.getChunk(punto.x() >> 4, punto.z() >> 4); // genera/carga para leer el heightmap
            double y = switch (punto.tipo()) {
                case SUPERFICIE -> nivel.getHeight(Heightmap.Types.MOTION_BLOCKING, punto.x(), punto.z()) + 2;
                case ALTURA -> nivel.getHeight(Heightmap.Types.MOTION_BLOCKING, punto.x(), punto.z())
                        + punto.yReferencia();
                case CUEVA -> buscarAireEnCueva(nivel, punto);
                case VUELO -> punto.yReferencia();
            };
            jugador.teleportTo(nivel, punto.x() + 0.5, y, punto.z() + 0.5, punto.yaw(), punto.pitch());
            LOG.info("LOD: benchmark en '{}' ({}, {}, {})", punto.nombre(), punto.x(), (int) y, punto.z());
        });
    }

    /**
     * Primer bloque de aire con aire encima, bajo la superficie, empezando
     * en la altura de referencia y alejándose hacia arriba y abajo.
     */
    private static double buscarAireEnCueva(ServerLevel nivel, PuntosBenchmark.Punto punto) {
        int superficie = nivel.getHeight(Heightmap.Types.MOTION_BLOCKING, punto.x(), punto.z());
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int d = 0; d <= 48; d++) {
            for (int signo : new int[]{-1, 1}) {
                int y = punto.yReferencia() + d * signo;
                if (y <= nivel.getMinBuildHeight() || y >= superficie - 4) {
                    continue;
                }
                pos.set(punto.x(), y, punto.z());
                if (nivel.getBlockState(pos).isAir() && nivel.getBlockState(pos.above()).isAir()) {
                    return y;
                }
            }
        }
        LOG.warn("LOD: no se encontró aire para la cueva en '{}'; se usa la altura de referencia", punto.nombre());
        return punto.yReferencia();
    }

    private void terminar(Minecraft mc) {
        CalibradorBenchmark.Resultado r = calibrador.resultado();
        for (CalibradorBenchmark.Medicion m : r.mediciones()) {
            LOG.info("LOD: escalón {}: {} ms promedio (umbral {} ms) -> {}", m.escalon(),
                    String.format(Locale.ROOT, "%.2f", m.msPromedio()),
                    String.format(Locale.ROOT, "%.2f", calibrador.umbralMs()), m.paso() ? "pasa" : "no pasa");
        }
        RecomendacionesBenchmark.Recomendaciones recomendaciones = soloMedir ? null
                : RecomendacionesBenchmark.de(r, cuentan(), calibrador.escalonActual().frameTimeObjetivoMs(),
                calibrador.escalones().size() - 1);
        Path informe = escribirInforme(mc, r, recomendaciones);
        if (!soloMedir) {
            LOG.info("LOD: calibración terminada: {}{}", r.elegido(), r.enPiso() ? " (en el piso)" : "");
            guardar(r.elegido(), recomendaciones);
        }
        cancelar();
        if (soloMedir) {
            aplicador.accept(ConfigLod.calidadCliente());
        }

        mc.level.disconnect();
        mc.disconnect(new GenericMessageScreen(Component.translatable("menu.savingLevel")));
        mc.setScreen(new TitleScreen());
        Component detalle = informe == null ? Component.translatable("minecraftlodmod.benchmark.sinInforme")
                : Component.translatable("minecraftlodmod.benchmark.informe", informe.getFileName().toString());
        SystemToast.addOrUpdate(mc.getToasts(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                Component.translatable(soloMedir ? "minecraftlodmod.benchmark.terminado"
                        : "minecraftlodmod.calibracion.terminada"),
                soloMedir ? detalle : Component.translatable(r.enPiso() ? "minecraftlodmod.calibracion.piso"
                        : "minecraftlodmod.calibracion.resultado", r.elegido().radioLodChunks()));
    }

    /** El informe en {@code .minecraft/minecraftlodmod/benchmark/}; null si no se pudo escribir. */
    private static boolean[] cuentan() {
        CalibradorBenchmark.Ventana[] v = ventanas();
        boolean[] cuentan = new boolean[v.length];
        for (int i = 0; i < cuentan.length; i++) {
            cuentan[i] = v[i].cuentaParaCalibrar();
        }
        return cuentan;
    }

    private Path escribirInforme(Minecraft mc, CalibradorBenchmark.Resultado r,
                                 RecomendacionesBenchmark.Recomendaciones recomendaciones) {
        List<PuntosBenchmark.Punto> puntos = PuntosBenchmark.PUNTOS;
        String texto = InformeBenchmark.armar(titulo, sistema(mc), MonitorRendimiento.resumenConfig(),
                puntos.stream().map(PuntosBenchmark.Punto::nombre).toList(), cuentan(), r, calibrador.escalones(),
                calibrador.umbralMs(), calibrador.umbralTironMs(), soloMedir);
        if (recomendaciones != null) {
            texto += recomendaciones.motivos().isEmpty() ? "Sin cambios extra (escalado y oclusión en costados quedan como estaban).\n"
                    : "Además se prendió: " + String.join("; ", recomendaciones.motivos()) + ".\n";
        }
        try {
            Path carpeta = FMLPaths.GAMEDIR.get().resolve("minecraftlodmod").resolve("benchmark");
            Files.createDirectories(carpeta);
            Path archivo = carpeta.resolve("informe-"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".txt");
            Files.writeString(archivo, texto, StandardCharsets.UTF_8);
            LOG.info("LOD: informe del benchmark en {}", archivo.toAbsolutePath());
            return archivo;
        } catch (IOException | RuntimeException e) {
            LOG.warn("LOD: no se pudo escribir el informe del benchmark; va al log:\n{}", texto, e);
            return null;
        }
    }

    /** Hardware y software, para comparar informes de máquinas distintas. */
    private static Map<String, String> sistema(Minecraft mc) {
        Map<String, String> s = new LinkedHashMap<>();
        s.put("Fecha", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        s.put("Mod", MonitorRendimiento.version());
        s.put("CPU", GlUtil.getCpuInfo() + " (" + Runtime.getRuntime().availableProcessors() + " hilos)");
        s.put("GPU", GlUtil.getVendor() + " " + GlUtil.getRenderer());
        s.put("OpenGL", GlUtil.getOpenGLVersion());
        var so = ManagementFactory.getOperatingSystemMXBean();
        long ramTotal = so instanceof com.sun.management.OperatingSystemMXBean x ? x.getTotalMemorySize() >> 20 : -1;
        s.put("RAM", (ramTotal < 0 ? "?" : ramTotal + " MB") + ", heap de Java " + (Runtime.getRuntime().maxMemory() >> 20)
                + " MB");
        s.put("Sistema", System.getProperty("os.name") + " " + System.getProperty("os.version") + ", Java "
                + System.getProperty("java.version"));
        s.put("Ventana", mc.getWindow().getWidth() + "×" + mc.getWindow().getHeight()
                + ", tope de FPS " + mc.options.framerateLimit().get() + ", vsync " + mc.options.enableVsync().get());
        return s;
    }

    private static void guardar(ParametrosCalidad p, RecomendacionesBenchmark.Recomendaciones extra) {
        ConfigLod.Cliente c = ConfigLod.CLIENTE;
        c.seleccion.set(ParametrosCalidad.Seleccion.PERSONALIZADO);
        c.radioLodChunks.set(p.radioLodChunks());
        c.umbralPx.set(p.umbralPx());
        c.hilosGeneracion.set(p.hilosGeneracion());
        c.cacheRamMb.set(p.cacheRamMb());
        c.colapsoDesdeNivel.set(p.colapsoDesdeNivel());
        c.fpsObjetivo.set(p.fpsObjetivo());
        // Solo se prende lo que conviene a esta máquina; lo que el jugador ya eligió no se apaga.
        if (extra != null && extra.escalado() && c.escalado.get() == com.example.minecraftlodmod.config.ModoEscalado.APAGADO) {
            c.escalado.set(com.example.minecraftlodmod.config.ModoEscalado.FSR1);
        }
        if (extra != null && extra.oclusionCostados()) {
            c.oclusionCostados.set(true);
        }
        ConfigLod.SPEC_CLIENTE.save();
    }
}
