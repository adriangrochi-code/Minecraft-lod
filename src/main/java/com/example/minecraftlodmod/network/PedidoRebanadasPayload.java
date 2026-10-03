package com.example.minecraftlodmod.network;

import com.example.minecraftlodmod.MinecraftLodMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Cliente → servidor: rebanadas del LOD ({@link RebanadaId}) que el render
 * del cliente buscó y no tiene, en la dimensión del jugador.
 */
public record PedidoRebanadasPayload(List<RebanadaId> rebanadas, long[] huellas) implements CustomPacketPayload {

    /** Tope por paquete (se valida al leer: un cliente modificado no fuerza una lista enorme). */
    public static final int MAXIMO = 64;

    public static final Type<PedidoRebanadasPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MinecraftLodMod.MOD_ID, "pedido_rebanadas"));

    public static final StreamCodec<FriendlyByteBuf, PedidoRebanadasPayload> STREAM_CODEC =
            StreamCodec.of(PedidoRebanadasPayload::escribir, PedidoRebanadasPayload::leer);

    /**
     * @param huellas la que el cliente ya tiene de cada rebanada (0 = ninguna): si
     *                coincide con la del servidor, la respuesta es "sin cambios"
     */
    public PedidoRebanadasPayload {
        rebanadas = List.copyOf(rebanadas);
        if (huellas.length != rebanadas.size()) {
            throw new IllegalArgumentException("Una huella por rebanada");
        }
    }

    private static void escribir(FriendlyByteBuf buf, PedidoRebanadasPayload p) {
        buf.writeVarInt(p.rebanadas.size());
        for (int i = 0; i < p.rebanadas.size(); i++) {
            RebanadaId r = p.rebanadas.get(i);
            buf.writeVarInt(r.regionX());
            buf.writeVarInt(r.regionZ());
            buf.writeByte(r.codigo());
            buf.writeLong(p.huellas[i]);
        }
    }

    private static PedidoRebanadasPayload leer(FriendlyByteBuf buf) {
        int cantidad = buf.readVarInt();
        if (cantidad < 0 || cantidad > MAXIMO) {
            throw new IllegalArgumentException("Pedido de rebanadas con tamaño inválido: " + cantidad);
        }
        List<RebanadaId> lista = new ArrayList<>(cantidad);
        long[] huellas = new long[cantidad];
        for (int i = 0; i < cantidad; i++) {
            lista.add(new RebanadaId(buf.readVarInt(), buf.readVarInt(), buf.readUnsignedByte() & 0xF));
            huellas[i] = buf.readLong();
        }
        return new PedidoRebanadasPayload(lista, huellas);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
