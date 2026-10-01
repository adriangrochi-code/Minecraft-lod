package com.example.minecraftlodmod.cubico;

import com.example.minecraftlodmod.config.ConfigLod;
import com.mojang.logging.LogUtils;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sincronización vertical, etapa 1 de los "cubic chunks" del mod (sección 31
 * de la arquitectura). Diseño inspirado en Vertigo (Builderb0y, MIT,
 * https://github.com/Builderb0y/Vertigo), escrito de nuevo para NeoForge.
 *
 * El servidor sigue cargando y generando columnas enteras; lo que cambia es
 * qué le manda a cada cliente: de cada columna, solo las secciones a menos de
 * {@code distancia} secciones (en Y) del jugador. Las demás le llegan como
 * aire. Al subir o bajar, se mandan las secciones que entran al rango y se
 * vacían las que salen. El cliente ahorra memoria, red y armado de mallas
 * (Sodium incluido: recibe datos normales); lo que no tiene lo dibuja el LOD
 * ({@code ClienteVertical}, "LOD vertical": islas flotantes, montañas altas).
 *
 * Opt-in desde el cliente (opción experimental) y solo si el servidor lo
 * permite. Todo en el hilo del servidor.
 */
public final class SincroVertical {

    private static final Logger LOG = LogUtils.getLogger();
    public static final String VERSION = "1";

    private SincroVertical() {
    }

    /** Estado por jugador: preferencia y rango enviado de cada columna. */
    static final class Estado {
        volatile boolean activa;
        volatile int distancia = 8;
        /** La preferencia cambió: re-evaluar todas las columnas en el próximo tick. */
        volatile boolean cambio;
        int seccionCentro = Integer.MIN_VALUE;
        /** Columna → rango que tiene el cliente ({@link RangoSecciones#empaquetado()}). */
        final Long2LongOpenHashMap rangos = new Long2LongOpenHashMap();
    }

    private static final Map<UUID, Estado> ESTADOS = new ConcurrentHashMap<>();
    /** Rango con que se está armando el paquete de chunk en este hilo (null = la columna entera). */
    static final ThreadLocal<RangoSecciones> RANGO_EN_ENVIO = new ThreadLocal<>();

    /** Estados de bloque de una sección de aire, para escribir las secciones ocultas. */
    private static final PalettedContainer<BlockState> AIRE = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY,
            Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES);

    // ------------------------------------------------------------------ registro

    /** Bus del mod. */
    public static void registrar(RegisterPayloadHandlersEvent evento) {
        PayloadRegistrar r = evento.registrar(VERSION).optional();
        r.playToServer(PaquetesVerticales.Preferencia.TYPE, PaquetesVerticales.Preferencia.STREAM_CODEC,
                SincroVertical::alRecibirPreferencia);
        r.playToClient(PaquetesVerticales.Rango.TYPE, PaquetesVerticales.Rango.STREAM_CODEC,
                (p, c) -> ClienteVertical.alRecibirRango(p));
        r.playToClient(PaquetesVerticales.CargaSeccion.TYPE, PaquetesVerticales.CargaSeccion.STREAM_CODEC,
                (p, c) -> ClienteVertical.alRecibirCarga(p));
        r.playToClient(PaquetesVerticales.DescargaSeccion.TYPE, PaquetesVerticales.DescargaSeccion.STREAM_CODEC,
                (p, c) -> ClienteVertical.alRecibirDescarga(p));
    }

    private static void alRecibirPreferencia(PaquetesVerticales.Preferencia p, IPayloadContext contexto) {
        if (!(contexto.player() instanceof ServerPlayer jugador)) {
            return;
        }
        Estado e = ESTADOS.computeIfAbsent(jugador.getUUID(), k -> new Estado());
        boolean activa = p.activa() && ConfigLod.SERVIDOR.permitirSincroVertical.get();
        int distancia = Math.max(DISTANCIA_MIN, Math.min(DISTANCIA_MAX, p.distancia()));
        if (activa != e.activa || distancia != e.distancia) {
            e.activa = activa;
            e.distancia = distancia;
            e.cambio = true;
            LOG.info("LOD: sincronización vertical {} para {} ({} secciones)", activa ? "activa" : "apagada",
                    jugador.getGameProfile().getName(), distancia);
        }
    }

    public static final int DISTANCIA_MIN = 2, DISTANCIA_MAX = 32;

    // ------------------------------------------------------------------ eventos del juego

    @SubscribeEvent
    public static void alEntrar(PlayerEvent.PlayerLoggedInEvent evento) {
        ESTADOS.computeIfAbsent(evento.getEntity().getUUID(), k -> new Estado());
    }

    @SubscribeEvent
    public static void alSalir(PlayerEvent.PlayerLoggedOutEvent evento) {
        ESTADOS.remove(evento.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void alCambiarDimension(PlayerEvent.PlayerChangedDimensionEvent evento) {
        Estado e = ESTADOS.get(evento.getEntity().getUUID());
        if (e != null) {
            e.rangos.clear(); // el cliente tira todos los chunks de la dimensión vieja
        }
    }

    @SubscribeEvent
    public static void alReaparecer(PlayerEvent.PlayerRespawnEvent evento) {
        Estado e = ESTADOS.get(evento.getEntity().getUUID());
        if (e != null) {
            e.rangos.clear();
        }
    }

    /** Cada tick del jugador: si cambió de sección (o de preferencia), mueve los rangos. */
    @SubscribeEvent
    public static void alTickJugador(PlayerTickEvent.Post evento) {
        if (!(evento.getEntity() instanceof ServerPlayer jugador)) {
            return;
        }
        Estado e = ESTADOS.get(jugador.getUUID());
        if (e == null || e.rangos.isEmpty()) {
            return;
        }
        int centro = jugador.getBlockY() >> 4;
        if (centro == e.seccionCentro && !e.cambio) {
            return;
        }
        boolean cambioPreferencia = e.cambio;
        e.cambio = false;
        e.seccionCentro = centro;
        actualizar(jugador, e, cambioPreferencia);
    }

    // ------------------------------------------------------------------ envío de chunks (mixins)

    /** Antes de armar el paquete de una columna para el jugador (MixinPlayerChunkSender). */
    public static void antesDeEnviarChunk(ServerPlayer jugador, LevelChunk chunk) {
        Estado e = ESTADOS.get(jugador.getUUID());
        RANGO_EN_ENVIO.set(e != null && e.activa ? objetivo(e, jugador) : null);
    }

    /** Después de enviarla: se recuerda el rango y, si es parcial, se le avisa al cliente. */
    public static void despuesDeEnviarChunk(ServerPlayer jugador, LevelChunk chunk) {
        RangoSecciones rango = RANGO_EN_ENVIO.get();
        RANGO_EN_ENVIO.remove();
        Estado e = ESTADOS.get(jugador.getUUID());
        if (e == null) {
            return;
        }
        if (rango == null) {
            rango = completo(jugador.serverLevel());
        }
        long clave = chunk.getPos().toLong();
        e.rangos.put(clave, rango.empaquetado());
        if (e.activa) {
            PacketDistributor.sendToPlayer(jugador,
                    new PaquetesVerticales.Rango(chunk.getPos().x, chunk.getPos().z, rango.min(), rango.max()));
        }
    }

    /** El cliente olvida la columna (MixinPlayerChunkSender#dropChunk). */
    public static void alSoltarChunk(ServerPlayer jugador, ChunkPos pos) {
        Estado e = ESTADOS.get(jugador.getUUID());
        if (e != null) {
            e.rangos.remove(pos.toLong());
        }
    }

    /** Rango del paquete de columna que se está armando en este hilo, o null (la columna entera). */
    public static RangoSecciones rangoEnEnvio() {
        return RANGO_EN_ENVIO.get();
    }

    /** Bytes de una sección oculta: aire, con los biomas reales (el cliente los usa para el cielo y la niebla). */
    public static int tamanoOculta(LevelChunkSection real) {
        return 2 + AIRE.getSerializedSize() + real.getBiomes().getSerializedSize();
    }

    public static void escribirOculta(FriendlyByteBuf buf, LevelChunkSection real) {
        buf.writeShort(0);
        AIRE.write(buf);
        real.getBiomes().write(buf);
    }

    // ------------------------------------------------------------------ cambios de bloques (MixinChunkHolder)

    /**
     * Para los paquetes que dependen de la sección (bloque, sección, block entity):
     * a qué jugadores mandarlos. null = no es de esos, que lo mande vanilla a todos.
     */
    public static List<ServerPlayer> filtrar(List<ServerPlayer> jugadores, Packet<?> paquete) {
        if (paquete instanceof ClientboundBlockUpdatePacket p) {
            return visibles(jugadores, p.getPos());
        }
        if (paquete instanceof ClientboundBlockEntityDataPacket p) {
            return visibles(jugadores, p.getPos());
        }
        if (paquete instanceof ClientboundSectionBlocksUpdatePacket p) {
            SectionPos s = ((com.example.minecraftlodmod.cubico.mixin.AccesoSeccionBloques) p).minecraftlodmod$seccion();
            return visibles(jugadores, s.x(), s.y(), s.z());
        }
        return null;
    }

    private static List<ServerPlayer> visibles(List<ServerPlayer> jugadores, BlockPos pos) {
        return visibles(jugadores, pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
    }

    private static List<ServerPlayer> visibles(List<ServerPlayer> jugadores, int chunkX, int seccionY, int chunkZ) {
        List<ServerPlayer> r = null;
        for (int i = 0; i < jugadores.size(); i++) {
            ServerPlayer j = jugadores.get(i);
            boolean ve = seccionVisible(j, chunkX, seccionY, chunkZ);
            if (!ve && r == null) {
                r = new ArrayList<>(jugadores.subList(0, i)); // a partir del primero que no la ve
            } else if (ve && r != null) {
                r.add(j);
            }
        }
        return r == null ? jugadores : r;
    }

    /** El cliente del jugador tiene los bloques reales de esa sección. */
    public static boolean seccionVisible(ServerPlayer jugador, int chunkX, int seccionY, int chunkZ) {
        Estado e = ESTADOS.get(jugador.getUUID());
        if (e == null) {
            return true;
        }
        long v = e.rangos.getOrDefault(ChunkPos.asLong(chunkX, chunkZ), Long.MIN_VALUE);
        return v == Long.MIN_VALUE || RangoSecciones.desempaquetar(v).contiene(seccionY);
    }

    // ------------------------------------------------------------------ movimiento vertical

    private static RangoSecciones completo(ServerLevel nivel) {
        return new RangoSecciones(nivel.getMinSection(), nivel.getMaxSection() - 1);
    }

    private static RangoSecciones objetivo(Estado e, ServerPlayer jugador) {
        ServerLevel nivel = jugador.serverLevel();
        return RangoSecciones.alrededor(jugador.getBlockY() >> 4, e.distancia, nivel.getMinSection(),
                nivel.getMaxSection() - 1);
    }

    private static void actualizar(ServerPlayer jugador, Estado e, boolean cambioPreferencia) {
        ServerLevel nivel = jugador.serverLevel();
        RangoSecciones objetivo = e.activa ? objetivo(e, jugador) : completo(nivel);
        int cargadas = 0, descargadas = 0;
        for (Long2LongMap.Entry entrada : e.rangos.long2LongEntrySet()) {
            long clave = entrada.getLongKey();
            RangoSecciones actual = RangoSecciones.desempaquetar(entrada.getLongValue());
            RangoSecciones nuevo = e.activa ? RangoSecciones.mover(actual, objetivo) : objetivo;
            if (nuevo.equals(actual) && !cambioPreferencia) {
                continue;
            }
            int chunkX = ChunkPos.getX(clave), chunkZ = ChunkPos.getZ(clave);
            LevelChunk chunk = nivel.getChunkSource().getChunkNow(chunkX, chunkZ);
            if (chunk == null) {
                continue; // se está descargando: dropChunk lo saca
            }
            for (int sy = actual.min(); sy <= actual.max(); sy++) {
                if (!nuevo.contiene(sy)) {
                    PacketDistributor.sendToPlayer(jugador, new PaquetesVerticales.DescargaSeccion(chunkX, sy, chunkZ));
                    descargadas++;
                }
            }
            for (int sy = nuevo.min(); sy <= nuevo.max(); sy++) {
                if (!actual.contiene(sy)) {
                    enviarSeccion(jugador, nivel, chunk, sy);
                    cargadas++;
                }
            }
            entrada.setValue(nuevo.empaquetado());
            // También al apagarla: el cliente vuelve a tratar la columna como completa.
            PacketDistributor.sendToPlayer(jugador, new PaquetesVerticales.Rango(chunkX, chunkZ, nuevo.min(), nuevo.max()));
        }
        if (cargadas + descargadas > 0) {
            ESTADISTICAS.secciones(cargadas, descargadas);
        }
    }

    private static void enviarSeccion(ServerPlayer jugador, ServerLevel nivel, LevelChunk chunk, int seccionY) {
        int indice = chunk.getSectionIndexFromSectionY(seccionY);
        LevelChunkSection seccion = chunk.getSection(indice);
        io.netty.buffer.ByteBuf buf = Unpooled.buffer(seccion.getSerializedSize());
        seccion.write(new FriendlyByteBuf(buf));
        byte[] datos = new byte[buf.writerIndex()];
        buf.readBytes(datos);
        SectionPos pos = SectionPos.of(chunk.getPos(), seccionY);
        PacketDistributor.sendToPlayer(jugador, new PaquetesVerticales.CargaSeccion(chunk.getPos().x, seccionY,
                chunk.getPos().z, datos, luz(nivel, LightLayer.SKY, pos), luz(nivel, LightLayer.BLOCK, pos)));
        ESTADISTICAS.bytes(datos.length);
        // Los block entities de la sección, con el paquete de vanilla (el cliente los crea al recibirlo).
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            if (be.getBlockPos().getY() >> 4 == seccionY) {
                var paquete = be.getUpdatePacket();
                if (paquete != null) {
                    jugador.connection.send(paquete);
                }
            }
        }
    }

    private static byte[] luz(ServerLevel nivel, LightLayer capa, SectionPos pos) {
        DataLayer datos = nivel.getLightEngine().getLayerListener(capa).getDataLayerData(pos);
        return datos == null ? new byte[0] : datos.copy().getData();
    }

    // ------------------------------------------------------------------ medición

    /** Lo que movió la sincronización vertical (para el log de estadísticas y la medición). */
    public static final Estadisticas ESTADISTICAS = new Estadisticas();

    public static final class Estadisticas {
        private final java.util.concurrent.atomic.LongAdder cargadas = new java.util.concurrent.atomic.LongAdder();
        private final java.util.concurrent.atomic.LongAdder descargadas = new java.util.concurrent.atomic.LongAdder();
        private final java.util.concurrent.atomic.LongAdder bytes = new java.util.concurrent.atomic.LongAdder();

        void secciones(int c, int d) {
            cargadas.add(c);
            descargadas.add(d);
        }

        void bytes(long b) {
            bytes.add(b);
        }

        /** "cargadas/descargadas/KB" desde la última llamada. */
        public String resumenYReiniciar() {
            return cargadas.sumThenReset() + " cargadas, " + descargadas.sumThenReset() + " vaciadas, "
                    + (bytes.sumThenReset() >> 10) + " KB";
        }
    }
}
