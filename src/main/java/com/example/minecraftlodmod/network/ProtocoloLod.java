package com.example.minecraftlodmod.network;

import com.example.minecraftlodmod.config.ConfigLod;
import com.example.minecraftlodmod.config.LimitesRed;
import com.example.minecraftlodmod.config.ParametrosCalidad;
import com.example.minecraftlodmod.generation.GeneradorLocal;
import com.example.minecraftlodmod.generation.GenerationTaskScheduler;
import com.example.minecraftlodmod.storage.RegionFileStore;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.HandlerThread;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Protocolo cliente-servidor (hito 7, sección 10 del documento de arquitectura).
 *
 * 1. {@link PedidoNodosPayload} (cliente → servidor): lote de nodos que
 *    pide el selector de LOD del cliente.
 * 2. {@link RespuestaNodoPayload} (servidor → cliente): un nodo en el
 *    formato de {@code OctreeNodeCodec}, leído del cache de disco que llena
 *    {@link GeneradorLocal} (modo LOCAL del servidor).
 * 3. Servidor: valida radio/altura/ritmo con {@link LimitadorPedidos} en el
 *    hilo principal y lee disco en el pool de generación, nunca en el hilo
 *    principal ni en el de red.
 * 4. Cliente: detecta si el servidor tiene el companion con
 *    {@link ClienteLod#servidorTieneCompanion()} — el canal se negocia al
 *    conectar, sin sondeo ni timeout. Los payloads son opcionales
 *    ({@code optional()}): clientes sin el mod entran a un servidor con el
 *    mod y viceversa; sin companion, el cliente queda en modo de
 *    compatibilidad (solo LOD de zonas visitadas localmente).
 *
 * Integración pendiente (Pista B, con render/): quién pide nodos (selector
 * del cliente) y quién consume las respuestas ({@link #asignarReceptor}).
 */
public final class ProtocoloLod {

    /** Versión del protocolo; subirla si cambia el formato de los payloads. */
    public static final String VERSION = "2";

    private static volatile Consumer<RespuestaNodoPayload> receptor = r -> { };

    private final GeneradorLocal generador;
    /** Se crea con el primer pedido de cada servidor (límites de su config); solo hilo principal. */
    private LimitadorPedidos limitador;
    private final ServidorRebanadas rebanadas = new ServidorRebanadas();

    public ProtocoloLod(GeneradorLocal generador) {
        this.generador = generador;
    }

    /**
     * Quién recibe las respuestas en el cliente. Se invoca en el HILO DE RED
     * (decodificar y cachear un nodo no necesita el hilo principal), así que
     * el receptor tiene que ser thread-safe.
     */
    public static void asignarReceptor(Consumer<RespuestaNodoPayload> nuevoReceptor) {
        receptor = nuevoReceptor;
    }

    /** Listener del bus del mod. */
    public void registrar(RegisterPayloadHandlersEvent evento) {
        PayloadRegistrar registrar = evento.registrar(VERSION).optional();
        registrar.playToServer(PedidoNodosPayload.TYPE, PedidoNodosPayload.STREAM_CODEC, this::alRecibirPedido);
        registrar.executesOn(HandlerThread.NETWORK)
                .playToClient(RespuestaNodoPayload.TYPE, RespuestaNodoPayload.STREAM_CODEC,
                        (respuesta, contexto) -> receptor.accept(respuesta));
        // Rebanadas (modo REMOTO del cliente, EspejoServidor): el pedido en el hilo del servidor,
        // la respuesta se guarda en el hilo de red (el store es thread-safe).
        registrar.playToServer(PedidoRebanadasPayload.TYPE, PedidoRebanadasPayload.STREAM_CODEC,
                this::alPedirRebanadas);
        registrar.executesOn(HandlerThread.NETWORK)
                .playToClient(RebanadaPayload.TYPE, RebanadaPayload.STREAM_CODEC,
                        (parte, contexto) -> ManejoCliente.recibir(parte));
    }

    /** Indirección para que el servidor dedicado no cargue {@link EspejoServidor} (clases de cliente). */
    private static final class ManejoCliente {
        static void recibir(RebanadaPayload parte) {
            EspejoServidor.recibir(parte);
        }
    }

    private void alPedirRebanadas(PedidoRebanadasPayload pedido, IPayloadContext contexto) {
        if (!(contexto.player() instanceof ServerPlayer jugador) || generador.calidad() == null) {
            return;
        }
        rebanadas.pedir(jugador, pedido.rebanadas(), pedido.huellas(),
                ConfigLod.limitesRed(generador.calidad()).radioMaximoChunks());
    }

    /** Listener del bus de NeoForge: manda las rebanadas leídas a ritmo fijo. */
    @SubscribeEvent
    public void alTerminarTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post evento) {
        rebanadas.tick(evento.getServer(), generador, ConfigLod.SERVIDOR.kbPorSegundo.get() * 1024);
    }

    /** Listener del bus de NeoForge: libera el estado del limitador. */
    @SubscribeEvent
    public void alDesconectarse(PlayerEvent.PlayerLoggedOutEvent evento) {
        if (limitador != null) {
            limitador.olvidar(evento.getEntity().getUUID());
        }
        rebanadas.olvidar(evento.getEntity().getUUID());
    }

    @SubscribeEvent
    public void alDetenerServidor(ServerStoppingEvent evento) {
        limitador = null;
        rebanadas.olvidarTodo();
    }

    /** Hilo principal del servidor (default de {@code PayloadRegistrar}). */
    private void alRecibirPedido(PedidoNodosPayload pedido, IPayloadContext contexto) {
        if (!(contexto.player() instanceof ServerPlayer jugador)) {
            return;
        }
        RegionFileStore store = generador.store();
        GenerationTaskScheduler scheduler = generador.scheduler();
        ParametrosCalidad calidad = generador.calidad();
        if (store == null || scheduler == null || calidad == null) {
            return;
        }
        if (limitador == null) {
            LimitesRed limites = ConfigLod.limitesRed(calidad);
            limitador = new LimitadorPedidos(limites.radioMaximoChunks(), limites.nodosPorSegundo(), limites.rafagaNodos());
        }
        ServerLevel nivel = jugador.serverLevel();
        int chunkX = jugador.chunkPosition().x;
        int chunkZ = jugador.chunkPosition().z;

        List<NodoId> validos = new ArrayList<>(pedido.nodos().size());
        for (NodoId nodo : pedido.nodos()) {
            // La Y fuera del rango real de la dimensión se descarta: la clave
            // de nodo usa 12 bits de Y y un valor absurdo podría aliasear otro nodo.
            if (limitador.dentroDelRadio(nodo, chunkX, chunkZ)
                    && nodo.seccionY() >= nivel.getMinSection() && nodo.seccionY() < nivel.getMaxSection()) {
                validos.add(nodo);
            }
        }
        if (validos.isEmpty() || !limitador.consumir(jugador.getUUID(), validos.size(), System.nanoTime())) {
            return;
        }

        byte dimensionId = GeneradorLocal.idDimension(nivel.dimension());
        // Si la cola de generación está llena, el pedido se descarta: el
        // cliente vuelve a pedir lo que no le llegó.
        scheduler.intentarEnviar(() -> {
            for (NodoId nodo : validos) {
                PacketDistributor.sendToPlayer(jugador, responder(store, dimensionId, nodo));
            }
            return null;
        });
    }

    static RespuestaNodoPayload responder(RegionFileStore store, byte dimensionId, NodoId nodo) {
        RegionFileStore.ClaveRegion region = GeneradorLocal.claveRegion(dimensionId, nodo.seccionX(), nodo.seccionZ());
        byte[] datos = store.leer(region, nodo.claveNodo());
        if (datos != null) {
            return new RespuestaNodoPayload(dimensionId, nodo, RespuestaNodoPayload.Estado.EXISTE, datos);
        }
        boolean chunkGenerado = GeneradorLocal.tieneMarca(store, region, nodo.seccionX(), nodo.seccionZ());
        return new RespuestaNodoPayload(dimensionId, nodo,
                chunkGenerado ? RespuestaNodoPayload.Estado.VACIO : RespuestaNodoPayload.Estado.NO_GENERADO, null);
    }
}
