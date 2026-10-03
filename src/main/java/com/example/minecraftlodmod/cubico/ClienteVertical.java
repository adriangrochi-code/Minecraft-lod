package com.example.minecraftlodmod.cubico;

import com.example.minecraftlodmod.config.ConfigLod;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * Lado cliente de la sincronización vertical ({@link SincroVertical}). Solo
 * hilo principal del cliente (los paquetes se atienden ahí).
 *
 * Además de aplicar las secciones que llegan y se van, guarda el rango de
 * cada columna: el render del LOD dibuja lo que queda afuera (el "LOD
 * vertical"), así las islas flotantes y las montañas altas fuera del rango se
 * siguen viendo en vez de desaparecer como con Vertigo.
 */
public final class ClienteVertical {

    private ClienteVertical() {
    }

    /** Columna → rango parcial ({@link RangoSecciones#empaquetado()}); las completas no están. */
    private static final Long2LongOpenHashMap RANGOS = new Long2LongOpenHashMap();

    static {
        RANGOS.defaultReturnValue(Long.MIN_VALUE);
    }
    /** Sube con cada cambio de rangos: el LOD rearma las celdas cercanas. */
    private static int version;

    /** Rango que tiene el cliente de esa columna, o null si la tiene entera (o no la tiene). */
    public static RangoSecciones rango(int chunkX, int chunkZ) {
        long v = RANGOS.getOrDefault(ChunkPos.asLong(chunkX, chunkZ), Long.MIN_VALUE);
        return v == Long.MIN_VALUE ? null : RangoSecciones.desempaquetar(v);
    }

    public static int version() {
        return version;
    }

    /** Columnas con rango parcial ahora (para la medición). */
    public static int columnasParciales() {
        return RANGOS.size();
    }

    // ------------------------------------------------------------------ paquetes

    static void alRecibirRango(PaquetesVerticales.Rango p) {
        ClientLevel nivel = Minecraft.getInstance().level;
        if (nivel == null) {
            return;
        }
        long clave = ChunkPos.asLong(p.chunkX(), p.chunkZ());
        if (p.min() <= nivel.getMinSection() && p.max() >= nivel.getMaxSection() - 1) {
            RANGOS.remove(clave);
        } else {
            RANGOS.put(clave, new RangoSecciones(p.min(), p.max()).empaquetado());
        }
        version++;
    }

    static void alRecibirCarga(PaquetesVerticales.CargaSeccion p) {
        ClientLevel nivel = Minecraft.getInstance().level;
        LevelChunk chunk = columna(nivel, p.chunkX(), p.chunkZ());
        if (chunk == null) {
            return;
        }
        int indice = chunk.getSectionIndexFromSectionY(p.seccionY());
        LevelChunkSection[] secciones = chunk.getSections();
        if (indice < 0 || indice >= secciones.length) {
            return;
        }
        LevelChunkSection nueva = new LevelChunkSection(nivel.registryAccess().registryOrThrow(Registries.BIOME));
        nueva.read(new FriendlyByteBuf(Unpooled.wrappedBuffer(p.seccion())));
        secciones[indice] = nueva;
        SectionPos pos = SectionPos.of(p.chunkX(), p.seccionY(), p.chunkZ());
        // Como al recibir luz de vanilla: los datos van a la cola del motor de luz del cliente.
        if (p.cielo().length == DataLayer.SIZE) {
            nivel.getLightEngine().queueSectionData(LightLayer.SKY, pos, new DataLayer(p.cielo()));
        }
        if (p.bloque().length == DataLayer.SIZE) {
            nivel.getLightEngine().queueSectionData(LightLayer.BLOCK, pos, new DataLayer(p.bloque()));
        }
        nivel.getLightEngine().updateSectionStatus(pos, nueva.hasOnlyAir());
        nivel.setSectionDirtyWithNeighbors(p.chunkX(), p.seccionY(), p.chunkZ());
    }

    static void alRecibirDescarga(PaquetesVerticales.DescargaSeccion p) {
        ClientLevel nivel = Minecraft.getInstance().level;
        LevelChunk chunk = columna(nivel, p.chunkX(), p.chunkZ());
        if (chunk == null) {
            return;
        }
        int indice = chunk.getSectionIndexFromSectionY(p.seccionY());
        LevelChunkSection[] secciones = chunk.getSections();
        if (indice < 0 || indice >= secciones.length) {
            return;
        }
        // Aire con los biomas que tenía (cielo y niebla siguen bien).
        secciones[indice] = new LevelChunkSection(new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY,
                Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES),
                secciones[indice].getBiomes());
        List<BlockPos> entidades = new ArrayList<>();
        for (BlockPos pos : chunk.getBlockEntities().keySet()) {
            if (pos.getY() >> 4 == p.seccionY()) {
                entidades.add(pos);
            }
        }
        entidades.forEach(chunk::removeBlockEntity);
        nivel.getLightEngine().updateSectionStatus(SectionPos.of(p.chunkX(), p.seccionY(), p.chunkZ()), true);
        nivel.setSectionDirtyWithNeighbors(p.chunkX(), p.seccionY(), p.chunkZ());
    }

    private static LevelChunk columna(ClientLevel nivel, int chunkX, int chunkZ) {
        if (nivel == null) {
            return null;
        }
        return nivel.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
    }

    // ------------------------------------------------------------------ preferencia y limpieza

    /** Le dice al servidor si se quiere la sincronización vertical (opción experimental del cliente). */
    public static void enviarPreferencia() {
        ClientPacketListener conexion = Minecraft.getInstance().getConnection();
        if (conexion == null || !conexion.hasChannel(PaquetesVerticales.Preferencia.TYPE)) {
            return; // servidor sin el mod (o sin esta versión del paquete): todo como vanilla
        }
        PacketDistributor.sendToServer(new PaquetesVerticales.Preferencia(
                ConfigLod.CLIENTE.sincroVertical.get(), ConfigLod.CLIENTE.distanciaVertical.get()));
    }

    @SubscribeEvent
    public static void alConectar(ClientPlayerNetworkEvent.LoggingIn evento) {
        RANGOS.clear();
        version++;
        enviarPreferencia();
    }

    @SubscribeEvent
    public static void alDescargarChunk(ChunkEvent.Unload evento) {
        if (evento.getLevel().isClientSide() && RANGOS.remove(evento.getChunk().getPos().toLong()) != Long.MIN_VALUE) {
            version++;
        }
    }

    @SubscribeEvent
    public static void alDescargarNivel(LevelEvent.Unload evento) {
        if (evento.getLevel().isClientSide()) {
            RANGOS.clear();
            version++;
        }
    }
}
