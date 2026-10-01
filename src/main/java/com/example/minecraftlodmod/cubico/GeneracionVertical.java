package com.example.minecraftlodmod.cubico;

import com.example.minecraftlodmod.MinecraftLodMod;
import com.example.minecraftlodmod.config.ConfigLod;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * Etapa 2 de los cubic chunks (sección 32 de la arquitectura), primera parte:
 * el servidor calcula el ruido del terreno solo en una franja vertical de cada
 * columna ({@link VentanaVertical}): la superficie con margen y la altura de
 * los jugadores cercanos. Lo de abajo queda como relleno sólido (el bloque por
 * defecto del generador: piedra en el Overworld) y lo de arriba, si se
 * recorta, como aire. Las secciones fuera de la franja quedan anotadas en el
 * chunk ({@link #VENTANA}) para completarlas cuando un jugador se acerque
 * (parte siguiente).
 *
 * Al relleno le siguen pasando las reglas de superficie (pizarra profunda,
 * lecho de roca), las cuevas de los carvers y las menas; lo que falta en él son
 * las cuevas de ruido, los acuíferos y las vetas grandes.
 *
 * Enganches ({@code cubico/mixin/MixinNoiseChunk}, {@code MixinGeneradorRuido}):
 * el rango con que se crea el {@code NoiseChunk} del chunk (paso BIOMES) y con
 * que se llena (paso NOISE). El acuífero conserva la altura completa (los
 * carvers lo consultan en todo su rango).
 */
public final class GeneracionVertical {

    private static final Logger LOG = LogUtils.getLogger();

    private GeneracionVertical() {
    }

    // ------------------------------------------------------------------ dato en el chunk

    private static final DeferredRegister<AttachmentType<?>> ADJUNTOS =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, MinecraftLodMod.MOD_ID);

    /** Franja generada con ruido completo ({@link RangoSecciones#empaquetado()}); sin dato = columna completa. */
    public static final Supplier<AttachmentType<Long>> VENTANA = ADJUNTOS.register("ventana_generacion",
            () -> AttachmentType.builder(() -> Long.MIN_VALUE)
                    .serialize(Codec.LONG, v -> v != Long.MIN_VALUE).build());

    public static void registrar(IEventBus busDelMod) {
        ADJUNTOS.register(busDelMod);
    }

    /** Franja generada de esa columna, o null si se generó (o se va a generar) completa. */
    public static RangoSecciones ventana(ChunkAccess chunk) {
        Long v = chunk.getExistingDataOrNull(VENTANA.get());
        return v == null || v == Long.MIN_VALUE ? null : RangoSecciones.desempaquetar(v);
    }

    // ------------------------------------------------------------------ jugadores (foto por tick)

    /** Por dimensión: x, y, z (bloques) de cada jugador, en tripletas. Se reemplaza entera cada tick. */
    private static volatile Map<ResourceKey<Level>, int[]> jugadores = Map.of();
    private static volatile int distanciaVista = 12;

    @SubscribeEvent
    public static void alTickServidor(ServerTickEvent.Post evento) {
        Map<ResourceKey<Level>, int[]> foto = new HashMap<>();
        for (ServerLevel nivel : evento.getServer().getAllLevels()) {
            var lista = nivel.players();
            if (lista.isEmpty()) {
                continue;
            }
            int[] pos = new int[lista.size() * 3];
            int i = 0;
            for (ServerPlayer j : lista) {
                pos[i++] = j.getBlockX();
                pos[i++] = j.getBlockY();
                pos[i++] = j.getBlockZ();
            }
            foto.put(nivel.dimension(), pos);
        }
        jugadores = foto;
        distanciaVista = evento.getServer().getPlayerList().getViewDistance();
        int tick = evento.getServer().getTickCount();
        if (tick % 10 == 0 && ConfigLod.SPEC_SERVIDOR.isLoaded()) {
            // Completar no depende de que la opción siga prendida: lo que quedó de relleno se completa igual.
            for (ServerLevel nivel : evento.getServer().getAllLevels()) {
                int[] pos = foto.get(nivel.dimension());
                if (pos != null) {
                    CompletadoVertical.revisar(nivel, pos, distanciaVista);
                }
            }
        }
        CompletadoVertical.tick(evento.getServer());
        SeccionesComprimidas.tick(evento.getServer(), foto, distanciaVista);
        if (tick % 600 == 0) {
            String r = ESTADISTICAS.resumenYReiniciar();
            if (r != null) {
                LOG.info("[LOD] Generación vertical (30 s): {}", r);
            }
            r = SeccionesComprimidas.ESTADISTICAS.resumenYReiniciar();
            if (r != null) {
                LOG.info("[LOD] Secciones lejanas (30 s): {}", r);
            }
            r = SeccionesCompartidas.ESTADISTICAS.resumenYReiniciar();
            if (r != null) {
                LOG.info("[LOD] Secciones uniformes (30 s): {}", r);
            }
            r = CompletadoVertical.ESTADISTICAS.resumenYReiniciar();
            if (r != null) {
                LOG.info("[LOD] Completado vertical (30 s): {}", r);
            }
        }
    }

    /** {@code /lodcubico completar <radio> <seccionY>}: completa alrededor como si hubiera un jugador ahí (pruebas). */
    @SubscribeEvent
    public static void alRegistrarComandos(net.neoforged.neoforge.event.RegisterCommandsEvent evento) {
        evento.getDispatcher().register(net.minecraft.commands.Commands.literal("lodcubico")
                .requires(f -> f.hasPermission(2))
                .then(net.minecraft.commands.Commands.literal("completar")
                        .then(net.minecraft.commands.Commands.argument("radio", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 32))
                                .then(net.minecraft.commands.Commands.argument("seccionY", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                        .executes(c -> {
                                            var fuente = c.getSource();
                                            BlockPosLike p = new BlockPosLike(fuente.getPosition());
                                            int n = CompletadoVertical.pedirAlrededor(fuente.getLevel(), p.cx, p.cz,
                                                    com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "radio"),
                                                    com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "seccionY"));
                                            fuente.sendSuccess(() -> net.minecraft.network.chat.Component.literal(n + " columnas pedidas"), false);
                                            return n;
                                        })))));
    }

    private record BlockPosLike(int cx, int cz) {
        BlockPosLike(net.minecraft.world.phys.Vec3 v) {
            this((int) Math.floor(v.x) >> 4, (int) Math.floor(v.z) >> 4);
        }
    }

    // ------------------------------------------------------------------ enganches de la generación

    private static boolean activa(ServerLevel nivel) {
        return ConfigLod.cubico(nivel, ConfigLod.SERVIDOR.generacionVertical);
    }

    /** Margen de abajo con superficie exacta ({@link com.example.minecraftlodmod.generation.FuenteAltura}). */
    static final int MARGEN_ABAJO_EXACTO = 2;

    /** Rango completo de la columna mientras se arma su NoiseChunk (para el acuífero). */
    private static final ThreadLocal<NoiseSettings> COMPLETO = new ThreadLocal<>();

    /**
     * Al crear el NoiseChunk de un chunk que todavía no pasó por el ruido: decide
     * su franja y la guarda en el chunk. Las veces siguientes (o al recargarlo)
     * se usa la guardada, así el NoiseChunk y el llenado coinciden.
     */
    public static void antesDeCrearNoiseChunk(ChunkAccess chunk, RandomState aleatorio, NoiseGeneratorSettings ajustes) {
        NoiseSettings completo = ajustes.noiseSettings().clampToHeightAccessor(chunk);
        COMPLETO.set(completo);
        if (!(chunk instanceof ProtoChunk proto) || proto.isUpgrading() || chunk.hasData(VENTANA.get())
                || !chunk.getPersistedStatus().isBefore(ChunkStatus.NOISE)) {
            return;
        }
        ServerLevel nivel = nivelDe(chunk);
        if (nivel == null || !activa(nivel) || !nivel.dimensionType().hasSkyLight() || nivel.dimensionType().hasCeiling()) {
            return; // el Nether y el End no tienen una "superficie" de la que colgar la franja
        }
        long t0 = System.nanoTime();
        // Con un tipo de mundo que conoce su superficie (Tierra real) la franja es exacta y el margen, chico.
        var fuente = com.example.minecraftlodmod.generation.FuenteAltura.de(nivel);
        int[] sup = fuente != null ? superficieExacta(chunk.getPos(), fuente) : estimarSuperficie(chunk.getPos(), aleatorio, completo);
        if (fuente != null) {
            // El agua del mar llega hasta el nivel del mar aunque el fondo esté cientos de bloques abajo:
            // con recortarArriba la franja tiene que llegar hasta ahí (si no, el mar sale vacío arriba).
            sup[1] = Math.max(sup[1], nivel.getSeaLevel());
        }
        var cfg = ConfigLod.SERVIDOR;
        RangoSecciones r = VentanaVertical.calcular(sup[0], sup[1], jugadoresCerca(nivel, chunk.getPos()),
                fuente != null ? Math.min(MARGEN_ABAJO_EXACTO, cfg.margenGeneracionAbajo.get()) : cfg.margenGeneracionAbajo.get(),
                ConfigLod.cubico(nivel, cfg.recortarGeneracionArriba), cfg.margenGeneracionArriba.get(),
                cfg.distanciaGeneracionJugador.get(),
                SectionPos.blockToSectionCoord(completo.minY()),
                SectionPos.blockToSectionCoord(completo.minY() + completo.height() - 1));
        ESTADISTICAS.estimacion.add(System.nanoTime() - t0);
        if (r != null) {
            chunk.setData(VENTANA.get(), r.empaquetado());
        }
    }

    public static void despuesDeCrearNoiseChunk() {
        COMPLETO.remove();
    }

    /** Rango completo de la columna cuyo NoiseChunk se está armando en este hilo (para el acuífero), o null. */
    public static NoiseSettings rangoCompleto() {
        return COMPLETO.get();
    }

    /** Recorta el rango del ruido a la franja guardada en el chunk (si la tiene). */
    public static NoiseSettings recortar(NoiseSettings completo, LevelHeightAccessor acceso) {
        if (!(acceso instanceof ChunkAccess chunk)) {
            return completo;
        }
        RangoSecciones r = ventana(chunk);
        if (r == null) {
            return completo;
        }
        int min = Math.max(completo.minY(), SectionPos.sectionToBlockCoord(r.min()));
        int max = Math.min(completo.minY() + completo.height(), SectionPos.sectionToBlockCoord(r.max() + 1));
        if (max <= min) {
            return completo;
        }
        return new NoiseSettings(min, max - min, completo.noiseSizeHorizontal(), completo.noiseSizeVertical());
    }

    /** Después del llenado: las secciones debajo de la franja pasan a relleno sólido. */
    public static void despuesDeLlenar(ChunkAccess chunk, NoiseGeneratorSettings ajustes, long nanos) {
        if (CompletadoVertical.esAparte(chunk)) {
            return; // banda de un completado: la arma CompletadoVertical
        }
        RangoSecciones r = ventana(chunk);
        ESTADISTICAS.chunk(r != null, nanos);
        if (r == null) {
            return;
        }
        BlockState relleno = ajustes.defaultBlock();
        LevelChunkSection[] secciones = chunk.getSections();
        int abajo = 0;
        int arriba = 0;
        int techoRelleno = Integer.MIN_VALUE;
        for (int i = 0; i < secciones.length; i++) {
            int sy = chunk.getSectionYFromSectionIndex(i);
            if (sy < r.min()) {
                secciones[i] = new LevelChunkSection(new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, relleno,
                        PalettedContainer.Strategy.SECTION_STATES), secciones[i].getBiomes());
                techoRelleno = SectionPos.sectionToBlockCoord(sy) + 15;
                abajo++;
            } else if (sy > r.max()) {
                arriba++;
            }
        }
        if (techoRelleno != Integer.MIN_VALUE) {
            // Si en alguna columna la franja quedó vacía, que los mapas de altura vean el relleno.
            Heightmap fondo = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
            Heightmap superficie = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    fondo.update(x, techoRelleno, z, relleno);
                    superficie.update(x, techoRelleno, z, relleno);
                }
            }
        }
        ESTADISTICAS.secciones(abajo, arriba);
    }

    // ------------------------------------------------------------------ auxiliares

    private static ServerLevel nivelDe(ChunkAccess chunk) {
        LevelHeightAccessor a = ((com.example.minecraftlodmod.cubico.mixin.AccesoChunk) chunk).minecraftlodmod$alturas();
        return a instanceof ServerLevel s ? s : null;
    }

    /**
     * Superficie estimada (menor y mayor) en las cuatro esquinas y el centro de
     * la columna, con la misma densidad y umbral que la "superficie preliminar"
     * de vanilla ({@code NoiseChunk.computePreliminarySurfaceLevel}).
     */
    /**
     * Menor y mayor altura de la superficie del chunk, exactas: la superficie
     * generada interpola entre las esquinas de celda (cada 4 bloques), así que
     * sus extremos están en esas 5×5 columnas.
     */
    static int[] superficieExacta(ChunkPos pos, com.example.minecraftlodmod.generation.FuenteAltura fuente) {
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int i = 0; i <= 16; i += 4) {
            for (int j = 0; j <= 16; j += 4) {
                int h = fuente.altura(pos.getMinBlockX() + i, pos.getMinBlockZ() + j);
                min = Math.min(min, h);
                max = Math.max(max, h);
            }
        }
        return new int[]{min, max};
    }

    private static int[] estimarSuperficie(ChunkPos pos, RandomState aleatorio, NoiseSettings completo) {
        DensityFunction densidad = aleatorio.router().initialDensityWithoutJaggedness();
        int paso = completo.getCellHeight();
        int x0 = pos.getMinBlockX();
        int z0 = pos.getMinBlockZ();
        int[][] puntos = {{x0, z0}, {x0 + 15, z0}, {x0, z0 + 15}, {x0 + 15, z0 + 15}, {x0 + 8, z0 + 8}};
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int[] p : puntos) {
            int encontrada = Integer.MAX_VALUE;
            for (int y = completo.minY() + completo.height(); y >= completo.minY(); y -= paso) {
                if (densidad.compute(new DensityFunction.SinglePointContext(p[0], y, p[1])) > 0.390625) {
                    encontrada = y;
                    break;
                }
            }
            if (encontrada == Integer.MAX_VALUE) {
                return new int[]{Integer.MAX_VALUE, Integer.MIN_VALUE}; // una columna sin terreno: no recortar
            }
            min = Math.min(min, encontrada);
            max = Math.max(max, encontrada);
        }
        return new int[]{min, max};
    }

    /** Y de los jugadores a una distancia horizontal en que su vista puede pedir este chunk. */
    private static int[] jugadoresCerca(ServerLevel nivel, ChunkPos pos) {
        int[] todos = jugadores.get(nivel.dimension());
        if (todos == null) {
            return new int[0];
        }
        int alcance = distanciaVista + 8;
        int[] ys = new int[todos.length / 3];
        int n = 0;
        for (int i = 0; i < todos.length; i += 3) {
            int cx = todos[i] >> 4;
            int cz = todos[i + 2] >> 4;
            if (Math.abs(cx - pos.x) <= alcance && Math.abs(cz - pos.z) <= alcance) {
                ys[n++] = todos[i + 1];
            }
        }
        return java.util.Arrays.copyOf(ys, n);
    }

    // ------------------------------------------------------------------ medición

    public static final Estadisticas ESTADISTICAS = new Estadisticas();

    public static final class Estadisticas {
        private final LongAdder chunks = new LongAdder();
        private final LongAdder recortados = new LongAdder();
        private final LongAdder nanosRuido = new LongAdder();
        private final LongAdder nanosRuidoRecortados = new LongAdder();
        final LongAdder estimacion = new LongAdder();
        private final LongAdder abajo = new LongAdder();
        private final LongAdder arriba = new LongAdder();

        void chunk(boolean recortado, long nanos) {
            chunks.increment();
            nanosRuido.add(nanos);
            if (recortado) {
                recortados.increment();
                nanosRuidoRecortados.add(nanos);
            }
        }

        void secciones(int a, int b) {
            abajo.add(a);
            arriba.add(b);
        }

        /** Resumen desde la última llamada, o null si no se llenó ningún chunk. */
        public String resumenYReiniciar() {
            long c = chunks.sumThenReset();
            long r = recortados.sumThenReset();
            long n = nanosRuido.sumThenReset();
            long nr = nanosRuidoRecortados.sumThenReset();
            long e = estimacion.sumThenReset();
            long a = abajo.sumThenReset();
            long b = arriba.sumThenReset();
            if (c == 0) {
                return null;
            }
            return String.format("%d chunks con ruido (%d con franja), %.2f ms de ruido por chunk"
                            + " (%.2f los recortados, %.2f ms de estimación), secciones omitidas: %d abajo, %d arriba",
                    c, r, n / 1e6 / c, r == 0 ? 0.0 : nr / 1e6 / r, r == 0 ? 0.0 : e / 1e6 / r, a, b);
        }
    }
}
