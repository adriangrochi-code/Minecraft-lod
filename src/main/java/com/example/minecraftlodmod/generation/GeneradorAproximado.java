package com.example.minecraftlodmod.generation;

import com.example.minecraftlodmod.core.OctreeNode;
import com.example.minecraftlodmod.core.SuperVoxel;
import com.example.minecraftlodmod.storage.OctreeNodeCodec;
import com.example.minecraftlodmod.storage.RegionFileStore;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Generación aproximada del horizonte (sección 25 punto 7): LOD para chunks
 * que nunca se generaron, sin generarlos. En vez de correr la generación
 * vanilla completa (cientos de ms por chunk y chunks guardados en el mundo),
 * se evalúa la función de densidad final del generador del mundo
 * ({@code RandomState.router().finalDensity()}, misma semilla y mismos
 * biomas) en 2×2 columnas por chunk para hallar la altura del terreno, y la
 * superficie se elige por bioma. Del resultado sale el nivel 3 y 4
 * ({@link TerrenoAproximado}), y de ahí los niveles grandes del horizonte.
 *
 * No hay estructuras, cuevas visibles ni árboles individuales: los bosques
 * se dibujan como una copa continua. Es exactamente la calidad que hace falta
 * a varios km, y cuando el chunk se genera de verdad (explorado o
 * pregenerado), lo real lo reemplaza.
 *
 * Recorre los chunks sin datos en espiral desde el jugador, primero donde
 * mira ({@link PrioridadVista}); el cálculo corre en el pool de generación,
 * nunca en el hilo del servidor. Solo dimensiones con generador por ruido y
 * sin techo (Overworld, End, y las de mods que usen el mismo sistema).
 */
public final class GeneradorAproximado {

    private static final Logger LOG = LogUtils.getLogger();

    static final int VENTANA = 1024;
    static final int REVISIONES_POR_TICK = 8192;
    static final int RECENTRAR_CHUNKS = 64;
    /**
     * Chunks por tarea del pool. Con una tarea por chunk, el ritmo quedaba
     * atado a los ticks (tareas en vuelo × 20 por segundo) y no al costo real.
     */
    static final int LOTE = 16;
    static final long MSPT_MAXIMO_NANOS = 45_000_000L;
    static final long PERIODO_LOG_NANOS = 30_000_000_000L;
    /**
     * Más lejos que esto (bloques) se muestrea 1 columna por chunk en vez de
     * 2×2: ahí el render usa vóxeles de 16 bloques o más (nivel 4+ con el
     * umbral del preset Medio) y el detalle extra no se vería.
     */
    static final double DISTANCIA_UNA_COLUMNA = 1536;
    /** Con pista (altura de la columna vecina), la búsqueda arranca esto por encima. */
    static final int MARGEN_PISTA = 32;
    private static final byte[] MARCA = new byte[0];

    /** Lo que se necesita del mundo, capturado en el hilo del servidor; todo es inmutable y seguro entre hilos. */
    private record Contexto(ServerLevel nivel, byte dimension, DensityFunction densidad, BiomeSource biomas,
                            Climate.Sampler clima, int nivelMar, int minY, int maxY, int minSeccion, int maxSeccion) {
    }

    private final GeneradorLocal generador;
    private final int enVueloMaximo = Math.max(2, Runtime.getRuntime().availableProcessors());

    private Contexto contexto;
    private EspiralChunks espiral;
    private int centroX, centroZ;
    private final List<Long> candidatos = new ArrayList<>();
    private final Set<Long> enVuelo = ConcurrentHashMap.newKeySet();
    private boolean espiralAgotada, avisoCompleto;
    private final AtomicLong hechos = new AtomicLong();
    private final AtomicLong nanosCalculo = new AtomicLong();
    /** Tareas mandadas al pool y todavía no terminadas (se descuentan con {@link #terminadas} en el tick). */
    private int tareasEnVuelo;
    private final java.util.concurrent.atomic.AtomicInteger terminadas = new java.util.concurrent.atomic.AtomicInteger();
    private long ultimoLogNanos = System.nanoTime();

    GeneradorAproximado(GeneradorLocal generador) {
        this.generador = generador;
    }

    void tick(MinecraftServer servidor, boolean activo, int radio) {
        RegionFileStore store = generador.store();
        GenerationTaskScheduler scheduler = generador.scheduler();
        ServerPlayer jugador = servidor.getPlayerList().getPlayers().isEmpty() ? null
                : servidor.getPlayerList().getPlayers().get(0);
        if (!activo || store == null || scheduler == null || jugador == null) {
            if (!activo) {
                espiral = null;
                candidatos.clear();
            }
            return;
        }
        ServerLevel nivel = jugador.serverLevel();
        if (contexto == null || contexto.nivel() != nivel) {
            contexto = crearContexto(nivel);
            espiral = null;
        }
        if (contexto == null) {
            return; // dimensión sin generador por ruido, o con techo (Nether)
        }
        int jx = jugador.chunkPosition().x, jz = jugador.chunkPosition().z;
        if (espiral == null || espiral.radio() != radio
                || Math.max(Math.abs(jx - centroX), Math.abs(jz - centroZ)) > RECENTRAR_CHUNKS) {
            centroX = jx;
            centroZ = jz;
            espiral = new EspiralChunks(radio);
            candidatos.clear();
            espiralAgotada = false;
            avisoCompleto = false;
        }
        registrarAvance();
        tareasEnVuelo = Math.max(0, tareasEnVuelo - terminadas.getAndSet(0));
        if (servidor.getAverageTickTimeNanos() > MSPT_MAXIMO_NANOS) {
            return;
        }
        Contexto ctx = contexto;
        int revisados = 0;
        while (candidatos.size() < VENTANA && revisados < REVISIONES_POR_TICK && !espiralAgotada) {
            if (!espiral.siguiente()) {
                espiralAgotada = true;
                break;
            }
            revisados++;
            int x = centroX + espiral.dx(), z = centroZ + espiral.dz();
            if (!tieneLod(store, ctx.dimension(), x, z)) {
                candidatos.add(ChunkPos.asLong(x, z));
            }
        }
        if (candidatos.isEmpty()) {
            if (espiralAgotada && enVuelo.isEmpty() && !avisoCompleto) {
                avisoCompleto = true;
                LOG.info("LOD: horizonte aproximado completo ({} chunks de radio)", radio);
            }
            return;
        }
        var mirada = jugador.getLookAngle();
        double px = jugador.getX(), pz = jugador.getZ();
        candidatos.sort(Comparator.comparingDouble(c -> PrioridadVista.costo(
                ChunkPos.getX(c) * 16 + 8 - px, ChunkPos.getZ(c) * 16 + 8 - pz, mirada.x, mirada.z)));
        Iterator<Long> it = candidatos.iterator();
        while (tareasEnVuelo < enVueloMaximo && it.hasNext()) {
            List<long[]> lote = new ArrayList<>(LOTE);
            while (lote.size() < LOTE && it.hasNext()) {
                long clave = it.next();
                int x = ChunkPos.getX(clave), z = ChunkPos.getZ(clave);
                if (enVuelo.contains(clave) || tieneLod(store, ctx.dimension(), x, z)) {
                    it.remove();
                    continue;
                }
                boolean unaColumna = Math.hypot(x * 16 + 8 - px, z * 16 + 8 - pz) > DISTANCIA_UNA_COLUMNA;
                lote.add(new long[]{clave, unaColumna ? 1 : 0});
            }
            if (lote.isEmpty()) {
                return;
            }
            lote.forEach(c -> enVuelo.add(c[0]));
            tareasEnVuelo++;
            var tarea = scheduler.intentarEnviar(() -> {
                try {
                    for (long[] c : lote) {
                        int x = ChunkPos.getX(c[0]), z = ChunkPos.getZ(c[0]);
                        try {
                            long inicio = System.nanoTime();
                            generar(ctx, store, x, z, c[1] == 1);
                            nanosCalculo.addAndGet(System.nanoTime() - inicio);
                            hechos.incrementAndGet();
                            generador.chunkAproximadoListo(ctx.dimension(), ctx.minSeccion(), ctx.maxSeccion(), x, z);
                        } catch (RuntimeException e) {
                            LOG.warn("LOD: no se pudo aproximar el chunk {}, {}", x, z, e);
                        } finally {
                            enVuelo.remove(c[0]);
                        }
                    }
                } finally {
                    terminadas.incrementAndGet();
                }
                return null;
            });
            if (tarea == null) {
                lote.forEach(c -> enVuelo.remove(c[0]));
                tareasEnVuelo--;
                return; // cola llena: los candidatos siguen en la ventana para el próximo tick
            }
            lote.forEach(c -> candidatos.remove(c[0]));
            it = candidatos.iterator();
        }
    }

    /** Chunk con LOD real o aproximado: no hace falta aproximarlo. */
    static boolean tieneLod(RegionFileStore store, byte dimension, int x, int z) {
        RegionFileStore.ClaveRegion region = GeneradorLocal.claveRegion(dimension, x, z);
        return store.contiene(region, GeneradorLocal.claveMarca(x, z)) || store.contiene(region, claveMarca(x, z));
    }

    /** Clave de la marca "este chunk tiene LOD aproximado". */
    public static long claveMarca(int chunkX, int chunkZ) {
        return SectionExtractor.claveNodo(TerrenoAproximado.NIVEL_MARCA, chunkX, 0, chunkZ);
    }

    private static Contexto crearContexto(ServerLevel nivel) {
        ChunkGenerator gen = nivel.getChunkSource().getGenerator();
        if (!(gen instanceof NoiseBasedChunkGenerator) || nivel.dimensionType().hasCeiling()) {
            return null;
        }
        var estado = nivel.getChunkSource().randomState();
        return new Contexto(nivel, GeneradorLocal.idDimension(nivel.dimension()), estado.router().finalDensity(),
                gen.getBiomeSource(), estado.sampler(), gen.getSeaLevel(), nivel.getMinBuildHeight(),
                nivel.getMaxBuildHeight(), nivel.getMinSection(), nivel.getMaxSection());
    }

    /** Hilo del pool: calcula y guarda los niveles 3 y 4 aproximados del chunk. */
    private static void generar(Contexto ctx, RegionFileStore store, int chunkX, int chunkZ, boolean unaColumna) {
        TerrenoAproximado.Columna[] columnas = new TerrenoAproximado.Columna[4];
        Holder<Biome> biomaAgua = null;
        int bx = chunkX * 16, bz = chunkZ * 16;
        int pista = Integer.MIN_VALUE;
        for (int cx = 0; cx < TerrenoAproximado.COLUMNAS; cx++) {
            for (int cz = 0; cz < TerrenoAproximado.COLUMNAS; cz++) {
                if (unaColumna && (cx > 0 || cz > 0)) {
                    columnas[cx * 2 + cz] = columnas[0]; // lejos: la del centro vale para todo el chunk
                    continue;
                }
                int x = unaColumna ? bx + 8 : bx + cx * 8 + 4, z = unaColumna ? bz + 8 : bz + cz * 8 + 4;
                int altura = altura(ctx, x, z, pista);
                pista = altura;
                Holder<Biome> bioma = ctx.biomas().getNoiseBiome(QuartPos.fromBlock(x),
                        QuartPos.fromBlock(Math.max(altura, ctx.nivelMar())), QuartPos.fromBlock(z), ctx.clima());
                if (biomaAgua == null) {
                    biomaAgua = bioma;
                }
                Superficie s = superficie(bioma, altura, ctx.nivelMar(), new BlockPos(x, altura, z));
                columnas[cx * 2 + cz] = new TerrenoAproximado.Columna(altura + s.elevacion(),
                        voxel(s.estado(), bioma.value(), x, z, 15),
                        voxel(s.subsuelo(), bioma.value(), x, z, 0));
            }
        }
        SuperVoxel agua = voxel(Blocks.WATER.defaultBlockState(), biomaAgua.value(), bx + 8, bz + 8, 15);
        RegionFileStore.ClaveRegion region = GeneradorLocal.claveRegion(ctx.dimension(), chunkX, chunkZ);
        for (int sy = ctx.minSeccion(); sy < ctx.maxSeccion(); sy++) {
            SuperVoxel[] n3 = TerrenoAproximado.grillaNivel3(sy, columnas, agua, ctx.nivelMar());
            if (n3 == null) {
                continue;
            }
            guardar(store, region, 3, chunkX, sy, chunkZ, n3);
            guardar(store, region, 4, chunkX, sy, chunkZ, TerrenoAproximado.grillaNivel4(n3));
        }
        store.guardar(region, claveMarca(chunkX, chunkZ), MARCA);
    }

    private static void guardar(RegionFileStore store, RegionFileStore.ClaveRegion region, int nivel,
                                int chunkX, int sy, int chunkZ, SuperVoxel[] grilla) {
        OctreeNode nodo = OctreeNode.mixto(nivel, chunkX * 16, sy * 16, chunkZ * 16, 16, grilla);
        store.guardar(region, SectionExtractor.claveNodo(TerrenoAproximado.nivelGuardado(nivel), chunkX, sy, chunkZ),
                OctreeNodeCodec.serializar(nodo, SectionExtractor.voxelesPorNodo(nivel)));
    }

    /**
     * Altura del bloque sólido más alto de la columna: se baja de a 8 bloques
     * hasta encontrar densidad positiva y se afina bloque a bloque. Sin
     * sólido en toda la columna (vacío del End), minY - 1.
     *
     * @param pista altura de una columna vecina (Integer.MIN_VALUE si no hay):
     *              la búsqueda arranca {@link #MARGEN_PISTA} por encima y, si ahí
     *              ya es sólido, sube de a 8; evita evaluar todo el cielo vacío
     */
    static int altura(Contexto ctx, int x, int z, int pista) {
        int inicio = ctx.maxY() - 1;
        if (pista != Integer.MIN_VALUE && pista >= ctx.minY()) {
            inicio = Math.min(ctx.maxY() - 1, pista + MARGEN_PISTA);
            if (solido(ctx, x, inicio, z)) {
                int y = inicio;
                while (y + 8 <= ctx.maxY() - 1 && solido(ctx, x, y + 8, z)) {
                    y += 8;
                }
                for (int arriba = Math.min(ctx.maxY() - 1, y + 7); arriba > y; arriba--) {
                    if (solido(ctx, x, arriba, z)) {
                        return arriba;
                    }
                }
                return y;
            }
        }
        for (int y = inicio; y >= ctx.minY(); y -= 8) {
            if (solido(ctx, x, y, z)) {
                for (int arriba = Math.min(ctx.maxY() - 1, y + 7); arriba > y; arriba--) {
                    if (solido(ctx, x, arriba, z)) {
                        return arriba;
                    }
                }
                return y;
            }
        }
        return ctx.minY() - 1;
    }

    private static boolean solido(Contexto ctx, int x, int y, int z) {
        return ctx.densidad().compute(new DensityFunction.SinglePointContext(x, y, z)) > 0;
    }

    /** Bloque de superficie, bloque de debajo, y cuánto sube la superficie (copa de los bosques). */
    private record Superficie(BlockState estado, BlockState subsuelo, int elevacion) {
    }

    /**
     * Superficie aproximada por bioma. No reproduce las reglas de superficie
     * de vanilla (son muchas y dependen de ruido), solo lo que define el color
     * a distancia: arena en playas y desiertos, terracota en badlands, nieve
     * donde nieva, piedra en picos, copas de árboles en bosques, pasto en el
     * resto (con el tinte del bioma).
     */
    private static Superficie superficie(Holder<Biome> bioma, int altura, int nivelMar, BlockPos pos) {
        BlockState tierra = Blocks.DIRT.defaultBlockState();
        BlockState piedra = Blocks.STONE.defaultBlockState();
        if (altura < nivelMar - 1) {
            // Fondo: arena en mar y ríos (se ve en las orillas poco profundas), tierra en el resto.
            return new Superficie(bioma.is(BiomeTags.IS_OCEAN) || bioma.is(BiomeTags.IS_RIVER)
                    ? Blocks.SAND.defaultBlockState() : tierra, piedra, 0);
        }
        if (bioma.is(BiomeTags.IS_BEACH) || bioma.is(Biomes.DESERT)) {
            return new Superficie(Blocks.SAND.defaultBlockState(), Blocks.SANDSTONE.defaultBlockState(), 0);
        }
        if (bioma.is(BiomeTags.IS_BADLANDS)) {
            return new Superficie(Blocks.TERRACOTTA.defaultBlockState(), Blocks.TERRACOTTA.defaultBlockState(), 0);
        }
        if (bioma.value().coldEnoughToSnow(pos)) {
            return new Superficie(Blocks.SNOW_BLOCK.defaultBlockState(), piedra, 0);
        }
        if (bioma.is(Biomes.STONY_PEAKS) || bioma.is(Biomes.JAGGED_PEAKS) || bioma.is(Biomes.STONY_SHORE)) {
            return new Superficie(piedra, piedra, 0);
        }
        if (bioma.is(Biomes.MUSHROOM_FIELDS)) {
            return new Superficie(Blocks.MYCELIUM.defaultBlockState(), tierra, 0);
        }
        if (bioma.is(BiomeTags.IS_TAIGA)) {
            return new Superficie(Blocks.SPRUCE_LEAVES.defaultBlockState(), tierra, 6);
        }
        if (bioma.is(BiomeTags.IS_JUNGLE)) {
            return new Superficie(Blocks.JUNGLE_LEAVES.defaultBlockState(), tierra, 8);
        }
        if (bioma.is(Biomes.DARK_FOREST)) {
            return new Superficie(Blocks.DARK_OAK_LEAVES.defaultBlockState(), tierra, 6);
        }
        if (bioma.is(BiomeTags.IS_FOREST)) {
            return new Superficie(Blocks.OAK_LEAVES.defaultBlockState(), tierra, 5);
        }
        return new Superficie(Blocks.GRASS_BLOCK.defaultBlockState(), tierra, 0);
    }

    private static SuperVoxel voxel(BlockState estado, Biome bioma, int x, int z, int luz) {
        SuperVoxel.Material material = estado.is(Blocks.WATER) ? SuperVoxel.Material.AGUA
                : estado.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock
                ? SuperVoxel.Material.VEGETACION : SuperVoxel.Material.SOLIDO;
        int rgb = ColoresBloque.rgb(estado, bioma, x, z);
        return new SuperVoxel((byte) (rgb >> 16), (byte) (rgb >> 8), (byte) rgb, (byte) 0, material, (byte) 0)
                .conLuzHorneada(luz)
                .conEstado(Block.getId(estado));
    }

    void reiniciar() {
        contexto = null;
        espiral = null;
        candidatos.clear();
        enVuelo.clear();
        tareasEnVuelo = 0;
        terminadas.set(0);
    }

    private void registrarAvance() {
        long ahora = System.nanoTime();
        if (ahora - ultimoLogNanos < PERIODO_LOG_NANOS || espiral == null) {
            return;
        }
        long n = hechos.getAndSet(0), nanos = nanosCalculo.getAndSet(0);
        if (n > 0) {
            LOG.info("LOD horizonte aproximado: anillo {} de {}, {} chunks ({} /s, {} ms/chunk en el pool)",
                    espiral.anillo(), espiral.radio(), n,
                    String.format("%.0f", n / ((ahora - ultimoLogNanos) / 1e9)),
                    String.format("%.2f", nanos / 1e6 / n));
        }
        ultimoLogNanos = ahora;
    }
}
