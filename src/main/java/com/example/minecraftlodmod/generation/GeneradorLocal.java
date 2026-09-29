package com.example.minecraftlodmod.generation;

import com.example.minecraftlodmod.config.PresupuestoMemoria;
import com.example.minecraftlodmod.config.QualityPreset;
import com.example.minecraftlodmod.core.OctreeNode;
import com.example.minecraftlodmod.storage.OctreeNodeCodec;
import com.example.minecraftlodmod.storage.RegionFileStore;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Pipeline de generación en modo LOCAL (sección 4): lee el {@code Level}
 * directamente. Lo usa el servidor — dedicado, o el integrado en
 * singleplayer — y deja los nodos en el cache de disco que después
 * consumen render/ (singleplayer) y network/ (multiplayer).
 *
 * Flujo por chunk:
 *   carga (hilo del servidor) → {@link LectorSeccionMinecraft#capturar}
 *   → pool de {@link GenerationTaskScheduler}: extraer → niveles → serializar
 *   → {@link RegionFileStore#guardar} (write-behind).
 *
 * Un chunk se genera la primera vez que se carga y otra vez al descargarse
 * si quedó modificado — invalidación gruesa pero barata (sección 10); la
 * fina por evento de bloque queda para cuando network/ la necesite.
 *
 * Se registra en {@code NeoForge.EVENT_BUS}; una instancia vive lo que dura
 * un servidor.
 */
public final class GeneradorLocal {

    private static final Logger LOG = LogUtils.getLogger();

    /**
     * Versión del algoritmo de extracción/reducción, mezclada en el
     * {@code hashFuente} del cache: subirla invalida todo lo generado antes.
     */
    public static final long VERSION_ALGORITMO = 1;

    /** Nivel reservado en {@link SectionExtractor#claveNodo} para marcar "este chunk ya se generó". */
    private static final int NIVEL_MARCA_CHUNK = 15;
    private static final byte[] MARCA = new byte[0];
    private static final long PERIODO_ESCRITURA_MS = 3000;

    private final QualityPreset preset;
    // volatile: los lee también network/ desde los hilos del pool.
    private volatile GenerationTaskScheduler scheduler;
    private volatile RegionFileStore store;
    private int descartadosPorColaLlena;

    public GeneradorLocal(QualityPreset preset) {
        this.preset = preset;
    }

    /**
     * AboutToStart y no Started: los chunks del spawn se cargan entre ambos
     * eventos, y con el store creado recién en Started se perdían.
     */
    @SubscribeEvent
    public void alArrancarServidor(ServerAboutToStartEvent evento) {
        MinecraftServer servidor = evento.getServer();
        Path directorio = servidor.getWorldPath(LevelResource.ROOT).resolve("minecraftlodmod");
        long hashFuente = servidor.getWorldData().worldGenOptions().seed() * 31 + VERSION_ALGORITMO;
        PresupuestoMemoria presupuesto = PresupuestoMemoria.para(preset);
        store = new RegionFileStore(directorio, hashFuente, PERIODO_ESCRITURA_MS);
        scheduler = new GenerationTaskScheduler(preset.hilosGeneracion, presupuesto.maxTareasEnCola());
        LOG.info("LOD: generación LOCAL activa (preset {}, {} hilos, cola {}) en {}",
                preset, preset.hilosGeneracion, presupuesto.maxTareasEnCola(), directorio);
    }

    @SubscribeEvent
    public void alDetenerServidor(ServerStoppingEvent evento) {
        if (scheduler != null) {
            scheduler.apagar();
            scheduler = null;
        }
        if (store != null) {
            try {
                store.close();
            } catch (IOException e) {
                LOG.error("LOD: no se pudo bajar el cache de regiones a disco", e);
            }
            store = null;
        }
        if (descartadosPorColaLlena > 0) {
            LOG.info("LOD: {} chunks no se generaron por cola llena (se reintentan al recargarse)",
                    descartadosPorColaLlena);
        }
    }

    @SubscribeEvent
    public void alCargarChunk(ChunkEvent.Load evento) {
        if (!(evento.getLevel() instanceof ServerLevel nivel) || !(evento.getChunk() instanceof LevelChunk chunk)) {
            return;
        }
        if (store == null || store.contiene(claveRegion(nivel, chunk), claveMarca(chunk))) {
            return;
        }
        encolar(nivel, chunk);
    }

    @SubscribeEvent
    public void alDescargarChunk(ChunkEvent.Unload evento) {
        if (evento.getLevel() instanceof ServerLevel nivel && evento.getChunk() instanceof LevelChunk chunk
                && chunk.isUnsaved()) {
            encolar(nivel, chunk);
        }
    }

    private void encolar(ServerLevel nivel, ChunkAccess chunk) {
        if (store == null || scheduler == null) {
            return;
        }
        List<LectorSeccionMinecraft.Captura> capturas = LectorSeccionMinecraft.capturar(nivel, chunk);
        RegionFileStore.ClaveRegion region = claveRegion(nivel, chunk);
        long marca = claveMarca(chunk);
        RegionFileStore destino = store;
        int colapsoDesde = preset.colapsoHomogeneoDesdeNivel;

        var tarea = scheduler.intentarEnviar(() -> {
            for (LectorSeccionMinecraft.Captura captura : capturas) {
                SectionExtractor.SeccionExtraida seccion = captura.extraer();
                if (seccion == null) {
                    continue;
                }
                for (OctreeNode nodo : SectionExtractor.generarNiveles(seccion, colapsoDesde)) {
                    long clave = SectionExtractor.claveNodo(nodo.nivelLod(),
                            seccion.seccionX(), seccion.seccionY(), seccion.seccionZ());
                    destino.guardar(region, clave,
                            OctreeNodeCodec.serializar(nodo, SectionExtractor.voxelesPorNodo(nodo.nivelLod())));
                }
            }
            destino.guardar(region, marca, MARCA);
            return null;
        });
        if (tarea == null) {
            descartadosPorColaLlena++;
        }
    }

    public QualityPreset preset() {
        return preset;
    }

    /** Cache de disco del servidor en curso, o null si no hay servidor corriendo. */
    public RegionFileStore store() {
        return store;
    }

    /** Pool de generación del servidor en curso (lo comparte network/ para leer disco), o null. */
    public GenerationTaskScheduler scheduler() {
        return scheduler;
    }

    private static RegionFileStore.ClaveRegion claveRegion(ServerLevel nivel, ChunkAccess chunk) {
        return claveRegion(idDimension(nivel.dimension()), chunk.getPos().x, chunk.getPos().z);
    }

    public static RegionFileStore.ClaveRegion claveRegion(byte dimensionId, int seccionX, int seccionZ) {
        return new RegionFileStore.ClaveRegion(dimensionId,
                SectionExtractor.regionDe(seccionX), SectionExtractor.regionDe(seccionZ));
    }

    private static long claveMarca(ChunkAccess chunk) {
        return claveMarca(chunk.getPos().x, chunk.getPos().z);
    }

    /** Clave de la marca "este chunk ya se generó": distingue sección vacía de chunk nunca cargado. */
    public static long claveMarca(int chunkX, int chunkZ) {
        return SectionExtractor.claveNodo(NIVEL_MARCA_CHUNK, chunkX, 0, chunkZ);
    }

    /**
     * {@code dimension_id} de 1 byte del formato (sección 5): 0-2 para las
     * vanilla, el resto por hash del id. Dos dimensiones de mods pueden
     * chocar (1 en 253) y entonces comparten archivos de región y se pisan
     * los nodos en las mismas coordenadas — anotado en NOTES.md.
     */
    public static byte idDimension(ResourceKey<Level> dimension) {
        if (dimension.equals(Level.OVERWORLD)) return 0;
        if (dimension.equals(Level.NETHER)) return 1;
        if (dimension.equals(Level.END)) return 2;
        return (byte) (3 + Math.floorMod(dimension.location().hashCode(), 253));
    }
}
