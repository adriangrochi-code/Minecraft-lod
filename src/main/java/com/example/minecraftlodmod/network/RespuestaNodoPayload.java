package com.example.minecraftlodmod.network;

import com.example.minecraftlodmod.MinecraftLodMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Servidor → cliente: un nodo, con los bytes tal cual los produce
 * {@link com.example.minecraftlodmod.storage.OctreeNodeCodec#serializar}
 * (mismo formato que en disco, sección 5). Del header de región solo viaja
 * {@code dimensionId} (1 byte): el cliente ya sabe qué pidió, pero así
 * descarta respuestas que llegan después de cambiar de dimensión.
 *
 * Un paquete por nodo: el peor caso (nivel 0 sin corridas largas) ronda
 * decenas de KB, muy por debajo del límite de 1 MB servidor → cliente, sin
 * necesitar partir paquetes.
 */
public record RespuestaNodoPayload(byte dimensionId, NodoId nodo, Estado estado, byte[] datos)
        implements CustomPacketPayload {

    /**
     * EXISTE: {@code datos} trae el nodo.
     * VACIO: el chunk está generado y la sección no tiene nada visible — no volver a pedir.
     * NO_GENERADO: el servidor todavía no tiene ese chunk — reintentar más tarde.
     */
    public enum Estado {
        EXISTE, VACIO, NO_GENERADO
    }

    public static final Type<RespuestaNodoPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MinecraftLodMod.MOD_ID, "respuesta_nodo"));

    public static final StreamCodec<FriendlyByteBuf, RespuestaNodoPayload> STREAM_CODEC =
            StreamCodec.of(RespuestaNodoPayload::escribir, RespuestaNodoPayload::leer);

    /** Tope al leer, para no reservar un array arbitrario desde la red. */
    static final int MAX_BYTES_NODO = 256 * 1024;

    public RespuestaNodoPayload {
        if (estado != Estado.EXISTE) {
            datos = new byte[0];
        }
    }

    private static void escribir(FriendlyByteBuf buf, RespuestaNodoPayload r) {
        buf.writeByte(r.dimensionId);
        PedidoNodosPayload.escribirNodo(buf, r.nodo);
        buf.writeEnum(r.estado);
        buf.writeByteArray(r.datos);
    }

    private static RespuestaNodoPayload leer(FriendlyByteBuf buf) {
        byte dimensionId = buf.readByte();
        NodoId nodo = PedidoNodosPayload.leerNodo(buf);
        Estado estado = buf.readEnum(Estado.class);
        byte[] datos = buf.readByteArray(MAX_BYTES_NODO);
        return new RespuestaNodoPayload(dimensionId, nodo, estado, datos);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
