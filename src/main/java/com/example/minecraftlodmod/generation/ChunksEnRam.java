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
 *
 * <p>Presupuesto: la misma RAM que el jugador le da al LOD ("RAM para LOD",
 * {@code cacheRamMb} del preset o personalizado), con tope en una cuarta
 * parte del heap de Java. La mitad va al colchón (el más ancho que entre, de
 * {@link #MARGEN_MIN} a {@link #MARGEN_MAX} chunks) y el resto a la retención:
 * más RAM para el LOD = más chunks listos alrededor.
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
     * Medido: colchón de 8 chunks con vista 12 (~1056 chunks) = ~100 MB de heap.
     */
    static final int KB_POR_CHUNK = 96;
    static final int MARGEN_MIN = 2, MARGEN_MAX = 32;

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();
    private int ultimoMargen = -1, ultimoPresupuesto = -1;

    /** RAM para chunks (MB): la del LOD, sin pasar de un cuarto del heap. */
    static int presupuestoMb(int ramLodMb, long heapMaximoBytes) {
        return (int) Math.max(0, Math.min(ramLodMb, heapMaximoBytes / 4 / (1024 * 1024)));
    }

    /** Chunks del colchón: anillo de ancho {@code margen} alrededor de la vista. */
    static long chunksColchon(int vista, int margen) {
        long afuera = 2L * (vista + margen) + 1, adentro = 2L * vista + 1;
        return afuera * afuera - adentro * adentro;
    }

    /** El colchón más ancho que entra en la mitad del presupuesto (al menos {@link #MARGEN_MIN}). */
    static int margenPara(int vista, int presupuestoMb) {
        long maximo = maximoChunks(presupuestoMb) / 2;
        int margen = MARGEN_MIN;
        while (margen < MARGEN_MAX && chunksColchon(vista, margen + 1) <= maximo) {
            margen++;
        }
        return margen;
    }

    /** Chunks con ticket, en orden de último uso (el primero es el más viejo). */
    private final LinkedHashMap<Long, Boolean> retenidos = new LinkedHashMap<>(256, 0.75f, true);
    private ServerLevel nivel;
    private int tick;

    void tick(MinecraftServer servidor, boolean activo, int ramLodMb) {
        if (++tick % PERIODO_TICKS != 0) {
            return;
        }
        int presupuestoMb = activo ? presupuestoMb(ramLodMb, Runtime.getRuntime().maxMemory()) : 0;
        int margen = presupuestoMb > 0 ? margenPara(servidor.getPlayerList().getViewDistance(), presupuestoMb) : 0;
        if (margen != ultimoMargen || presupuestoMb != ultimoPresupuesto) {
            ultimoMargen = margen;
            ultimoPresupuesto = presupuestoMb;
            if (presupuestoMb > 0) {
                LOG.info("LOD: chunks en RAM con {} MB (RAM para LOD): colchón de {} chunks, hasta {} chunks retenidos",
                        presupuestoMb, margen, maximoChunks(presupuestoMb));
            }
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
        if (!faltan.isEmpty() && servidor.getAverageTickTimeNanos() <= MSPT_MAXIMO_NANOS
                && vistaCompleta(servidor, jugador)) {
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

    /**
     * Chunks dentro de la distancia de vista del jugador que todavía no están
     * cargados. Mientras falte alguno, ni el colchón ni el anillo real piden
     * chunks nuevos: en terreno sin explorar competían por los mismos núcleos
     * (y con la misma prioridad de ticket) con lo que el jugador tiene delante.
     * Medido (vista 12, terreno nuevo): sin esto la vista quedaba trabada con
     * 607 de 625 chunks faltantes más de 80 s; con esto se completa en 37-39 s.
     */
    static int faltanEnVista(ServerLevel nivel, int jx, int jz, int vista) {
        int faltan = 0;
        for (int dx = -vista; dx <= vista; dx++) {
            for (int dz = -vista; dz <= vista; dz++) {
                if (nivel.getChunkSource().getChunkNow(jx + dx, jz + dz) == null) {
                    faltan++;
                }
            }
        }
        return faltan;
    }

    private static long tickVista = Long.MIN_VALUE;
    private static boolean vistaCompleta;

    /** {@link #faltanEnVista} == 0, calculado una vez por tick (lo consultan el colchón y el anillo real). */
    static boolean vistaCompleta(MinecraftServer servidor, ServerPlayer jugador) {
        long ahora = servidor.getTickCount();
        if (ahora != tickVista) {
            tickVista = ahora;
            int faltan = faltanEnVista(jugador.serverLevel(), jugador.chunkPosition().x, jugador.chunkPosition().z,
                    servidor.getPlayerList().getViewDistance());
            vistaCompleta = faltan == 0;
        }
        return vistaCompleta;
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

    /** Servidor detenido: el nivel ya no existe (sus tickets se fueron con él); empezar de cero. */
    void reiniciar() {
        retenidos.clear();
        nivel = null;
        ultimoMargen = -1;
        ultimoPresupuesto = -1;
        tickVista = Long.MIN_VALUE;
    }

    /** Para el HUD/log: chunks retenidos ahora. */
    int cantidad() {
        return retenidos.size();
    }
}
