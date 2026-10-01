package com.example.minecraftlodmod.cubico;

import com.example.minecraftlodmod.MinecraftLodMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Paquetes de la sincronización vertical ({@link SincroVertical}). Diseño
 * inspirado en Vertigo (Builderb0y, MIT, https://github.com/Builderb0y/Vertigo):
 * cargar y descargar secciones sueltas en vez de reenviar columnas enteras.
 */
public final class PaquetesVerticales {

    private PaquetesVerticales() {
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> tipo(String nombre) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(MinecraftLodMod.MOD_ID, nombre));
    }

    /** Cliente → servidor: si quiere la sincronización vertical y a qué distancia (en secciones). */
    public record Preferencia(boolean activa, int distancia) implements CustomPacketPayload {
        public static final Type<Preferencia> TYPE = tipo("vertical_preferencia");
        public static final StreamCodec<FriendlyByteBuf, Preferencia> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeBoolean(p.activa);
                    buf.writeVarInt(p.distancia);
                },
                buf -> new Preferencia(buf.readBoolean(), buf.readVarInt()));

        @Override
        public Type<Preferencia> type() {
            return TYPE;
        }
    }

    /** Servidor → cliente: qué secciones de la columna tiene con sus bloques reales. */
    public record Rango(int chunkX, int chunkZ, int min, int max) implements CustomPacketPayload {
        public static final Type<Rango> TYPE = tipo("vertical_rango");
        public static final StreamCodec<FriendlyByteBuf, Rango> STREAM_CODEC = StreamCodec.of(
                (buf, r) -> {
                    buf.writeVarInt(r.chunkX);
                    buf.writeVarInt(r.chunkZ);
                    buf.writeVarInt(r.min);
                    buf.writeVarInt(r.max);
                },
                buf -> new Rango(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));

        @Override
        public Type<Rango> type() {
            return TYPE;
        }
    }

    /**
     * Servidor → cliente: una sección con sus bloques ({@code LevelChunkSection#write})
     * y su luz (2048 bytes de cielo y de bloque; vacío = sin datos).
     */
    public record CargaSeccion(int chunkX, int seccionY, int chunkZ, byte[] seccion, byte[] cielo, byte[] bloque)
            implements CustomPacketPayload {
        public static final Type<CargaSeccion> TYPE = tipo("vertical_carga");
        /** Una sección serializada con paleta completa ronda 8-10 KB; tope generoso. */
        static final int MAX_BYTES_SECCION = 64 * 1024;
        public static final StreamCodec<FriendlyByteBuf, CargaSeccion> STREAM_CODEC = StreamCodec.of(
                (buf, c) -> {
                    buf.writeVarInt(c.chunkX);
                    buf.writeVarInt(c.seccionY);
                    buf.writeVarInt(c.chunkZ);
                    buf.writeByteArray(c.seccion);
                    buf.writeByteArray(c.cielo);
                    buf.writeByteArray(c.bloque);
                },
                buf -> new CargaSeccion(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                        buf.readByteArray(MAX_BYTES_SECCION), buf.readByteArray(2048), buf.readByteArray(2048)));

        @Override
        public Type<CargaSeccion> type() {
            return TYPE;
        }
    }

    /** Servidor → cliente: la sección pasa a ser aire en el cliente (memoria y mallas libres). */
    public record DescargaSeccion(int chunkX, int seccionY, int chunkZ) implements CustomPacketPayload {
        public static final Type<DescargaSeccion> TYPE = tipo("vertical_descarga");
        public static final StreamCodec<FriendlyByteBuf, DescargaSeccion> STREAM_CODEC = StreamCodec.of(
                (buf, d) -> {
                    buf.writeVarInt(d.chunkX);
                    buf.writeVarInt(d.seccionY);
                    buf.writeVarInt(d.chunkZ);
                },
                buf -> new DescargaSeccion(buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));

        @Override
        public Type<DescargaSeccion> type() {
            return TYPE;
        }
    }
}
