package com.example.minecraftlodmod.generation;

import com.example.minecraftlodmod.storage.RegionFileStore;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Pregenerador integrado (como Chunky): genera chunks vanilla del jugador
 * hacia afuera ({@link EspiralChunks}) para que el LOD se llene más allá
 * de la distancia de render sin tener que recorrer el mundo. Sin esto, el
 * LOD solo existe donde el servidor ya cargó chunks.
 *
 * Cada chunk se pide con un ticket propio de nivel 33 (chunk completo, sin
 * ticks de entidades ni bloques): vanilla lo genera en sus hilos de
 * generación, al cargarse dispara {@code ChunkEvent.Load} y
 * {@link GeneradorLocal} extrae el LOD como con cualquier otro chunk. El
 * ticket se suelta recién cuando el LOD del chunk quedó guardado (si se
 * soltara al cargar, un chunk que esperaba lugar en la cola de extracción
 * se descargaría y se perdería).
 *
 * Orden: la espiral va del centro hacia afuera, pero de una ventana de
 * {@link #VENTANA} candidatos se pide primero lo que el jugador mira
 * ({@link PrioridadVista}), así el LOD crece antes en su campo visual.
 *
 * No compite con el juego: con el tick del servidor por encima de
 * {@link #MSPT_MAXIMO_NANOS} o con la extracción atrasada no pide chunks
 * nuevos. Los chunks generados se guardan en el mundo como los de Chunky:
 * un radio grande ocupa mucho disco y tarda (ver la opción en la config).
 */
public final class PregeneradorChunks {

    private static final Logger LOG = LogUtils.getLogger();

    static final TicketType<ChunkPos> TICKET = TicketType.create("minecraftlodmod_pregeneracion",
            Comparator.comparingLong(ChunkPos::toLong));

    /** Tick del servidor más lento que esto (promedio): no se piden chunks nuevos. */
    static final long MSPT_MAXIMO_NANOS = 40_000_000L;
    /** Chunks esperando extracción por encima de esto: la extracción va atrasada, esperar. */
    static final int PENDIENTES_MAXIMOS = 64;
    /** Candidatos de la espiral revisados por tick (los ya generados se saltean rápido). */
    static final int REVISIONES_POR_TICK = 4096;
    /** Un ticket que no terminó en este tiempo se suelta igual (chunk corrupto, etc.). */
    static final int TICKS_MAXIMOS_POR_CHUNK = 20 * 120;
    /** Si el jugador se aleja esto del centro, la espiral recomienza desde él. */
    static final int RECENTRAR_CHUNKS = 32;
    static final long PERIODO_LOG_NANOS = 30_000_000_000L;
    /** Candidatos de la espiral que se ordenan por prioridad de vista antes de pedirlos. */
    static final int VENTANA = 512;

    private final GeneradorLocal generador;
    private final int enVueloMaximo = Math.max(4, Runtime.getRuntime().availableProcessors() * 2);

    private EspiralChunks espiral;
    private ServerLevel nivel;
    private int centroX, centroZ;
    /** Chunk (ChunkPos.toLong) → tick en que se pidió. */
    private final Map<Long, Integer> enVuelo = new HashMap<>();
    /** Próximos chunks de la espiral que todavía no tienen LOD (x, z empaquetados con ChunkPos.asLong). */
    private final java.util.ArrayList<Long> candidatos = new java.util.ArrayList<>();
    private boolean espiralAgotada;
    private int tick;
    private long generados;
    /** Desde que arrancó la espiral actual: para el estado que muestra el comando. */
    private long generadosTotal;
    private long inicioEspiralNanos;
    private long ultimoLogNanos = System.nanoTime();
    private boolean avisoCompleto;

    PregeneradorChunks(GeneradorLocal generador) {
        this.generador = generador;
    }

    /**
     * @param activo pregeneración prendida en la config
     * @param radio  radio en chunks alrededor del jugador
     */
    void tick(MinecraftServer servidor, boolean activo, int radio) {
        tick++;
        RegionFileStore store = generador.store();
        liberarTerminados(store);
        ServerPlayer jugador = servidor.getPlayerList().getPlayers().isEmpty() ? null
                : servidor.getPlayerList().getPlayers().get(0);
        if (!activo || store == null || jugador == null) {
            if (!activo) {
                soltarTodo();
                espiral = null;
            }
            return;
        }
        ServerLevel nivelJugador = jugador.serverLevel();
        int jugadorX = jugador.chunkPosition().x, jugadorZ = jugador.chunkPosition().z;
        if (espiral == null || nivelJugador != nivel || espiral.radio() != radio
                || Math.max(Math.abs(jugadorX - centroX), Math.abs(jugadorZ - centroZ)) > RECENTRAR_CHUNKS) {
            if (nivelJugador != nivel) {
                soltarTodo();
            }
            nivel = nivelJugador;
            centroX = jugadorX;
            centroZ = jugadorZ;
            espiral = new EspiralChunks(radio);
            candidatos.clear();
            espiralAgotada = false;
            avisoCompleto = false;
            generadosTotal = 0;
            inicioEspiralNanos = System.nanoTime();
        }
        registrarAvance();
        if (servidor.getAverageTickTimeNanos() > MSPT_MAXIMO_NANOS
                || generador.cantidadPendientes() > PENDIENTES_MAXIMOS) {
            return;
        }
        byte dimension = GeneradorLocal.idDimension(nivel.dimension());
        // Llenar la ventana con los próximos de la espiral que todavía no tienen LOD.
        int revisados = 0;
        while (candidatos.size() < VENTANA && revisados < REVISIONES_POR_TICK && !espiralAgotada) {
            if (!espiral.siguiente()) {
                espiralAgotada = true;
                break;
            }
            revisados++;
            int x = centroX + espiral.dx(), z = centroZ + espiral.dz();
            if (!GeneradorLocal.tieneMarca(store, GeneradorLocal.claveRegion(dimension, x, z), x, z)) {
                candidatos.add(ChunkPos.asLong(x, z));
            }
        }
        if (candidatos.isEmpty()) {
            if (espiralAgotada && enVuelo.isEmpty() && !avisoCompleto) {
                avisoCompleto = true;
                LOG.info("LOD: pregeneración completa ({} chunks de radio alrededor de {}, {})",
                        radio, centroX * 16, centroZ * 16);
            }
            return;
        }
        // Primero lo que el jugador mira.
        var mirada = jugador.getLookAngle();
        double jx = jugador.getX(), jz = jugador.getZ();
        candidatos.sort(java.util.Comparator.comparingDouble(c -> PrioridadVista.costo(
                ChunkPos.getX(c) * 16 + 8 - jx, ChunkPos.getZ(c) * 16 + 8 - jz, mirada.x, mirada.z)));
        Iterator<Long> it = candidatos.iterator();
        while (enVuelo.size() < enVueloMaximo && it.hasNext()) {
            long clave = it.next();
            it.remove();
            int x = ChunkPos.getX(clave), z = ChunkPos.getZ(clave);
            if (enVuelo.containsKey(clave)
                    || GeneradorLocal.tieneMarca(store, GeneradorLocal.claveRegion(dimension, x, z), x, z)
                    || nivel.getChunkSource().getChunkNow(x, z) != null) {
                continue; // ya tiene LOD, ya se pidió, o ya está cargado (lo extrae la carga normal)
            }
            ChunkPos pos = new ChunkPos(x, z);
            nivel.getChunkSource().addRegionTicket(TICKET, pos, 0, pos);
            enVuelo.put(clave, tick);
        }
    }

    private void liberarTerminados(RegionFileStore store) {
        if (enVuelo.isEmpty() || nivel == null) {
            return;
        }
        byte dimension = GeneradorLocal.idDimension(nivel.dimension());
        Iterator<Map.Entry<Long, Integer>> it = enVuelo.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Integer> e = it.next();
            int x = ChunkPos.getX(e.getKey()), z = ChunkPos.getZ(e.getKey());
            boolean listo = store != null
                    && GeneradorLocal.tieneMarca(store, GeneradorLocal.claveRegion(dimension, x, z), x, z);
            if (listo || tick - e.getValue() > TICKS_MAXIMOS_POR_CHUNK) {
                ChunkPos pos = new ChunkPos(x, z);
                nivel.getChunkSource().removeRegionTicket(TICKET, pos, 0, pos);
                it.remove();
                if (listo) {
                    generados++;
                    generadosTotal++;
                }
            }
        }
    }

    /** Lo que muestra {@code /lod pregenerar}. */
    public record Estado(boolean enMarcha, int anillo, int radio, long generados, double porSegundo, int enCurso,
                         boolean completo) {
    }

    public Estado estado() {
        if (espiral == null) {
            return new Estado(false, 0, 0, 0, 0, 0, false);
        }
        double segundos = Math.max(1e-3, (System.nanoTime() - inicioEspiralNanos) / 1e9);
        return new Estado(true, espiral.anillo(), espiral.radio(), generadosTotal, generadosTotal / segundos,
                enVuelo.size(), avisoCompleto);
    }

    /** Suelta todos los tickets (pregeneración apagada, cambio de dimensión, servidor cerrando). */
    void soltarTodo() {
        if (nivel != null) {
            for (long clave : enVuelo.keySet()) {
                ChunkPos pos = new ChunkPos(clave);
                nivel.getChunkSource().removeRegionTicket(TICKET, pos, 0, pos);
            }
        }
        enVuelo.clear();
    }

    /** Servidor detenido: todo el estado apunta a niveles que ya no existen. */
    void reiniciar() {
        enVuelo.clear();
        espiral = null;
        nivel = null;
        generados = 0;
    }

    private void registrarAvance() {
        long ahora = System.nanoTime();
        if (ahora - ultimoLogNanos < PERIODO_LOG_NANOS || espiral == null || avisoCompleto) {
            return;
        }
        double segundos = (ahora - ultimoLogNanos) / 1e9;
        LOG.info("LOD pregeneración: anillo {} de {} ({} chunks nuevos, {} /s, {} en curso)",
                espiral.anillo(), espiral.radio(), generados, String.format("%.1f", generados / segundos),
                enVuelo.size());
        generados = 0;
        ultimoLogNanos = ahora;
    }
}
