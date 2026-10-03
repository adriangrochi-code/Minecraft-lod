package com.example.minecraftlodmod.network;

import com.example.minecraftlodmod.generation.GeneradorLocal;
import com.example.minecraftlodmod.generation.GenerationTaskScheduler;
import com.example.minecraftlodmod.storage.RegionFileStore;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lado servidor de las rebanadas ({@link RebanadaId}): por jugador, una cola de
 * lo pedido (validado contra el radio servido), una lectura por vez en el pool de
 * generación (nunca disco en el hilo del servidor) y la salida a un ritmo fijo de
 * bytes por segundo, para no ahogar la conexión de nadie. Hilo del servidor salvo
 * la lectura.
 */
final class ServidorRebanadas {

    /** Rebanadas en cola por jugador: lo de más se descarta (el cliente vuelve a pedirlo). */
    static final int MAX_COLA = 512;

    private static final class Jugador {
        final ArrayDeque<RebanadaId> cola = new ArrayDeque<>();
        /** Huella que tiene el cliente de cada rebanada en cola (empaquetada → huella). */
        final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap huellas = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
        final it.unimi.dsi.fastutil.longs.LongOpenHashSet enCola = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        final ConcurrentLinkedQueue<RebanadaPayload> salida = new ConcurrentLinkedQueue<>();
        final AtomicBoolean leyendo = new AtomicBoolean();
        double credito;
    }

    private final Map<UUID, Jugador> jugadores = new HashMap<>();

    /** Hilo del servidor: encola lo que está dentro del radio. */
    void pedir(ServerPlayer jugador, List<RebanadaId> rebanadas, long[] huellas, int radioChunks) {
        Jugador j = jugadores.computeIfAbsent(jugador.getUUID(), u -> new Jugador());
        int cx = jugador.chunkPosition().x, cz = jugador.chunkPosition().z;
        byte dimension = GeneradorLocal.idDimension(jugador.serverLevel().dimension());
        for (int i = 0; i < rebanadas.size(); i++) {
            RebanadaId r = rebanadas.get(i);
            if (j.cola.size() >= MAX_COLA) {
                return;
            }
            if (!r.dentroDelRadio(cx, cz, radioChunks)) {
                // Respuesta vacía en vez de silencio: si no, el cliente la cuenta "en vuelo"
                // hasta que vence la espera, y con su radio mayor que el servido (horizonte)
                // esas esperas ocupaban todos sus pedidos y lo cercano no llegaba.
                j.salida.add(RebanadaPayload.sinCambios(dimension, r, 0));
            } else if (j.enCola.add(r.empaquetada())) {
                j.cola.add(r);
                j.huellas.put(r.empaquetada(), huellas[i]);
            }
        }
    }

    /** Cada tick: manda lo leído según el crédito de bytes y lanza la próxima lectura. */
    void tick(net.minecraft.server.MinecraftServer servidor, GeneradorLocal generador, int bytesPorSegundo) {
        RegionFileStore store = generador.store();
        GenerationTaskScheduler scheduler = generador.scheduler();
        if (jugadores.isEmpty() || store == null || scheduler == null) {
            return;
        }
        double porTick = bytesPorSegundo / 20.0;
        Iterator<Map.Entry<UUID, Jugador>> it = jugadores.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Jugador> e = it.next();
            ServerPlayer jugador = servidor.getPlayerList().getPlayer(e.getKey());
            if (jugador == null) {
                it.remove();
                continue;
            }
            Jugador j = e.getValue();
            j.credito = Math.min(j.credito + porTick, bytesPorSegundo);
            RebanadaPayload parte;
            while (j.credito > 0 && (parte = j.salida.poll()) != null) {
                j.credito -= parte.bytesAproximados();
                PacketDistributor.sendToPlayer(jugador, parte);
            }
            if (j.salida.isEmpty() && !j.cola.isEmpty() && j.leyendo.compareAndSet(false, true)) {
                RebanadaId r = j.cola.poll();
                j.enCola.remove(r.empaquetada());
                long conocida = j.huellas.remove(r.empaquetada());
                byte dimension = GeneradorLocal.idDimension(jugador.serverLevel().dimension());
                boolean aceptada = null != scheduler.intentarEnviar(() -> {
                    try {
                        j.salida.addAll(leer(store, dimension, r, conocida));
                    } finally {
                        j.leyendo.set(false);
                    }
                    return null;
                });
                if (!aceptada) {
                    // Cola de generación llena: se reintenta el próximo tick.
                    j.leyendo.set(false);
                    j.cola.addFirst(r);
                    j.enCola.add(r.empaquetada());
                    j.huellas.put(r.empaquetada(), conocida);
                }
            }
        }
    }

    /**
     * Todas las entradas de la rebanada, comprimidas como están en el store, partidas
     * en paquetes; o un "sin cambios" si el cliente ya tiene la huella actual.
     */
    static List<RebanadaPayload> leer(RegionFileStore store, byte dimension, RebanadaId r, long huellaCliente) {
        RegionFileStore.ClaveRegion region = new RegionFileStore.ClaveRegion(dimension, r.regionX(), r.regionZ());
        long huella = store.huella(region, clave -> RebanadaId.codigo(clave) == r.codigo());
        if (huellaCliente != 0 && huella == huellaCliente) {
            return List.of(RebanadaPayload.sinCambios(dimension, r, huella));
        }
        long[] todas = store.claves(region);
        it.unimi.dsi.fastutil.longs.LongArrayList claves = new it.unimi.dsi.fastutil.longs.LongArrayList();
        java.util.ArrayList<byte[]> datos = new java.util.ArrayList<>();
        java.util.Arrays.sort(todas);
        for (long clave : todas) {
            if (RebanadaId.codigo(clave) != r.codigo()) {
                continue;
            }
            byte[] d = store.leerComprimido(region, clave);
            if (d != null && d.length <= RebanadaPayload.MAX_BYTES_ENTRADA) {
                claves.add(clave);
                datos.add(d);
            }
        }
        return RebanadaPayload.partir(dimension, r, huella, claves.toLongArray(), datos.toArray(new byte[0][]));
    }

    void olvidar(UUID jugador) {
        jugadores.remove(jugador);
    }

    void olvidarTodo() {
        jugadores.clear();
    }
}
