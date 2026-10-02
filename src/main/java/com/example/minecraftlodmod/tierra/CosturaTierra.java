package com.example.minecraftlodmod.tierra;

import com.mojang.logging.LogUtils;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.network.bundle.PacketAndPayloadAcceptor;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Circunnavegación este-oeste de la Tierra cilíndrica (H10,
 * {@code docs/tierra-real/05-borde.md}). El terreno es periódico en x
 * ({@link Costura}): pasando el antimeridiano se genera la continuación del
 * otro lado, idéntica. Lo que la cruza por más de {@link Costura#MARGEN_SALTO}
 * bloques se lleva una vuelta atrás:
 * <ul>
 *   <li><b>jugadores:</b> teletransporte relativo (sin pantalla de carga; el
 *       cliente conserva velocidad, mirada y vuelo con élitros), con su
 *       vehículo y los demás pasajeros, y los animales que lleva con rienda;</li>
 *   <li><b>otras entidades</b> (mobs, ítems, flechas, botes vacíos): solas, si
 *       el chunk de llegada está cargado (si no, quedan donde están).</li>
 * </ul>
 * Mientras un jugador está cerca de la costura, los chunks del otro lado
 * quedan cargados ({@link #PRECARGA}). El salto <b>espera</b> a que los
 * chunks de llegada estén cargados: NeoForge carga el chunk de destino de
 * forma síncrona al mover una entidad ({@code Entity#setPosRaw}), y si
 * todavía se estaba generando el servidor quedaba trabado decenas de segundos
 * (medido en H10, cruzando en carrito a 8 bloques/s). Mientras espera, el
 * jugador sigue en la continuación del otro lado, que es el mismo terreno.
 */
public final class CosturaTierra {

    private static final Logger LOG = LogUtils.getLogger();
    /** Ticket de los chunks del otro lado de la costura; vence solo si el jugador se aleja. */
    private static final TicketType<ChunkPos> PRECARGA =
            TicketType.create("minecraftlodmod_costura", Comparator.comparingLong(ChunkPos::toLong), 60);
    /** Desde esta distancia a la costura (además de la de vista) se precarga el otro lado. */
    private static final double DISTANCIA_PRECARGA = 1024;

    private static final Map<ServerLevel, Double> PERIODOS = new WeakHashMap<>();

    private CosturaTierra() {}

    /** Período en x del nivel (la vuelta al ecuador), 0 si no es la Tierra cilíndrica. Solo hilo del servidor. */
    static double periodo(ServerLevel nivel) {
        return PERIODOS.computeIfAbsent(nivel, n -> {
            AlturaTierra a = TierraReal.alturaDe(n);
            return a == null ? 0.0 : a.periodoX();
        });
    }

    @SubscribeEvent
    public static void alTerminarTickNivel(LevelTickEvent.Post evento) {
        // Al final del tick del nivel y antes del de las conexiones: así la conexión toma como
        // última posición buena del vehículo la nueva (si no, un paquete viejo del cliente movería
        // el vehículo de vuelta 5 M de bloques, con colisiones por todo el camino).
        if (!(evento.getLevel() instanceof ServerLevel nivel)) return;
        double c = periodo(nivel);
        if (c <= 0) return;
        for (ServerPlayer jugador : List.copyOf(nivel.players())) {
            Entity raiz = jugador.getRootVehicle();
            double dx = Costura.salto(raiz.getX(), c, Costura.MARGEN_SALTO);
            if (dx != 0 && llegadaCargada(nivel, raiz.getX() + dx, raiz.getZ())) {
                cruzar(jugador, raiz, dx, nivel);
            } else if ((nivel.getGameTime() + jugador.getId()) % 20 == 0) {
                precargar(nivel, jugador, c);
            }
        }
    }

    @SubscribeEvent
    public static void alTerminarTickEntidad(EntityTickEvent.Post evento) {
        Entity e = evento.getEntity();
        if (e instanceof ServerPlayer || e.isPassenger() || !(e.level() instanceof ServerLevel nivel)) return;
        double c = periodo(nivel);
        if (c <= 0) return;
        double dx = Costura.salto(e.getX(), c, Costura.MARGEN_SALTO);
        if (dx == 0) return;
        for (Entity p : e.getIndirectPassengers()) if (p instanceof ServerPlayer) return; // lo lleva el jugador
        if (!nivel.hasChunk(Mth.floor(e.getX() + dx) >> 4, Mth.floor(e.getZ()) >> 4)) return;
        e.teleportTo(e.getX() + dx, e.getY(), e.getZ());
    }

    /** Lleva al jugador (y lo que viaja con él) una vuelta en x. */
    private static void cruzar(ServerPlayer jugador, Entity raiz, double dx, ServerLevel nivel) {
        List<ServerPlayer> jugadores = new ArrayList<>();
        if (raiz == jugador) jugadores.add(jugador);
        for (Entity p : raiz.getIndirectPassengers()) if (p instanceof ServerPlayer sp) jugadores.add(sp);
        // Animales con rienda de estos jugadores (a más de 10 bloques la rienda se corta)
        List<Entity> conRienda = new ArrayList<>();
        for (ServerPlayer j : jugadores) {
            for (Entity e : nivel.getEntities(j, j.getBoundingBox().inflate(12),
                    e -> e instanceof Leashable l && l.getLeashHolder() == j && !e.isPassenger())) {
                conRienda.add(e);
            }
        }
        // Primero el paquete relativo a cada jugador (el servidor ignora sus paquetes de movimiento
        // hasta que el cliente lo confirma: ninguno con la posición vieja llega a mover nada)
        for (ServerPlayer j : jugadores) {
            j.connection.teleport(j.getX() + dx, j.getY(), j.getZ(), j.getYRot(), j.getXRot(), RelativeMovement.ALL);
        }
        if (raiz != jugador) {
            raiz.teleportTo(raiz.getX() + dx, raiz.getY(), raiz.getZ()); // mueve también a los pasajeros
        }
        for (Entity e : conRienda) e.teleportTo(e.getX() + dx, e.getY(), e.getZ());
        // El cliente descarga los chunks de donde estaba: el vehículo (y con él el jugador, que se
        // actualiza a través de él) quedaría quieto en un chunk sin actualizar, sin llegar nunca a la
        // posición nueva (pasaba con un carrito: el jugador veía solo cielo). Se le manda de nuevo,
        // ya en la posición nueva, como si recién lo viera; lo mismo los animales con rienda.
        List<Entity> reenviar = new ArrayList<>(conRienda);
        if (raiz != jugador) raiz.getSelfAndPassengers().forEach(reenviar::add);
        for (ServerPlayer j : jugadores) {
            List<Packet<? super ClientGamePacketListener>> paquetes = new ArrayList<>();
            PacketAndPayloadAcceptor<ClientGamePacketListener> destino = new PacketAndPayloadAcceptor<>(paquetes::add);
            for (Entity e : reenviar) {
                if (e != j) new ServerEntity(nivel, e, 1, true, p -> {}).sendPairingData(j, destino);
            }
            if (!paquetes.isEmpty()) j.connection.send(new ClientboundBundlePacket(paquetes));
        }
        if (raiz.getControllingPassenger() instanceof ServerPlayer conductor && raiz != conductor) {
            // El cliente que lo maneja no acepta teletransportes del vehículo: la posición va así
            conductor.connection.send(new ClientboundMoveVehiclePacket(raiz));
        }
        LOG.debug("[Tierra real] {} dio la vuelta al mundo: x {} → {}", jugador.getName().getString(),
                (long) (raiz.getX() - dx), (long) raiz.getX());
    }

    /** Los 3×3 chunks alrededor del punto de llegada están cargados (si no, mover ahí bloquea el servidor). */
    private static boolean llegadaCargada(ServerLevel nivel, double x, double z) {
        int cx = Mth.floor(x) >> 4, cz = Mth.floor(z) >> 4;
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                if (!nivel.getChunkSource().hasChunk(cx + i, cz + j)) return false;
            }
        }
        return true;
    }

    /** Si el jugador está cerca de la costura, carga los chunks del otro lado alrededor de donde va a aparecer. */
    private static void precargar(ServerLevel nivel, ServerPlayer jugador, double c) {
        int vista = nivel.getServer().getPlayerList().getViewDistance();
        double x = jugador.getX();
        if (Math.abs(x) < c / 2 - vista * 16 - DISTANCIA_PRECARGA) return;
        double espejo = x > 0 ? x - c : x + c;
        ChunkPos pos = new ChunkPos(Mth.floor(espejo) >> 4, Mth.floor(jugador.getZ()) >> 4);
        nivel.getChunkSource().addRegionTicket(PRECARGA, pos, vista + 1, pos);
    }
}
