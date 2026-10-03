package com.example.minecraftlodmod.network;

import com.example.minecraftlodmod.MinecraftLodMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Cliente → servidor: lote de nodos que pide el selector de LOD del
 * cliente, en la dimensión donde está el jugador.
 *
 * Por nodo: nivel (1 byte) + sección X/Y/Z en VarInt — ~5-10 bytes, así que
 * un lote de {@link LimitadorPedidos#MAX_NODOS_POR_PEDIDO} queda lejos del
 * límite de 32 KB de un paquete cliente → servidor.
 */
public record PedidoNodosPayload(List<NodoId> nodos) implements CustomPacketPayload {

    public static final Type<PedidoNodosPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MinecraftLodMod.MOD_ID, "pedido_nodos"));

    public static final StreamCodec<FriendlyByteBuf, PedidoNodosPayload> STREAM_CODEC =
            StreamCodec.of(PedidoNodosPayload::escribir, PedidoNodosPayload::leer);

    public PedidoNodosPayload {
        nodos = List.copyOf(nodos);
    }

    private static void escribir(FriendlyByteBuf buf, PedidoNodosPayload pedido) {
        buf.writeVarInt(pedido.nodos.size());
        for (NodoId nodo : pedido.nodos) {
            escribirNodo(buf, nodo);
        }
    }

    private static PedidoNodosPayload leer(FriendlyByteBuf buf) {
        int cantidad = buf.readVarInt();
        // Se valida antes de reservar: un cliente modificado podría mandar
        // un tamaño enorme para forzar una asignación gigante.
        if (cantidad < 0 || cantidad > LimitadorPedidos.MAX_NODOS_POR_PEDIDO) {
            throw new IllegalArgumentException("Pedido de nodos con tamaño inválido: " + cantidad);
        }
        List<NodoId> nodos = new ArrayList<>(cantidad);
        for (int i = 0; i < cantidad; i++) {
            nodos.add(leerNodo(buf));
        }
        return new PedidoNodosPayload(nodos);
    }

    static void escribirNodo(FriendlyByteBuf buf, NodoId nodo) {
        buf.writeByte(nodo.nivel());
        buf.writeVarInt(nodo.seccionX());
        buf.writeVarInt(nodo.seccionY());
        buf.writeVarInt(nodo.seccionZ());
    }

    static NodoId leerNodo(FriendlyByteBuf buf) {
        return new NodoId(buf.readUnsignedByte(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
