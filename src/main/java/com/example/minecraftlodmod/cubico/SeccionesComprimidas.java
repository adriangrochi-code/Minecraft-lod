package com.example.minecraftlodmod.cubico;

import com.example.minecraftlodmod.config.ConfigLod;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * Etapa 2 de los cubic chunks, "no cargar lo lejano en vertical" (sección 32
 * de la arquitectura): los datos de bloques de las secciones lejanas en
 * vertical de todos los jugadores se guardan comprimidos en RAM
 * ({@link AlmacenComprimido}) y se descomprimen solos al primer acceso.
 *
 * Medido en el mundo alto: lo que pesa de lo lejano en vertical es la roca de
 * abajo (secciones mixtas por las menas y las rocas de features, ~2 KB de
 * datos cada una), no el aire. Las columnas siguen cargadas enteras (todo
 * Minecraft lo asume); lo que se achica es lo que guardan.
 *
 * Un barrido en el hilo del servidor recorre los chunks cargados con un tope
 * de tiempo por tick. No toca secciones con ticks aleatorios (se volverían a
 * descomprimir enseguida) ni las que ya son uniformes.
 */
public final class SeccionesComprimidas {

    private SeccionesComprimidas() {
    }

    private static final long TOPE_NANOS_POR_TICK = 1_000_000L;

    /** Chunks cargados del servidor por dimensión, en orden de recorrido. */
    private static final Map<ResourceKey<Level>, Long2ObjectLinkedOpenHashMap<LevelChunk>> CARGADOS = new HashMap<>();
    private static final Map<ResourceKey<Level>, Iterator<LevelChunk>> CURSORES = new HashMap<>();
    /** Tick de carga de cada chunk: la luz de un chunk recién cargado puede estar en cola y escribirse directo. */
    private static final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap CARGADO_EN = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
    private static final int TICKS_ANTES_DE_LUZ = 200;

    @SubscribeEvent
    public static void alCargarChunk(ChunkEvent.Load evento) {
        if (evento.getLevel() instanceof ServerLevel nivel && evento.getChunk() instanceof LevelChunk chunk) {
            CARGADOS.computeIfAbsent(nivel.dimension(), k -> new Long2ObjectLinkedOpenHashMap<>())
                    .put(chunk.getPos().toLong(), chunk);
            CARGADO_EN.put(chunk.getPos().toLong(), nivel.getServer().getTickCount());
            CURSORES.remove(nivel.dimension()); // el iterador ya no vale
        }
    }

    @SubscribeEvent
    public static void alDescargarChunk(ChunkEvent.Unload evento) {
        if (evento.getLevel() instanceof ServerLevel nivel) {
            var mapa = CARGADOS.get(nivel.dimension());
            CARGADO_EN.remove(evento.getChunk().getPos().toLong());
            if (mapa != null && mapa.remove(evento.getChunk().getPos().toLong()) != null) {
                CURSORES.remove(nivel.dimension());
            }
        }
    }

    @SubscribeEvent
    public static void alParar(ServerStoppedEvent evento) {
        CARGADOS.clear();
        CURSORES.clear();
        CARGADO_EN.clear();
    }

    /** Cada tick, desde {@link GeneracionVertical}: barrido con tope de tiempo. */
    static void tick(MinecraftServer servidor, Map<ResourceKey<Level>, int[]> jugadores, int distanciaVista) {
        if (!ConfigLod.SPEC_SERVIDOR.isLoaded() || !ConfigLod.SERVIDOR.comprimirSeccionesLejanas.get()) {
            return;
        }
        int distancia = ConfigLod.SERVIDOR.distanciaCompresion.get();
        long fin = System.nanoTime() + TOPE_NANOS_POR_TICK;
        for (ServerLevel nivel : servidor.getAllLevels()) {
            var mapa = CARGADOS.get(nivel.dimension());
            if (mapa == null || mapa.isEmpty()) {
                continue;
            }
            int[] pos = jugadores.getOrDefault(nivel.dimension(), new int[0]);
            Iterator<LevelChunk> it = CURSORES.get(nivel.dimension());
            while (System.nanoTime() < fin) {
                if (it == null || !it.hasNext()) {
                    it = mapa.values().iterator();
                    if (!it.hasNext()) {
                        break;
                    }
                }
                revisar(it.next(), pos, distanciaVista, distancia);
            }
            CURSORES.put(nivel.dimension(), it);
        }
    }

    private static void revisar(LevelChunk chunk, int[] jugadores, int distanciaVista, int distancia) {
        int cx = chunk.getPos().x;
        int cz = chunk.getPos().z;
        LevelChunkSection[] secciones = chunk.getSections();
        LevelLightEngine luz = chunk.getLevel().getLightEngine();
        long cargado = CARGADO_EN.getOrDefault(chunk.getPos().toLong(), Long.MAX_VALUE);
        boolean luzEstable = chunk.getLevel().getServer().getTickCount() - cargado > TICKS_ANTES_DE_LUZ;
        for (int i = 0; i < secciones.length; i++) {
            LevelChunkSection s = secciones[i];
            int sy = chunk.getSectionYFromSectionIndex(i);
            if (cerca(jugadores, cx, sy, cz, distanciaVista + 2, distancia)) {
                continue;
            }
            // Luz: las capas visibles (las que se leen; el motor de luz copia antes de escribir).
            if (luzEstable) {
                comprimirLuz(luz.getLayerListener(LightLayer.SKY).getDataLayerData(SectionPos.of(cx, sy, cz)));
                comprimirLuz(luz.getLayerListener(LightLayer.BLOCK).getDataLayerData(SectionPos.of(cx, sy, cz)));
            }
            if (s.hasOnlyAir() || s.isRandomlyTicking()) {
                continue;
            }
            PalettedContainer<BlockState> estados = s.getStates();
            var datos = estados.data;
            if (datos.storage() instanceof AlmacenComprimido a) {
                if (!a.estaComprimido()) {
                    ESTADISTICAS.bytesComprimidos.add(a.comprimir());
                    ESTADISTICAS.recomprimidas.increment();
                }
            } else if (datos.storage() instanceof SimpleBitStorage simple && simple.getBits() > 0) {
                AlmacenComprimido a = new AlmacenComprimido(simple);
                estados.data = new PalettedContainer.Data<>(datos.configuration(), a, datos.palette());
                ESTADISTICAS.bytesOriginales.add(simple.getRaw().length * 8L);
                ESTADISTICAS.bytesComprimidos.add(a.comprimir());
                ESTADISTICAS.comprimidas.increment();
            }
        }
    }

    private static void comprimirLuz(DataLayer capa) {
        if (capa instanceof LuzComprimible c && !c.minecraftlodmod$estaComprimida()) {
            boolean nueva = !capa.isDefinitelyHomogenous();
            int bytes = c.minecraftlodmod$comprimir();
            if (bytes >= 0 && nueva) {
                ESTADISTICAS.luzComprimida.increment();
                ESTADISTICAS.bytesLuz.add(bytes);
            }
        }
    }

    /** Si algún jugador está a la vista horizontal y a menos de {@code distancia} secciones en vertical. */
    static boolean cerca(int[] jugadores, int cx, int sy, int cz, int alcanceHorizontal, int distancia) {
        for (int i = 0; i < jugadores.length; i += 3) {
            if (Math.abs((jugadores[i] >> 4) - cx) <= alcanceHorizontal
                    && Math.abs((jugadores[i + 2] >> 4) - cz) <= alcanceHorizontal
                    && Math.abs((jugadores[i + 1] >> 4) - sy) <= distancia) {
                return true;
            }
        }
        return false;
    }

    public static final Estadisticas ESTADISTICAS = new Estadisticas();

    public static final class Estadisticas {
        final LongAdder comprimidas = new LongAdder();
        final LongAdder recomprimidas = new LongAdder();
        final LongAdder descomprimidas = new LongAdder();
        final LongAdder bytesOriginales = new LongAdder();
        final LongAdder bytesComprimidos = new LongAdder();
        final LongAdder luzComprimida = new LongAdder();
        final LongAdder bytesLuz = new LongAdder();
        private final LongAdder luzDescomprimida = new LongAdder();

        public void luzDescomprimida() {
            luzDescomprimida.increment();
        }

        public String resumenYReiniciar() {
            long c = comprimidas.sumThenReset();
            long r = recomprimidas.sumThenReset();
            long d = descomprimidas.sumThenReset();
            long o = bytesOriginales.sumThenReset();
            long k = bytesComprimidos.sumThenReset();
            long lc = luzComprimida.sumThenReset();
            long lb = bytesLuz.sumThenReset();
            long ld = luzDescomprimida.sumThenReset();
            if (c == 0 && r == 0 && d == 0 && lc == 0 && ld == 0) {
                return null;
            }
            return String.format("%d secciones comprimidas (%d → %d KB de datos de bloques en las nuevas),"
                    + " %d recomprimidas, %d descomprimidas al usarlas; luz: %d capas comprimidas (%d → %d KB),"
                    + " %d descomprimidas", c, o >> 10, c == 0 ? 0 : (k >> 10), r, d, lc, lc * 2, lb >> 10, ld);
        }
    }
}
