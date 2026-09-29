package com.example.minecraftlodmod.benchmark;

import com.example.minecraftlodmod.config.ConfigLod;
import com.example.minecraftlodmod.config.ParametrosCalidad;
import com.example.minecraftlodmod.config.QualityPreset;
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
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

import java.util.function.Consumer;

/**
 * Calibración por benchmark, lado cliente (sección 9). Solo cliente.
 *
 * Flujo del botón "Calibrar" de la pantalla de config:
 *  1. El jugador eligió un preset como punto de partida.
 *  2. Se abre el mundo de benchmark ({@link PuntosBenchmark}), creándolo
 *     la primera vez: seed fija, espectador, pacífico, sin ciclo de día ni
 *     clima ni mobs — la escena tiene que ser la misma en cada corrida.
 *  3. {@link CalibradorBenchmark} recorre escalones × puntos; en cada frame
 *     se le pasa el frame time real (entre dos {@link RenderFrameEvent.Post}).
 *  4. El resultado se guarda como config PERSONALIZADO.
 *  5. Se cierra el mundo y se vuelve al menú principal.
 *
 * Mover al jugador se hace directo sobre el servidor integrado (siempre es
 * singleplayer), no con comandos: no depende de que el mundo tenga trucos.
 *
 * Pendiente con render/: {@link #asignarAplicador} — hasta que exista el
 * render de LOD, cambiar de escalón no cambia lo que se dibuja y la
 * calibración mide solo el costo vanilla de cada escena.
 */
public final class SesionCalibracion {

    private static final Logger LOG = LogUtils.getLogger();

    /** Espera en cada punto antes de medir: carga de chunks y generación de LOD. */
    public static final double CALENTAMIENTO_MS = 10_000;
    public static final double MEDICION_MS = 5_000;
    /** Frames más largos que esto (pausas, alt-tab, guardado) no se cuentan. */
    private static final double FRAME_MAXIMO_MS = 2_000;

    private static volatile Consumer<ParametrosCalidad> aplicador = p -> { };

    /** Sesión en curso, o null. Solo hilo de render. */
    private static SesionCalibracion activa;

    private final CalibradorBenchmark calibrador;
    private long ultimoFrameNanos;
    private boolean posicionado;

    private SesionCalibracion(QualityPreset inicial) {
        this.calibrador = new CalibradorBenchmark(EscalonesCalibracion.desde(inicial),
                PuntosBenchmark.PUNTOS.size(), CALENTAMIENTO_MS, MEDICION_MS);
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

    /** Solo desde el menú principal (sin mundo abierto). */
    public static void iniciar(QualityPreset inicial) {
        Minecraft mc = Minecraft.getInstance();
        if (activa != null || mc.level != null) {
            return;
        }
        activa = new SesionCalibracion(inicial);
        NeoForge.EVENT_BUS.register(activa);
        LOG.info("LOD: calibración desde {} ({} escalones posibles)", inicial,
                EscalonesCalibracion.desde(inicial).escalones().size());

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
            activa = null;
        }
    }

    @SubscribeEvent
    public void alTerminarFrame(RenderFrameEvent.Post evento) {
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer servidor = mc.getSingleplayerServer();
        if (mc.player == null || mc.level == null || servidor == null || mc.isPaused()) {
            ultimoFrameNanos = 0;
            return;
        }
        if (!posicionado) {
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

        switch (calibrador.registrarFrame(frameMs)) {
            case NADA -> { }
            case CAMBIAR_PUNTO -> mover(servidor, calibrador.puntoActual());
            case CAMBIAR_ESCALON -> {
                LOG.info("LOD: calibración, escalón {} -> {}", calibrador.indiceEscalonActual(),
                        calibrador.escalonActual());
                aplicador.accept(calibrador.escalonActual());
                mover(servidor, 0);
            }
            case TERMINADO -> terminar(mc);
        }
    }

    /** Teletransporta al jugador al punto, en el hilo del servidor integrado. */
    private static void mover(MinecraftServer servidor, int indice) {
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
                case CUEVA -> buscarAireEnCueva(nivel, punto);
            };
            jugador.teleportTo(nivel, punto.x() + 0.5, y, punto.z() + 0.5, punto.yaw(), punto.pitch());
            LOG.info("LOD: calibración en '{}' ({}, {}, {})", punto.nombre(), punto.x(), (int) y, punto.z());
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
                    String.format("%.2f", m.msPromedio()), String.format("%.2f", calibrador.umbralMs()),
                    m.paso() ? "pasa" : "no pasa");
        }
        LOG.info("LOD: calibración terminada: {}{}", r.elegido(), r.enPiso() ? " (en el piso)" : "");
        guardar(r.elegido());
        cancelar();

        mc.level.disconnect();
        mc.disconnect(new GenericMessageScreen(Component.translatable("menu.savingLevel")));
        mc.setScreen(new TitleScreen());
        SystemToast.addOrUpdate(mc.getToasts(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                Component.translatable("minecraftlodmod.calibracion.terminada"),
                Component.translatable(r.enPiso() ? "minecraftlodmod.calibracion.piso"
                        : "minecraftlodmod.calibracion.resultado", r.elegido().radioLodChunks()));
    }

    private static void guardar(ParametrosCalidad p) {
        ConfigLod.Cliente c = ConfigLod.CLIENTE;
        c.seleccion.set(ParametrosCalidad.Seleccion.PERSONALIZADO);
        c.radioLodChunks.set(p.radioLodChunks());
        c.umbralPx.set(p.umbralPx());
        c.hilosGeneracion.set(p.hilosGeneracion());
        c.cacheRamMb.set(p.cacheRamMb());
        c.colapsoDesdeNivel.set(p.colapsoDesdeNivel());
        c.fpsObjetivo.set(p.fpsObjetivo());
        ConfigLod.SPEC_CLIENTE.save();
    }
}
