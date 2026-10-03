package com.example.minecraftlodmod.network;

import com.example.minecraftlodmod.MinecraftLodMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Servidor → cliente: una parte de una rebanada ({@link RebanadaId}): pares
 * clave de nodo → bytes tal cual están en el store del servidor (ya
 * comprimidos con {@code CompresionNodos}: no se descomprime para mandar ni
 * se vuelve a comprimir para guardar). Una rebanada grande (nivel 0 de una
 * región entera) va en varias partes; la última lleva {@code ultima}, así el
 * cliente sabe que ya tiene todo lo que había (lo que falte, no existe).
 *
 * @param dimensionId para descartar lo que llega después de cambiar de dimensión
 * @param sinCambios  la huella que mandó el cliente es la actual: no viajan datos
 * @param huella      la de la rebanada en el servidor (en la última parte)
 */
public record RebanadaPayload(byte dimensionId, RebanadaId rebanada, boolean ultima, boolean sinCambios, long huella,
                              long[] claves, byte[][] datos)
        implements CustomPacketPayload {

    /** Bytes de datos por parte (el límite de un paquete servidor → cliente es 1 MiB). */
    public static final int BYTES_POR_PARTE = 256 * 1024;
    /** Topes al leer, para no reservar arreglos arbitrarios desde la red. */
    static final int MAX_ENTRADAS = 1 << 16;
    static final int MAX_BYTES_ENTRADA = 256 * 1024;

    public static final Type<RebanadaPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MinecraftLodMod.MOD_ID, "rebanada"));

    public static final StreamCodec<FriendlyByteBuf, RebanadaPayload> STREAM_CODEC =
            StreamCodec.of(RebanadaPayload::escribir, RebanadaPayload::leer);

    public RebanadaPayload {
        if (claves.length != datos.length) {
            throw new IllegalArgumentException("Claves y datos de distinto largo");
        }
    }

    private static void escribir(FriendlyByteBuf buf, RebanadaPayload p) {
        buf.writeByte(p.dimensionId);
        buf.writeVarInt(p.rebanada.regionX());
        buf.writeVarInt(p.rebanada.regionZ());
        buf.writeByte(p.rebanada.codigo());
        buf.writeBoolean(p.ultima);
        buf.writeBoolean(p.sinCambios);
        buf.writeLong(p.huella);
        buf.writeVarInt(p.claves.length);
        for (int i = 0; i < p.claves.length; i++) {
            buf.writeVarLong(p.claves[i]);
            buf.writeByteArray(p.datos[i]);
        }
    }

    private static RebanadaPayload leer(FriendlyByteBuf buf) {
        byte dimension = buf.readByte();
        RebanadaId rebanada = new RebanadaId(buf.readVarInt(), buf.readVarInt(), buf.readUnsignedByte() & 0xF);
        boolean ultima = buf.readBoolean();
        boolean sinCambios = buf.readBoolean();
        long huella = buf.readLong();
        int n = buf.readVarInt();
        if (n < 0 || n > MAX_ENTRADAS) {
            throw new IllegalArgumentException("Rebanada con cantidad inválida: " + n);
        }
        long[] claves = new long[n];
        byte[][] datos = new byte[n][];
        for (int i = 0; i < n; i++) {
            claves[i] = buf.readVarLong();
            datos[i] = buf.readByteArray(MAX_BYTES_ENTRADA);
        }
        return new RebanadaPayload(dimension, rebanada, ultima, sinCambios, huella, claves, datos);
    }

    /**
     * Parte una rebanada en paquetes de hasta {@link #BYTES_POR_PARTE} bytes de
     * datos (al menos una entrada por parte); siempre al menos una parte, la
     * última con {@code ultima}, aunque la rebanada esté vacía.
     */
    public static List<RebanadaPayload> partir(byte dimensionId, RebanadaId rebanada, long huella, long[] claves,
                                               byte[][] datos) {
        List<RebanadaPayload> partes = new ArrayList<>();
        int desde = 0;
        while (true) {
            int hasta = desde;
            long bytes = 0;
            while (hasta < claves.length && (hasta == desde || bytes + datos[hasta].length <= BYTES_POR_PARTE)) {
                bytes += datos[hasta].length;
                hasta++;
            }
            boolean ultima = hasta >= claves.length;
            partes.add(new RebanadaPayload(dimensionId, rebanada, ultima, false, huella,
                    java.util.Arrays.copyOfRange(claves, desde, hasta), java.util.Arrays.copyOfRange(datos, desde, hasta)));
            if (ultima) {
                return partes;
            }
            desde = hasta;
        }
    }

    /** Respuesta de una sola parte, sin datos: lo que tiene el cliente sigue vigente. */
    public static RebanadaPayload sinCambios(byte dimensionId, RebanadaId rebanada, long huella) {
        return new RebanadaPayload(dimensionId, rebanada, true, true, huella, new long[0], new byte[0][]);
    }

    /** Bytes aproximados en el cable (para el ritmo por jugador). */
    public int bytesAproximados() {
        int total = 16;
        for (byte[] d : datos) {
            total += d.length + 12;
        }
        return total;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
