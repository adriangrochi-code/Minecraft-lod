package com.example.minecraftlodmod.tierra;

import com.example.minecraftlodmod.MinecraftLodMod;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.MapCodec;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registro del tipo de mundo Tierra real ({@code docs/tierra-real/}): la
 * función de densidad {@link SuperficieTierra}, los datos compartidos entre
 * mundos ({@code .minecraft/minecraftlodmod/tierra/tierra.lodt}, preparados
 * con {@link PreparadorDatos}) y los comandos de prueba. Los
 * {@code world_preset}, {@code noise_settings} y {@code dimension_type} son
 * datos del mod ({@code data/minecraftlodmod/worldgen/}).
 */
public final class TierraReal {

    private static final Logger LOG = LogUtils.getLogger();
    public static final String ARCHIVO = "tierra.lodt";
    /** Tope de la caché de teselas: ~340 teselas de 256×256. */
    private static final long TOPE_CACHE_BYTES = 64L << 20;

    private static final DeferredRegister<MapCodec<? extends DensityFunction>> FUNCIONES =
            DeferredRegister.create(Registries.DENSITY_FUNCTION_TYPE, MinecraftLodMod.MOD_ID);
    private static final DeferredRegister<MapCodec<? extends net.minecraft.world.level.biome.BiomeSource>> FUENTES_BIOMAS =
            DeferredRegister.create(Registries.BIOME_SOURCE, MinecraftLodMod.MOD_ID);

    static {
        FUNCIONES.register("tierra_superficie", () -> SuperficieTierra.CODEC_MAPA);
        FUENTES_BIOMAS.register("tierra", () -> FuenteBiomasTierra.CODEC);
    }

    private static final Object CANDADO = new Object();
    private static volatile FuenteTierra fuente;
    private static volatile boolean fuenteFallo;
    private static final Map<String, AlturaTierra> ALTURAS = new ConcurrentHashMap<>();

    private TierraReal() {}

    public static void registrar(IEventBus busDelMod) {
        FUNCIONES.register(busDelMod);
        FUENTES_BIOMAS.register(busDelMod);
        // Opciones de cubico/ prendidas por defecto en Tierra real (docs/tierra-real/04-integracion-lod.md, punto 7).
        if (!Boolean.getBoolean("minecraftlodmod.tierraSinCubico")) { // para medir sin ellas
            com.example.minecraftlodmod.config.ConfigLod.cubicoPorDefecto = nivel -> nivel.dimensionTypeRegistration()
                    .unwrapKey().map(k -> metrosPorBloque(k.location()) > 0).orElse(false);
        }
        // Atajo del LOD aproximado: la altura de cada columna sale de los datos, sin buscarla en la densidad.
        com.example.minecraftlodmod.generation.FuenteAltura.registrar(nivel -> {
            AlturaTierra a = alturaDe(nivel);
            return a == null ? null : a::altura;
        });
    }

    /**
     * Metros por bloque de una dimensión de Tierra real por la clave de su
     * {@code dimension_type} ({@code minecraftlodmod:tierra_8} → 8), o 0 si no
     * es de Tierra real. El cliente recibe el tipo de dimensión del servidor,
     * así que le alcanza para la curvatura sin un paquete propio.
     */
    public static double metrosPorBloque(net.minecraft.resources.ResourceLocation tipoDimension) {
        if (tipoDimension == null || !tipoDimension.getNamespace().equals(MinecraftLodMod.MOD_ID)) return 0;
        return metrosPorBloque(tipoDimension.getPath());
    }

    static double metrosPorBloque(String ruta) {
        if (!ruta.startsWith("tierra_")) return 0;
        try {
            double m = Double.parseDouble(ruta.substring("tierra_".length()));
            return m >= 1 && m <= 64 ? m : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Radio del planeta en bloques para la curvatura del LOD en este nivel
     * (6371 km / escala: 796 km a 1:8), o 0 si no es Tierra real.
     */
    public static double radioPlaneta(net.minecraft.world.level.Level nivel) {
        if (nivel == null) return 0;
        double m = nivel.dimensionTypeRegistration().unwrapKey().map(k -> metrosPorBloque(k.location())).orElse(0.0);
        return m > 0 ? Proyeccion.RADIO_TIERRA_M / m : 0;
    }

    /**
     * Relieve lejano que el horizonte real del LOD tiene en cuenta en Tierra
     * real: 2 km (las montañas de 2 km detrás del horizonte asoman; las más
     * altas se cortan antes, a cambio de no generar ~40 000 bloques de radio).
     */
    public static double relieveHorizonte(net.minecraft.world.level.Level nivel) {
        double m = nivel == null ? 0
                : nivel.dimensionTypeRegistration().unwrapKey().map(k -> metrosPorBloque(k.location())).orElse(0.0);
        return m > 0 ? 2000 / m : com.example.minecraftlodmod.core.HorizonteCurvo.RELIEVE;
    }

    public static Path archivoDatos() {
        return FMLPaths.GAMEDIR.get().resolve(MinecraftLodMod.MOD_ID).resolve("tierra").resolve(ARCHIVO);
    }

    /** Superficie para una proyección y escala, o {@code null} si no hay datos (se avisa una vez). */
    static AlturaTierra altura(String proyeccion, double metrosPorBloque) {
        FuenteTierra f = fuente();
        if (f == null) return null;
        return ALTURAS.computeIfAbsent(proyeccion + "/" + metrosPorBloque, k -> {
            Proyeccion p = proyeccion.equals(SuperficieTierra.AZIMUTAL)
                    ? new ProyeccionAzimutal(metrosPorBloque) : new ProyeccionCilindrica(metrosPorBloque);
            AlturaTierra a = new AlturaTierra(f, p, 1.0);
            LOG.info("[Tierra real] {} 1:{}: fondo de la fosa en y {} (lecho de roca hasta ahí), cima en y {}",
                    proyeccion, metrosPorBloque, a.yFondoFosa(), a.yCima());
            return a;
        });
    }

    private static FuenteTierra fuente() {
        FuenteTierra f = fuente;
        if (f != null || fuenteFallo) return f;
        synchronized (CANDADO) {
            if (fuente != null || fuenteFallo) return fuente;
            Path archivo = archivoDatos();
            try {
                if (!Files.exists(archivo)) throw new IOException("no existe");
                LectorLodt lector = LectorLodt.abrir(archivo, TOPE_CACHE_BYTES);
                FormatoLodt.Cabecera c = lector.cabecera;
                LOG.info("[Tierra real] datos {}: {}×{} muestras, paso {}°, elevación {}..{} m",
                        archivo, c.ancho(), c.alto(), c.paso(), c.elevMinima(), c.elevMaxima());
                if (!c.global()) LOG.warn("[Tierra real] los datos no cubren todo el planeta: fuera del recorte se repite el borde");
                fuente = new FuenteTierra(lector);
            } catch (IOException | RuntimeException e) {
                fuenteFallo = true;
                LOG.error("[Tierra real] no se pudieron abrir los datos ({}): {}. Se genera un fondo de mar plano. "
                        + "Prepararlos con PreparadorDatos (docs/tierra-real/02-datos.md).", archivo, e.getMessage());
            }
            return fuente;
        }
    }

    // ------------------------------------------------------------------ comandos de prueba

    /**
     * {@code /tierra ir <lat> <lon>}: lleva al jugador a ese lugar, parado en la
     * superficie (o sobre el agua). {@code /tierra medir <lat> <lon>}: genera
     * esa columna y compara el bloque sólido más alto con {@link AlturaTierra}
     * (la prueba de "elevación en puntos conocidos" de H3, por RCON).
     */
    @SubscribeEvent
    public static void alRegistrarComandos(RegisterCommandsEvent evento) {
        evento.getDispatcher().register(Commands.literal("tierra")
                .requires(f -> f.hasPermission(2))
                .then(Commands.literal("ir")
                        .then(Commands.argument("lat", DoubleArgumentType.doubleArg(-90, 90))
                                .then(Commands.argument("lon", DoubleArgumentType.doubleArg(-180, 180))
                                        .executes(TierraReal::ir))))
                .then(Commands.literal("medir")
                        .then(Commands.argument("lat", DoubleArgumentType.doubleArg(-90, 90))
                                .then(Commands.argument("lon", DoubleArgumentType.doubleArg(-180, 180))
                                        .executes(TierraReal::medir)))));
    }

    private static AlturaTierra alturaDe(ServerLevel nivel) {
        if (nivel.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator g) {
            SuperficieTierra s = SuperficieTierra.de(g.generatorSettings().value());
            if (s != null) return s.altura();
        }
        return null;
    }

    private static int ir(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack fuente = c.getSource();
        ServerPlayer jugador = fuente.getPlayerOrException();
        AlturaTierra a = alturaDe(fuente.getLevel());
        if (a == null) {
            fuente.sendFailure(Component.literal("Esta dimensión no es Tierra real (o faltan los datos)"));
            return 0;
        }
        double lat = DoubleArgumentType.getDouble(c, "lat"), lon = DoubleArgumentType.getDouble(c, "lon");
        double x = a.proyeccion().x(lat, lon), z = a.proyeccion().z(lat, lon);
        int y = Math.max(a.altura((int) Math.floor(x), (int) Math.floor(z)), AlturaTierra.NIVEL_MAR - 1) + 1;
        jugador.teleportTo(fuente.getLevel(), x, y, z, jugador.getYRot(), jugador.getXRot());
        fuente.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "%.4f, %.4f → %.0f %d %.0f", lat, lon, x, y, z)), true);
        return 1;
    }

    private static int medir(CommandContext<CommandSourceStack> c) {
        CommandSourceStack fuente = c.getSource();
        ServerLevel nivel = fuente.getLevel();
        AlturaTierra a = alturaDe(nivel);
        if (a == null) {
            fuente.sendFailure(Component.literal("Esta dimensión no es Tierra real (o faltan los datos)"));
            return 0;
        }
        double lat = DoubleArgumentType.getDouble(c, "lat"), lon = DoubleArgumentType.getDouble(c, "lon");
        int x = (int) Math.floor(a.proyeccion().x(lat, lon)), z = (int) Math.floor(a.proyeccion().z(lat, lon));
        long inicio = System.nanoTime();
        ChunkAccess chunk = nivel.getChunk(x >> 4, z >> 4);
        double ms = (System.nanoTime() - inicio) / 1e6;
        int esperada = a.altura(x, z);
        int real = Integer.MIN_VALUE;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, 0, z);
        for (int y = nivel.getMaxBuildHeight() - 1; y >= nivel.getMinBuildHeight(); y--) {
            BlockState b = chunk.getBlockState(p.setY(y));
            if (!b.isAir() && b.getFluidState().isEmpty()) {
                real = y;
                break;
            }
        }
        int debajo = real - 1;
        String bioma = nivel.getBiome(p.setY(Math.max(real, esperada) + 1)).unwrapKey()
                .map(k -> k.location().toString()).orElse("?");
        if (nivel.getChunkSource().getGenerator().getBiomeSource() instanceof FuenteBiomasTierra fb) {
            bioma = fb.claveDe(x, z) + " → " + bioma;
        }
        String piso = real == Integer.MIN_VALUE ? "-" : chunk.getBlockState(p.setY(debajo)).getBlock().getName().getString();
        String texto = String.format(Locale.ROOT,
                "medir %.4f %.4f: x %d z %d, elevación %.1f m, bioma %s, esperada y %d, real y %d (%s; debajo: %s; 100 más abajo: %s), agua hasta y %d, chunk %.0f ms",
                lat, lon, x, z, a.elevacionMetros(x + 0.5, z + 0.5), bioma, esperada, real,
                real == Integer.MIN_VALUE ? "-" : chunk.getBlockState(p.setY(real)).getBlock().getName().getString(),
                piso, real - 100 < nivel.getMinBuildHeight() ? "-" : chunk.getBlockState(p.setY(real - 100)).getBlock().getName().getString(),
                aguaHasta(chunk, p, nivel), ms);
        LOG.info("[Tierra real] {}", texto);
        fuente.sendSuccess(() -> Component.literal(texto), false);
        return real == esperada ? 1 : 0;
    }

    private static int aguaHasta(ChunkAccess chunk, BlockPos.MutableBlockPos p, ServerLevel nivel) {
        for (int y = nivel.getMaxBuildHeight() - 1; y >= nivel.getMinBuildHeight(); y--) {
            if (!chunk.getBlockState(p.setY(y)).getFluidState().isEmpty()) return y;
        }
        return Integer.MIN_VALUE;
    }
}
