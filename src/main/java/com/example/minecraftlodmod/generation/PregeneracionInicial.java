package com.example.minecraftlodmod.generation;

import com.example.minecraftlodmod.config.ConfigLod;
import com.example.minecraftlodmod.storage.RegionFileStore;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Pregeneración alrededor del jugador al entrar a un mundo y cuando un jugador se
 * va de un servidor: chunks
 * vanilla desde donde está (o quedó) el jugador hacia afuera, salteando lo ya
 * generado con su LOD, con los tickets y las marcas de {@link PregeneradorChunks}.
 *
 * - **Al entrar** (singleplayer, opción {@code pregeneracionInicial}): mientras
 *   dura la pantalla de "preparando el área de aparición", desde donde quedó el
 *   jugador según level.dat (el spawn en un mundo nuevo). Lo llama
 *   {@code MixinPrepararNiveles} desde {@code MinecraftServer#prepareLevels}.
 * - **Al irse un jugador de un servidor** (dedicado, o un invitado de un mundo
 *   abierto en LAN; opción de servidor {@code pregeneracionSalida}): en segundo
 *   plano durante esos segundos alrededor de donde quedó, de a un jugador por vez
 *   y sin pedir chunks con el tick del servidor lento. Al volver ya tiene terreno
 *   real y LOD alrededor.
 *
 * Al entrar nadie está jugando: se usa toda la CPU, en el hilo del
 * servidor, con {@code esperarTick} = el {@code waitUntilNextTick} de vanilla,
 * que corre las tareas pendientes (tickets, generación, luz) como hace vanilla
 * con el área de aparición y al guardar.
 */
public final class PregeneracionInicial {

    private static final Logger LOG = LogUtils.getLogger();

    /** Chunks esperando extracción por encima de esto: esperar a que la extracción alcance. */
    static final int PENDIENTES_MAXIMOS = 128;
    /** Ticket que no terminó en este tiempo se suelta igual. */
    static final long NANOS_MAXIMOS_POR_CHUNK = 30_000_000_000L;
    /** Radio máximo de la espiral: con el tiempo de la opción no se llega ni cerca. */
    static final int RADIO = 512;

    /** Lo que muestra la pantalla de carga (se lee desde el hilo de render). */
    public record Estado(int chunks, int segundosRestantes) {
    }

    private static volatile Estado estado;

    /** null si no hay una pregeneración bloqueante corriendo. */
    public static Estado estado() {
        return estado;
    }

    private PregeneracionInicial() {
    }

    /** Dimensión y chunk desde donde pregenerar. */
    record Lugar(ServerLevel nivel, ChunkPos chunk) {
        static Lugar de(ServerPlayer jugador) {
            return new Lugar(jugador.serverLevel(), jugador.chunkPosition());
        }
    }

    /** Dónde quedó el jugador según level.dat (singleplayer); el spawn si nunca entró. */
    static Lugar dondeQuedo(MinecraftServer servidor) {
        net.minecraft.nbt.CompoundTag jugador = servidor.getWorldData().getLoadedPlayerTag();
        if (jugador != null) {
            try {
                net.minecraft.nbt.ListTag pos = jugador.getList("Pos", net.minecraft.nbt.Tag.TAG_DOUBLE);
                ServerLevel nivel = servidor.overworld();
                if (jugador.contains("Dimension", net.minecraft.nbt.Tag.TAG_STRING)) {
                    ServerLevel otro = servidor.getLevel(net.minecraft.resources.ResourceKey.create(
                            net.minecraft.core.registries.Registries.DIMENSION,
                            net.minecraft.resources.ResourceLocation.parse(jugador.getString("Dimension"))));
                    nivel = otro != null ? otro : nivel;
                }
                if (pos.size() == 3) {
                    return new Lugar(nivel, new ChunkPos(
                            net.minecraft.util.Mth.floor(pos.getDouble(0)) >> 4,
                            net.minecraft.util.Mth.floor(pos.getDouble(2)) >> 4));
                }
            } catch (RuntimeException e) {
                LOG.warn("LOD: no se pudo leer dónde quedó el jugador; se pregenera alrededor del spawn", e);
            }
        }
        return new Lugar(servidor.overworld(), new ChunkPos(servidor.overworld().getSharedSpawnPos()));
    }

    /** Al entrar a un mundo (singleplayer), desde {@code prepareLevels}. */
    public static void alEntrar(MinecraftServer servidor, Runnable esperarTick) {
        // Las opciones viven en la config del cliente: en un servidor dedicado no existen.
        if (!servidor.isSingleplayer()) {
            return;
        }
        bloqueando(servidor, dondeQuedo(servidor), ConfigLod.CLIENTE.pregeneracionInicial.get(), esperarTick,
                "al entrar");
    }

    static void bloqueando(MinecraftServer servidor, Lugar lugar, int segundos, Runnable esperarTick, String cuando) {
        GeneradorLocal generador = GeneradorLocal.activo();
        if (segundos <= 0 || generador == null || generador.store() == null) {
            return;
        }
        long inicio = System.nanoTime();
        long fin = inicio + segundos * 1_000_000_000L;
        Pasos pasos = new Pasos(generador, lugar, Math.max(8, Runtime.getRuntime().availableProcessors() * 4));
        LOG.info("LOD: pregenerando {} alrededor de {}, {} en {} durante {} s", cuando, lugar.chunk().x * 16,
                lugar.chunk().z * 16, lugar.nivel().dimension().location(), segundos);
        try {
            while (System.nanoTime() < fin && !pasos.terminado()) {
                long ahora = System.nanoTime();
                pasos.paso(ahora);
                // Sin ticks del servidor: los chunks que esperaban luz se reintentan acá.
                generador.reintentarPendientes(servidor);
                estado = new Estado(pasos.listos, (int) Math.max(0, (fin - ahora) / 1_000_000_000L));
                esperarTick.run();
            }
        } finally {
            pasos.soltar();
            estado = null;
        }
        LOG.info("LOD: pregeneración {}: {} chunks en {} s (anillo {})", cuando, pasos.listos,
                (System.nanoTime() - inicio) / 1_000_000_000L, pasos.espiral.anillo());
    }

    // --- Servidor: en segundo plano después de que un jugador se va. ---

    /** Tick del servidor más lento que esto (promedio): no se piden chunks nuevos. */
    static final long MSPT_MAXIMO_NANOS = 40_000_000L;

    private static final ArrayDeque<Lugar> salidas = new ArrayDeque<>();
    private static Pasos enCurso;
    private static long finEnCurso;

    @SubscribeEvent
    public static void alDesconectarse(PlayerEvent.PlayerLoggedOutEvent evento) {
        if (!(evento.getEntity() instanceof ServerPlayer jugador)) {
            return;
        }
        MinecraftServer servidor = jugador.getServer();
        // En singleplayer, el dueño que cierra su mundo no (el servidor se apaga); un invitado de LAN sí.
        if (servidor != null && !servidor.isSingleplayerOwner(jugador.getGameProfile())
                && ConfigLod.SERVIDOR.pregeneracionSalida.get() > 0) {
            Lugar lugar = Lugar.de(jugador);
            salidas.removeIf(l -> l.nivel() == lugar.nivel() && l.chunk().equals(lugar.chunk()));
            salidas.add(lugar);
        }
    }

    @SubscribeEvent
    public static void alTerminarTick(ServerTickEvent.Post evento) {
        MinecraftServer servidor = evento.getServer();
        if (enCurso == null && salidas.isEmpty()) {
            return;
        }
        GeneradorLocal generador = GeneradorLocal.activo();
        if (generador == null || generador.store() == null) {
            return;
        }
        long ahora = System.nanoTime();
        if (enCurso == null) {
            Lugar lugar = salidas.poll();
            int segundos = ConfigLod.SERVIDOR.pregeneracionSalida.get();
            // Con el servidor ocupado, a lo sumo la mitad de los núcleos pidiendo chunks.
            enCurso = new Pasos(generador, lugar, Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
            finEnCurso = ahora + segundos * 1_000_000_000L;
            LOG.info("LOD: pregenerando alrededor de donde se fue un jugador ({}, {} en {}) durante {} s",
                    lugar.chunk().x * 16, lugar.chunk().z * 16, lugar.nivel().dimension().location(), segundos);
        }
        if (ahora >= finEnCurso || enCurso.terminado()) {
            enCurso.soltar();
            LOG.info("LOD: pregeneración al salir un jugador: {} chunks", enCurso.listos);
            enCurso = null;
            return;
        }
        enCurso.paso(ahora, servidor.getAverageTickTimeNanos() <= MSPT_MAXIMO_NANOS);
    }

    @SubscribeEvent
    public static void alDetenerse(ServerStoppedEvent evento) {
        enCurso = null;
        salidas.clear();
    }

    /** Una espiral de pregeneración con sus tickets en vuelo (hilo del servidor). */
    static final class Pasos {
        final GeneradorLocal generador;
        final ServerLevel nivel;
        final ChunkPos centro;
        final EspiralChunks espiral = new EspiralChunks(RADIO);
        final int enVueloMaximo;
        final byte dimension;
        final Map<Long, Long> enVuelo = new HashMap<>();
        boolean agotada;
        int listos;

        Pasos(GeneradorLocal generador, Lugar lugar, int enVueloMaximo) {
            this.generador = generador;
            this.nivel = lugar.nivel();
            this.centro = lugar.chunk();
            this.enVueloMaximo = enVueloMaximo;
            this.dimension = GeneradorLocal.idDimension(nivel.dimension());
        }

        boolean terminado() {
            return agotada && enVuelo.isEmpty();
        }

        void paso(long ahora) {
            paso(ahora, true);
        }

        /** Suelta lo terminado y, si {@code pedir}, pide chunks nuevos hasta el tope en vuelo. */
        void paso(long ahora, boolean pedir) {
            RegionFileStore store = generador.store();
            if (store == null) {
                return;
            }
            Iterator<Map.Entry<Long, Long>> it = enVuelo.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Long, Long> e = it.next();
                int x = ChunkPos.getX(e.getKey()), z = ChunkPos.getZ(e.getKey());
                boolean listo = GeneradorLocal.tieneMarca(store, GeneradorLocal.claveRegion(dimension, x, z), x, z);
                if (listo || ahora - e.getValue() > NANOS_MAXIMOS_POR_CHUNK) {
                    ChunkPos pos = new ChunkPos(x, z);
                    nivel.getChunkSource().removeRegionTicket(PregeneradorChunks.TICKET, pos, 0, pos);
                    it.remove();
                    listos += listo ? 1 : 0;
                }
            }
            while (pedir && !agotada && enVuelo.size() < enVueloMaximo
                    && generador.cantidadPendientes() < PENDIENTES_MAXIMOS) {
                if (!espiral.siguiente()) {
                    agotada = true;
                    break;
                }
                int x = centro.x + espiral.dx(), z = centro.z + espiral.dz();
                if (GeneradorLocal.tieneMarca(store, GeneradorLocal.claveRegion(dimension, x, z), x, z)) {
                    continue;
                }
                ChunkPos pos = new ChunkPos(x, z);
                nivel.getChunkSource().addRegionTicket(PregeneradorChunks.TICKET, pos, 0, pos);
                enVuelo.put(pos.toLong(), ahora);
            }
        }

        void soltar() {
            for (long clave : enVuelo.keySet()) {
                ChunkPos pos = new ChunkPos(clave);
                nivel.getChunkSource().removeRegionTicket(PregeneradorChunks.TICKET, pos, 0, pos);
            }
            enVuelo.clear();
        }
    }
}
