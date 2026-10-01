package com.example.minecraftlodmod.cubico;

import com.example.minecraftlodmod.config.ConfigLod;
import com.mojang.logging.LogUtils;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.neoforged.neoforge.common.Tags;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Etapa 2 de los cubic chunks, parte 2: completar las secciones que
 * {@link GeneracionVertical} dejó de relleno cuando un jugador se acerca en
 * altura (sección 32 de la arquitectura).
 *
 * Por cada columna, la banda que falta se genera con ruido (más acuíferos y
 * reglas de superficie) en un {@code ProtoChunk} aparte, en un hilo de fondo,
 * y en el hilo del servidor se mezcla con el chunk real:
 * - debajo (relleno de piedra): el real ya tiene menas, cuevas de carvers,
 *   lecho de roca y estructuras puestas sobre el relleno. Se toman del aparte
 *   el aire y los fluidos (cuevas de ruido, acuíferos), y la roca donde el real
 *   tiene la piedra o pizarra del relleno (vetas); las menas y rocas de
 *   features quedan salvo que el aparte diga hueco (no quedan menas flotando);
 *   lo que no es roca (aire de carvers, estructuras, lo que puso un jugador)
 *   queda como está.
 * - arriba (aire): se toma lo del aparte donde el real es aire (islas
 *   flotantes, sin la vegetación de las features).
 * Los cambios pasan por {@link LevelChunk#setBlockState} (luz, mapas de altura,
 * fluidos que se ponen a correr) y después el chunk se vuelve a mandar.
 */
public final class CompletadoVertical {

    private static final Logger LOG = LogUtils.getLogger();

    private CompletadoVertical() {
    }

    /** Pedidos en curso o en cola, por dimensión y columna. */
    private record Clave(ResourceKey<Level> dimension, long columna) {
    }

    private record Pedido(Clave clave, RangoSecciones ventana, RangoSecciones objetivo) {
    }

    /** Resultado del hilo de fondo, para mezclar en el hilo del servidor. */
    private record Banda(Pedido pedido, ProtoChunk aparte, RangoSecciones rango, boolean abajo, long nanos) {
    }

    private static final Set<Clave> EN_CURSO = Collections.synchronizedSet(new HashSet<>());
    private static final ArrayDeque<Pedido> COLA = new ArrayDeque<>();
    private static final ConcurrentLinkedQueue<Banda> LISTAS = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger TRABAJANDO = new AtomicInteger();
    /** Chunks aparte que se están generando: {@link GeneracionVertical} no los trata como chunks nuevos. */
    private static final Set<Object> APARTES = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private static final int MAX_TRABAJANDO = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 3));
    private static final int MEZCLAS_POR_TICK = 4;
    private static final int MAX_COLA = 4096;

    static boolean esAparte(Object chunk) {
        return APARTES.contains(chunk);
    }

    // ------------------------------------------------------------------ cuándo

    /** Cada 10 ticks: columnas cargadas con franja a las que un jugador se acerca en altura. */
    static void revisar(ServerLevel nivel, int[] jugadores, int distanciaVista) {
        int d = ConfigLod.SERVIDOR.distanciaGeneracionJugador.get();
        for (int i = 0; i < jugadores.length; i += 3) {
            int cx = jugadores[i] >> 4;
            int sy = jugadores[i + 1] >> 4;
            int cz = jugadores[i + 2] >> 4;
            // Del centro hacia afuera: lo más cercano se completa primero.
            for (int r = 0; r <= distanciaVista; r++) {
                for (int x = cx - r; x <= cx + r; x++) {
                    for (int z = cz - r; z <= cz + r; z++) {
                        if (Math.max(Math.abs(x - cx), Math.abs(z - cz)) == r) {
                            pedirSiHaceFalta(nivel, x, z, sy, d);
                        }
                    }
                }
            }
        }
    }

    /** Pide completar la columna para un jugador en la sección {@code seccionJugador}. */
    static boolean pedirSiHaceFalta(ServerLevel nivel, int x, int z, int seccionJugador, int distancia) {
        LevelChunk chunk = nivel.getChunkSource().getChunkNow(x, z);
        if (chunk == null) {
            return false;
        }
        RangoSecciones v = GeneracionVertical.ventana(chunk);
        if (v == null) {
            return false;
        }
        int minMundo = chunk.getMinSection();
        int maxMundo = chunk.getMaxSection() - 1;
        int min = v.min();
        int max = v.max();
        if (seccionJugador - distancia < v.min()) {
            min = Math.max(minMundo, seccionJugador - distancia - 2); // dos secciones de adelanto
        }
        if (seccionJugador + distancia > v.max()) {
            max = Math.min(maxMundo, seccionJugador + distancia + 2);
        }
        if (min == v.min() && max == v.max()) {
            return false;
        }
        Clave clave = new Clave(nivel.dimension(), chunk.getPos().toLong());
        if (COLA.size() >= MAX_COLA || !EN_CURSO.add(clave)) {
            return false;
        }
        COLA.add(new Pedido(clave, v, new RangoSecciones(min, max)));
        return true;
    }

    /** En cada tick del servidor: lanza trabajos y mezcla los que terminaron. */
    static void tick(net.minecraft.server.MinecraftServer servidor) {
        while (TRABAJANDO.get() < MAX_TRABAJANDO && !COLA.isEmpty()) {
            Pedido p = COLA.poll();
            ServerLevel nivel = servidor.getLevel(p.clave.dimension);
            LevelChunk chunk = nivel == null ? null : nivel.getChunkSource().getChunkNow(
                    ChunkPos.getX(p.clave.columna), ChunkPos.getZ(p.clave.columna));
            if (chunk == null || !(nivel.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator gen)) {
                EN_CURSO.remove(p.clave);
                continue;
            }
            TRABAJANDO.incrementAndGet();
            RandomState aleatorio = nivel.getChunkSource().randomState();
            CompletableFuture.runAsync(() -> generar(nivel, gen, aleatorio, p), Util.backgroundExecutor())
                    .whenComplete((r, error) -> {
                        TRABAJANDO.decrementAndGet();
                        if (error != null) {
                            LOG.warn("[LOD] No se pudo completar la columna {}: {}",
                                    new ChunkPos(p.clave.columna), error.toString());
                            EN_CURSO.remove(p.clave);
                        }
                    });
        }
        // Las dos bandas de un pedido (arriba y abajo) llegan juntas; se mezclan de a pocas por tick.
        for (int i = 0; i < MEZCLAS_POR_TICK && !LISTAS.isEmpty(); i++) {
            Banda b = LISTAS.poll();
            try {
                mezclar(servidor, b);
            } catch (RuntimeException e) {
                LOG.warn("[LOD] Falló la mezcla de la columna {}", new ChunkPos(b.pedido.clave.columna), e);
                EN_CURSO.remove(b.pedido.clave);
            }
        }
    }

    // ------------------------------------------------------------------ hilo de fondo

    private static void generar(ServerLevel nivel, NoiseBasedChunkGenerator gen, RandomState aleatorio, Pedido p) {
        long t0 = System.nanoTime();
        ChunkPos pos = new ChunkPos(p.clave.columna);
        RangoSecciones v = p.ventana;
        RangoSecciones o = p.objetivo;
        boolean hayAbajo = o.min() < v.min();
        boolean hayArriba = o.max() > v.max();
        // Las dos bandas en un solo pedido: la de abajo primero, la de arriba avisa que terminó el pedido.
        Banda abajo = hayAbajo ? banda(nivel, gen, aleatorio, pos, p, new RangoSecciones(o.min(), v.min() - 1), true) : null;
        Banda arriba = hayArriba ? banda(nivel, gen, aleatorio, pos, p, new RangoSecciones(v.max() + 1, o.max()), false) : null;
        long nanos = System.nanoTime() - t0;
        ESTADISTICAS.generado.add(nanos);
        if (abajo != null) {
            LISTAS.add(new Banda(p, abajo.aparte, abajo.rango, true, arriba == null ? nanos : -1));
        }
        if (arriba != null) {
            LISTAS.add(new Banda(p, arriba.aparte, arriba.rango, false, nanos));
        }
    }

    private static Banda banda(ServerLevel nivel, NoiseBasedChunkGenerator gen, RandomState aleatorio, ChunkPos pos,
                               Pedido p, RangoSecciones rango, boolean abajo) {
        Registry<Biome> biomas = nivel.registryAccess().registryOrThrow(Registries.BIOME);
        ProtoChunk aparte = new ProtoChunk(pos, UpgradeData.EMPTY, nivel, biomas, null);
        APARTES.add(aparte);
        try {
            aparte.setData(GeneracionVertical.VENTANA.get(), rango.empaquetado());
            gen.createBiomes(aleatorio, Blender.empty(), nivel.structureManager(), aparte).join();
            aparte.setPersistedStatus(ChunkStatus.BIOMES); // si no, getNoiseBiome se niega
            gen.fillFromNoise(Blender.empty(), aleatorio, nivel.structureManager(), aparte).join();
            NoiseGeneratorSettings ajustes = gen.generatorSettings().value();
            if (abajo) {
                // Tres secciones de relleno encima de la banda: las reglas de superficie la tratan como
                // subsuelo (pizarra profunda sí; pasto o arena no).
                BlockState relleno = ajustes.defaultBlock();
                LevelChunkSection[] secciones = aparte.getSections();
                for (int sy = rango.max() + 1; sy <= Math.min(rango.max() + 3, aparte.getMaxSection() - 1); sy++) {
                    int i = aparte.getSectionIndexFromSectionY(sy);
                    secciones[i] = new LevelChunkSection(new PalettedContainer<>(net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY,
                            relleno, PalettedContainer.Strategy.SECTION_STATES), secciones[i].getBiomes());
                }
            }
            Heightmap.primeHeightmaps(aparte, EnumSet.of(Heightmap.Types.WORLD_SURFACE_WG, Heightmap.Types.OCEAN_FLOOR_WG));
            // Biomas fuera del chunk aparte (mezcla de biomas en los bordes): directo de la fuente del generador.
            BiomeManager.NoiseBiomeSource fuente = (qx, qy, qz) -> {
                if ((qx >> 2) == pos.x && (qz >> 2) == pos.z) {
                    return aparte.getNoiseBiome(qx, qy, qz);
                }
                return gen.getBiomeSource().getNoiseBiome(qx, qy, qz, aleatorio.sampler());
            };
            gen.buildSurface(aparte, new WorldGenerationContext(gen, nivel), aleatorio, nivel.structureManager(),
                    new BiomeManager(fuente, BiomeManager.obfuscateSeed(nivel.getSeed())), biomas, Blender.empty());
            return new Banda(p, aparte, rango, abajo, 0);
        } finally {
            APARTES.remove(aparte);
        }
    }

    // ------------------------------------------------------------------ hilo del servidor

    private static void mezclar(net.minecraft.server.MinecraftServer servidor, Banda b) {
        long t0 = System.nanoTime();
        Pedido p = b.pedido;
        boolean ultima = b.nanos >= 0;
        ServerLevel nivel = servidor.getLevel(p.clave.dimension);
        LevelChunk chunk = nivel == null ? null : nivel.getChunkSource().getChunkNow(
                ChunkPos.getX(p.clave.columna), ChunkPos.getZ(p.clave.columna));
        RangoSecciones actual = chunk == null ? null : GeneracionVertical.ventana(chunk);
        if (actual == null || !actual.equals(p.ventana)) {
            if (ultima) {
                EN_CURSO.remove(p.clave); // se descargó o cambió mientras tanto: se vuelve a pedir si hace falta
            }
            return;
        }
        BlockState relleno = nivel.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator gen
                ? gen.generatorSettings().value().defaultBlock() : Blocks.STONE.defaultBlockState();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int cambiados = 0;
        int x0 = chunk.getPos().getMinBlockX();
        int z0 = chunk.getPos().getMinBlockZ();
        for (int sy = b.rango.min(); sy <= b.rango.max(); sy++) {
            LevelChunkSection real = chunk.getSection(chunk.getSectionIndexFromSectionY(sy));
            LevelChunkSection nueva = b.aparte.getSection(b.aparte.getSectionIndexFromSectionY(sy));
            if (!b.abajo && nueva.hasOnlyAir()) {
                continue;
            }
            int y0 = SectionPos.sectionToBlockCoord(sy);
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState r = real.getBlockState(x, y, z);
                        BlockState n = nueva.getBlockState(x, y, z);
                        if (r == n || !(b.abajo ? tomarAbajo(r, n, relleno) : r.isAir() && !n.isAir())) {
                            continue;
                        }
                        chunk.setBlockState(pos.set(x0 + x, y0 + y, z0 + z), n, false);
                        cambiados++;
                    }
                }
            }
        }
        ESTADISTICAS.mezcla(cambiados, System.nanoTime() - t0);
        if (!ultima) {
            return;
        }
        // Franja nueva del chunk; sin dato si ya está completo.
        RangoSecciones o = p.objetivo;
        if (o.min() <= chunk.getMinSection() && o.max() >= chunk.getMaxSection() - 1) {
            chunk.removeData(GeneracionVertical.VENTANA.get());
        } else {
            chunk.setData(GeneracionVertical.VENTANA.get(), o.empaquetado());
        }
        chunk.setUnsaved(true);
        ESTADISTICAS.columna();
        // Cuando la luz terminó con estos cambios, se le vuelve a mandar el chunk a quien lo ve.
        nivel.getChunkSource().getLightEngine().lightChunk(chunk, true).thenRunAsync(() -> {
            EN_CURSO.remove(p.clave);
            LevelChunk ahora = nivel.getChunkSource().getChunkNow(chunk.getPos().x, chunk.getPos().z);
            if (ahora == chunk) {
                for (ServerPlayer j : nivel.getChunkSource().chunkMap.getPlayers(chunk.getPos(), false)) {
                    j.connection.chunkSender.markChunkPendingToSend(chunk);
                }
            }
        }, servidor);
    }

    /** Debajo de la franja: qué bloque del aparte reemplaza al del relleno real. */
    static boolean tomarAbajo(BlockState real, BlockState nuevo, BlockState relleno) {
        boolean roca = real == relleno || real.is(BlockTags.BASE_STONE_OVERWORLD) || real.is(Tags.Blocks.ORES);
        if (!roca) {
            return false; // aire de carvers, estructuras, lecho de roca, lo que puso un jugador
        }
        if (nuevo.isAir() || !nuevo.getFluidState().isEmpty()) {
            return true; // cuevas de ruido y acuíferos
        }
        return real == relleno || real.is(Blocks.DEEPSLATE); // vetas y la roca del ruido; menas y features quedan
    }

    /** Comando de prueba: completa alrededor de una columna como si hubiera un jugador en esa sección. */
    static int pedirAlrededor(ServerLevel nivel, int cx, int cz, int radio, int seccionY) {
        int d = ConfigLod.SERVIDOR.distanciaGeneracionJugador.get();
        int n = 0;
        for (int x = cx - radio; x <= cx + radio; x++) {
            for (int z = cz - radio; z <= cz + radio; z++) {
                if (pedirSiHaceFalta(nivel, x, z, seccionY, d)) {
                    n++;
                }
            }
        }
        return n;
    }

    static int pendientes() {
        return COLA.size() + TRABAJANDO.get() + LISTAS.size();
    }

    // ------------------------------------------------------------------ medición

    public static final Estadisticas ESTADISTICAS = new Estadisticas();

    public static final class Estadisticas {
        private final LongAdder columnas = new LongAdder();
        final LongAdder generado = new LongAdder();
        private final LongAdder nanosMezcla = new LongAdder();
        private final LongAdder bloques = new LongAdder();

        void mezcla(int cambiados, long nanos) {
            bloques.add(cambiados);
            nanosMezcla.add(nanos);
        }

        void columna() {
            columnas.increment();
        }

        public String resumenYReiniciar() {
            long c = columnas.sumThenReset();
            long g = generado.sumThenReset();
            long m = nanosMezcla.sumThenReset();
            long b = bloques.sumThenReset();
            if (c == 0) {
                return null;
            }
            return String.format("%d columnas completadas, %.1f ms de generación y %.1f ms de mezcla (hilo del"
                    + " servidor) por columna, %d bloques cambiados, %d pendientes", c, g / 1e6 / c, m / 1e6 / c, b, pendientes());
        }
    }
}
