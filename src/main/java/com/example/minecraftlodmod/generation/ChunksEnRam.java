package com.example.minecraftlodmod.generation;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chunks en RAM para que la carga sea más rápida (solo singleplayer, como la
 * pregeneración).
 *
 * <p>Vanilla lee los chunks del disco en otro hilo pero los deserializa en el
 * hilo del servidor, de a uno, recién cuando entran a la distancia de vista;
 * y los suelta apenas salen. Al caminar por zonas ya exploradas, ese es el
 * cuello de botella que se ve como chunks que tardan en aparecer.
 *
 * <ul>
 *   <li><b>Colchón:</b> los chunks a {@code margen} chunks más allá de la vista
 *   se mantienen cargados (ticket de nivel 33: chunk completo, sin ticks ni
 *   envío al cliente). Se piden de a poco, primero lo que el jugador mira, y
 *   no con el servidor atrasado. Lo que entra a la vista ya está listo.</li>
 *   <li><b>Retención:</b> lo que sale del colchón no se suelta enseguida: se
 *   libera cuando se pasa del presupuesto de RAM, empezando por lo que hace
 *   más tiempo que no está cerca (LRU). Volver por el mismo camino es inmediato.</li>
 * </ul>
 * Cargar un chunk que no existe lo genera (como el anillo real).
 */
public final class ChunksEnRam {

    static final TicketType<ChunkPos> TICKET = TicketType.create("minecraftlodmod_ram",
            Comparator.comparingLong(ChunkPos::toLong));

    static final int PERIODO_TICKS = 10;
    /** Chunks nuevos pedidos por pasada (la deserialización va al hilo del servidor). */
    static final int NUEVOS_POR_PASADA = 32;
    static final long MSPT_MAXIMO_NANOS = 40_000_000L;
    /**
     * Memoria por chunk cargado, para pasar el presupuesto en MB a cantidad.
     * Medido con un histograma del heap (ver CHANGELOG 0.26.16).
     */
    static final int KB_POR_CHUNK = 96;

    /** Chunks con ticket, en orden de último uso (el primero es el más viejo). */
    private final LinkedHashMap<Long, Boolean> retenidos = new LinkedHashMap<>(256, 0.75f, true);
    private ServerLevel nivel;
    private int tick;

    void tick(MinecraftServer servidor, int margen, int presupuestoMb) {
        if (++tick % PERIODO_TICKS != 0) {
            return;
        }
        ServerPlayer jugador = servidor.getPlayerList().getPlayers().isEmpty() ? null
                : servidor.getPlayerList().getPlayers().get(0);
        if (margen <= 0 || jugador == null) {
            soltarTodo();
            return;
        }
        ServerLevel nivelJugador = jugador.serverLevel();
        if (nivelJugador != nivel) {
            soltarTodo();
            nivel = nivelJugador;
        }
        int vista = servidor.getPlayerList().getViewDistance();
        int jx = jugador.chunkPosition().x, jz = jugador.chunkPosition().z;
        int alcance = vista + margen;
        List<Long> faltan = new ArrayList<>();
        int enColchon = 0;
        for (int dx = -alcance; dx <= alcance; dx++) {
            for (int dz = -alcance; dz <= alcance; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) <= vista) {
                    continue;
                }
                enColchon++;
                long clave = ChunkPos.asLong(jx + dx, jz + dz);
                if (retenidos.get(clave) == null) { // get() lo marca como recién usado
                    faltan.add(clave);
                }
            }
        }
        if (!faltan.isEmpty() && servidor.getAverageTickTimeNanos() <= MSPT_MAXIMO_NANOS) {
            var mirada = jugador.getLookAngle();
            double px = jugador.getX(), pz = jugador.getZ();
            faltan.sort(Comparator.comparingDouble(c -> PrioridadVista.costo(
                    ChunkPos.getX(c) * 16 + 8 - px, ChunkPos.getZ(c) * 16 + 8 - pz, mirada.x, mirada.z)));
            for (int i = 0; i < Math.min(NUEVOS_POR_PASADA, faltan.size()); i++) {
                ChunkPos pos = new ChunkPos(faltan.get(i));
                nivel.getChunkSource().addRegionTicket(TICKET, pos, 0, pos);
                retenidos.put(faltan.get(i), Boolean.TRUE);
            }
        }
        int maximo = Math.max(enColchon, maximoChunks(presupuestoMb));
        for (Iterator<Map.Entry<Long, Boolean>> it = retenidos.entrySet().iterator();
             retenidos.size() > maximo && it.hasNext(); ) {
            ChunkPos pos = new ChunkPos(it.next().getKey());
            nivel.getChunkSource().removeRegionTicket(TICKET, pos, 0, pos);
            it.remove();
        }
    }

    static int maximoChunks(int presupuestoMb) {
        return (int) Math.min(Integer.MAX_VALUE, (long) presupuestoMb * 1024 / KB_POR_CHUNK);
    }

    void soltarTodo() {
        if (nivel != null) {
            for (long clave : retenidos.keySet()) {
                ChunkPos pos = new ChunkPos(clave);
                nivel.getChunkSource().removeRegionTicket(TICKET, pos, 0, pos);
            }
        }
        retenidos.clear();
    }

    /** Para el HUD/log: chunks retenidos ahora. */
    int cantidad() {
        return retenidos.size();
    }
}
