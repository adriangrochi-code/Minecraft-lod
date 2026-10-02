package com.example.minecraftlodmod.generation;

import com.example.minecraftlodmod.config.ConfigLod;
import com.example.minecraftlodmod.storage.RegionFileStore;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Pregeneración al crear un mundo: mientras dura la pantalla de "preparando el
 * área de aparición", y solo la primera vez que se abre (todavía no entró
 * ningún jugador), se siguen generando chunks vanilla del spawn hacia afuera
 * durante {@code pregeneracionInicial} segundos. Nadie está jugando todavía:
 * se usa toda la CPU sin frenar por el tick del servidor, y al entrar ya hay
 * terreno real (y su LOD) alrededor en vez de empezar con el mundo vacío.
 *
 * Lo llama {@code MixinPrepararNiveles} desde {@code MinecraftServer#prepareLevels},
 * en el hilo del servidor; {@code esperarTick} es el {@code waitUntilNextTick}
 * de vanilla, que corre las tareas pendientes (tickets, generación, luz) como
 * con el área de aparición. Mismo mecanismo de tickets y marcas de LOD que
 * {@link PregeneradorChunks}.
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

    /** null si no está corriendo. */
    public static Estado estado() {
        return estado;
    }

    private PregeneracionInicial() {
    }

    /** ¿Mundo recién creado? En singleplayer, level.dat todavía no tiene los datos del jugador. */
    static boolean mundoNuevo(MinecraftServer servidor) {
        return servidor.getWorldData().getLoadedPlayerTag() == null;
    }

    public static void correr(MinecraftServer servidor, Runnable esperarTick) {
        // La opción vive en la config del cliente: en un servidor dedicado no existe.
        if (!servidor.isSingleplayer() || !mundoNuevo(servidor)) {
            return;
        }
        GeneradorLocal generador = GeneradorLocal.activo();
        int segundos = ConfigLod.CLIENTE.pregeneracionInicial.get();
        if (segundos <= 0 || generador == null || generador.store() == null) {
            return;
        }
        ServerLevel nivel = servidor.overworld();
        ChunkPos centro = new ChunkPos(nivel.getSharedSpawnPos());
        int enVueloMaximo = Math.max(8, Runtime.getRuntime().availableProcessors() * 4);
        long inicio = System.nanoTime();
        long fin = inicio + segundos * 1_000_000_000L;
        EspiralChunks espiral = new EspiralChunks(RADIO);
        Map<Long, Long> enVuelo = new HashMap<>();
        byte dimension = GeneradorLocal.idDimension(nivel.dimension());
        RegionFileStore store = generador.store();
        int listos = 0;
        boolean agotada = false;
        LOG.info("LOD: mundo nuevo, pregenerando alrededor del spawn durante {} s", segundos);
        try {
            while (System.nanoTime() < fin && !(agotada && enVuelo.isEmpty())) {
                long ahora = System.nanoTime();
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
                while (!agotada && enVuelo.size() < enVueloMaximo
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
                // Sin ticks del servidor todavía: los chunks que esperaban luz se reintentan acá.
                generador.reintentarPendientes(servidor);
                estado = new Estado(listos, (int) Math.max(0, (fin - ahora) / 1_000_000_000L));
                esperarTick.run();
            }
        } finally {
            for (long clave : enVuelo.keySet()) {
                ChunkPos pos = new ChunkPos(clave);
                nivel.getChunkSource().removeRegionTicket(PregeneradorChunks.TICKET, pos, 0, pos);
            }
            estado = null;
        }
        LOG.info("LOD: pregeneración inicial: {} chunks en {} s (anillo {})", listos,
                (System.nanoTime() - inicio) / 1_000_000_000L, espiral.anillo());
    }
}
