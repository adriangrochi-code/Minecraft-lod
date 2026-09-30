package com.example.minecraftlodmod.generation;

import com.example.minecraftlodmod.config.ConfigLod;

import com.example.minecraftlodmod.config.PresupuestoMemoria;
import com.example.minecraftlodmod.config.ParametrosCalidad;
import com.example.minecraftlodmod.core.OctreeNode;
import com.example.minecraftlodmod.core.SuperVoxel;
import com.example.minecraftlodmod.storage.OctreeNodeCodec;
import com.example.minecraftlodmod.storage.RegionFileStore;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.List;
import java.util.function.Function;

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
 * Si la cola de generación está llena el chunk queda PENDIENTE y se
 * reintenta de a {@link #REINTENTOS_POR_TICK} por tick mientras siga
 * cargado: sin esto, los chunks que nunca se descargan (spawn, forceload)
 * se perdían para siempre al llenarse la cola una sola vez.
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
    public static final long VERSION_ALGORITMO = 11;

    /** Nivel reservado en {@link SectionExtractor#claveNodo} para marcar "este chunk ya se generó". */
    private static final int NIVEL_MARCA_CHUNK = 15;
    private static final byte[] MARCA = new byte[0];
    private static final long PERIODO_ESCRITURA_MS = 3000;
    static final int REINTENTOS_POR_TICK = 8;

    /** Chunks que no entraron en la cola; solo hilo del servidor. */
    private record Pendiente(ResourceKey<Level> dimension, long chunk) {
    }

    private final LinkedHashSet<Pendiente> pendientes = new LinkedHashSet<>();
    private final PregeneradorChunks pregenerador = new PregeneradorChunks(this);
    private final GeneradorAproximado aproximado = new GeneradorAproximado(this);

    /** Cada cuántos ticks se reconstruyen los niveles grandes de lo recién generado (5 s). */
    static final int TICKS_ENTRE_LOTES_GRANDES = 100;

    /** Chunks generados desde el último lote de niveles grandes, por dimensión (los escriben los hilos del pool). */
    private record Dimension(byte id, int minSeccion, int maxSeccion) {
    }

    private final ConcurrentHashMap<Dimension, Set<Long>> chunksSucios = new ConcurrentHashMap<>();
    private final AtomicBoolean loteGrandeEnCurso = new AtomicBoolean();
    private int ticksDesdeLote;

    // Estadísticas para estimar el costo en cada hardware (log cada 10 s si hubo trabajo).
    private final LongAdder nanosChunks = new LongAdder();
    private final LongAdder chunksHechos = new LongAdder();
    /** Total desde que arrancó el juego (no se reinicia): el monitor de rendimiento saca el ritmo por diferencia. */
    private final java.util.concurrent.atomic.AtomicLong chunksExtraidosTotal = new java.util.concurrent.atomic.AtomicLong();
    private final LongAdder nanosLotesGrandes = new LongAdder();
    private long nanosCapturaHiloServidor;
    private int ticksDesdeEstadistica;

    private void registrarEstadisticas() {
        if (++ticksDesdeEstadistica < 200) {
            return;
        }
        ticksDesdeEstadistica = 0;
        long chunks = chunksHechos.sumThenReset();
        long nanos = nanosChunks.sumThenReset();
        long nanosGrandes = nanosLotesGrandes.sumThenReset();
        if (chunks == 0 && pendientes.isEmpty()) {
            nanosCapturaHiloServidor = 0;
            return;
        }
        RegionFileStore s = store;
        LOG.info("LOD gen: {} chunks en 10 s ({} ms/chunk en el pool, {} ms/chunk en el hilo del servidor), "
                        + "niveles grandes {} ms, {} pendientes | RAM LOD: {} MB por escribir, {} MB en cache",
                chunks, chunks == 0 ? 0 : String.format("%.1f", nanos / 1e6 / chunks),
                chunks == 0 ? 0 : String.format("%.2f", nanosCapturaHiloServidor / 1e6 / chunks),
                nanosGrandes / 1_000_000, pendientes.size(),
                s == null ? 0 : s.bytesPendientes() >> 20, s == null ? 0 : s.bytesEnCache() >> 20);
        nanosCapturaHiloServidor = 0;
    }

    private final Function<MinecraftServer, ParametrosCalidad> resolverCalidad;
    private volatile ParametrosCalidad calidad;
    // volatile: los lee también network/ desde los hilos del pool.
    private volatile GenerationTaskScheduler scheduler;
    private volatile RegionFileStore store;
    private int descartadosPorColaLlena;

    /**
     * @param resolverCalidad calidad con que genera cada servidor; se evalúa
     *                        al arrancar, con la config de servidor ya cargada
     *                        ({@code ConfigLod::calidadServidor})
     */
    public GeneradorLocal(Function<MinecraftServer, ParametrosCalidad> resolverCalidad) {
        this.resolverCalidad = resolverCalidad;
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
        ParametrosCalidad calidad = resolverCalidad.apply(servidor);
        this.calidad = calidad;
        PresupuestoMemoria presupuesto = PresupuestoMemoria.para(calidad.cacheRamMb(), calidad.hilosGeneracion());
        // El slider "RAM para LOD" (cacheRamMb): su parte de cache limita lo
        // que el store tiene en memoria antes de mandarlo a disco.
        store = new RegionFileStore(directorio, hashFuente, PERIODO_ESCRITURA_MS, presupuesto.bytesCacheRegiones());
        scheduler = new GenerationTaskScheduler(calidad.hilosGeneracion(), presupuesto.maxTareasEnCola());
        LOG.info("LOD: generación LOCAL activa ({}, cola {}) en {}",
                calidad, presupuesto.maxTareasEnCola(), directorio);
    }

    @SubscribeEvent
    public void alRegistrarComandos(RegisterCommandsEvent evento) {
        ComandoPregeneracion.registrar(evento.getDispatcher(), pregenerador);
    }

    @SubscribeEvent
    public void alActualizarTags(TagsUpdatedEvent evento) {
        LectorSeccionMinecraft.olvidarMateriales();
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
            LOG.info("LOD: {} veces la cola estuvo llena; {} chunks quedaron pendientes al cerrar",
                    descartadosPorColaLlena, pendientes.size());
        }
        pendientes.clear();
        chunksSucios.clear();
        pregenerador.reiniciar();
        pregeneradorRoto = false;
        aproximado.reiniciar();
        aproximadoRoto = false;
        descartadosPorColaLlena = 0;
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

    /**
     * Reintenta pendientes empezando por los más cercanos a algún jugador,
     * con prioridad a lo que mira ({@link PrioridadVista}): el LOD se
     * completa del centro hacia afuera, primero en el campo visual. Recorre todos los
     * pendientes por tick (lineal, barato para miles), no hace falta
     * mantenerlos ordenados mientras los jugadores se mueven.
     */
    @SubscribeEvent
    public void alTerminarTick(ServerTickEvent.Post evento) {
        registrarEstadisticas();
        pregenerar(evento.getServer());
        aproximar(evento.getServer());
        if (++ticksDesdeLote >= TICKS_ENTRE_LOTES_GRANDES) {
            ticksDesdeLote = 0;
            lanzarLoteGrande();
        }
        if (pendientes.isEmpty() || store == null) {
            return;
        }
        for (int i = 0; i < REINTENTOS_POR_TICK && !pendientes.isEmpty(); i++) {
            Pendiente p = masCercanoAJugador(evento.getServer());
            ServerLevel nivel = evento.getServer().getLevel(p.dimension());
            LevelChunk chunk = nivel == null ? null
                    : nivel.getChunkSource().getChunkNow(ChunkPos.getX(p.chunk()), ChunkPos.getZ(p.chunk()));
            // Descargado: se regenera en la próxima carga. Ya generado: nada que hacer.
            if (chunk == null || store.contiene(claveRegion(nivel, chunk), claveMarca(chunk))) {
                pendientes.remove(p);
                continue;
            }
            if (!encolarSinPendiente(nivel, chunk)) {
                return; // cola todavía llena: seguir el próximo tick
            }
            pendientes.remove(p);
        }
    }

    /**
     * Pregeneración (solo singleplayer por ahora: la opción vive en la config
     * del cliente, que en un servidor dedicado no existe). Nunca tira el
     * servidor abajo: un error la apaga hasta el próximo arranque.
     */
    private void pregenerar(MinecraftServer servidor) {
        if (pregeneradorRoto || !servidor.isSingleplayer()) {
            return;
        }
        try {
            pregenerador.tick(servidor, ConfigLod.CLIENTE.pregenerar.get(), ConfigLod.CLIENTE.radioPregeneracion.get());
        } catch (RuntimeException e) {
            pregeneradorRoto = true;
            pregenerador.soltarTodo();
            LOG.error("LOD: la pregeneración falló y se apaga hasta reiniciar el mundo", e);
        }
    }

    /**
     * Radio pedido por el render con "horizonte real" (curvatura), en chunks;
     * 0 = el del preset. El horizonte aproximado genera hasta ahí.
     */
    public static volatile int radioHorizonteCliente;

    private boolean pregeneradorRoto;
    private boolean aproximadoRoto;

    /** Horizonte aproximado (solo singleplayer por la misma razón que la pregeneración). */
    private void aproximar(MinecraftServer servidor) {
        if (aproximadoRoto || !servidor.isSingleplayer() || calidad == null) {
            return;
        }
        try {
            int radio = radioHorizonteCliente > 0 ? radioHorizonteCliente : calidad.radioLodChunks();
            aproximado.tick(servidor, ConfigLod.CLIENTE.generacionAproximada.get(), radio);
        } catch (RuntimeException e) {
            aproximadoRoto = true;
            LOG.error("LOD: la generación aproximada falló y se apaga hasta reiniciar el mundo", e);
        }
    }

    /** Un chunk aproximado quedó guardado: sus niveles grandes se rearman en el próximo lote. */
    void chunkAproximadoListo(byte dimension, int minSeccion, int maxSeccion, int chunkX, int chunkZ) {
        chunksSucios.computeIfAbsent(new Dimension(dimension, minSeccion, maxSeccion), d -> ConcurrentHashMap.newKeySet())
                .add(NivelesGrandes.empaquetar(chunkX, chunkZ));
    }

    /** Chunks cargados que esperan lugar en la cola de extracción. */
    /** Para el HUD y el log de depuración (cualquier hilo). */
    public long chunksExtraidosTotal() {
        return chunksExtraidosTotal.get();
    }

    public long chunksAproximadosTotal() {
        return aproximado.hechosTotal();
    }

    public PregeneradorChunks.Estado estadoPregeneracion() {
        return pregenerador.estado();
    }

    /** Chunks cargados que esperan lugar en la cola de extracción (el tamaño lo lee el HUD sin sincronizar: aproximado). */
    public int cantidadPendientes() {
        return pendientes.size();
    }

    private Pendiente masCercanoAJugador(MinecraftServer servidor) {
        Pendiente mejor = null;
        double mejorDistancia = Double.MAX_VALUE;
        var jugadores = servidor.getPlayerList().getPlayers();
        for (Pendiente p : pendientes) {
            double centroX = ChunkPos.getX(p.chunk()) * 16 + 8;
            double centroZ = ChunkPos.getZ(p.chunk()) * 16 + 8;
            double distancia = jugadores.isEmpty() ? 0 : Double.MAX_VALUE;
            for (ServerPlayer jugador : jugadores) {
                if (jugador.level().dimension() == p.dimension()) {
                    // Lo que el jugador mira primero (PrioridadVista), después el resto.
                    var mirada = jugador.getLookAngle();
                    distancia = Math.min(distancia, PrioridadVista.costo(centroX - jugador.getX(),
                            centroZ - jugador.getZ(), mirada.x, mirada.z));
                }
            }
            if (mejor == null || distancia < mejorDistancia) {
                mejor = p;
                mejorDistancia = distancia;
            }
        }
        return mejor;
    }

    /**
     * Reconstruye en el pool los niveles grandes ({@link NivelesGrandes})
     * de los chunks generados desde el lote anterior. Un lote a la vez; si
     * la cola está llena, los chunks esperan al próximo intento.
     */
    private void lanzarLoteGrande() {
        RegionFileStore destino = store;
        if (destino == null || scheduler == null || chunksSucios.isEmpty()
                || !loteGrandeEnCurso.compareAndSet(false, true)) {
            return;
        }
        Map<Dimension, Set<Long>> lote = new HashMap<>();
        for (Dimension d : chunksSucios.keySet()) {
            Set<Long> sucios = chunksSucios.remove(d);
            if (sucios != null && !sucios.isEmpty()) {
                lote.put(d, sucios);
            }
        }
        var tarea = scheduler.intentarEnviar(() -> {
            try {
                long inicio = System.nanoTime();
                int nodos = 0;
                try {
                for (Map.Entry<Dimension, Set<Long>> e : lote.entrySet()) {
                    Dimension d = e.getKey();
                    nodos += NivelesGrandes.actualizar(e.getValue(), d.minSeccion(), d.maxSeccion(),
                            new AccesoStore(destino, d.id()));
                }
                } finally {
                    nanosLotesGrandes.add(System.nanoTime() - inicio);
                }
                LOG.debug("LOD: niveles grandes: {} nodos en {} ms", nodos, (System.nanoTime() - inicio) / 1_000_000);
            } catch (RuntimeException ex) {
                LOG.error("LOD: falló la reconstrucción de niveles grandes", ex);
            } finally {
                loteGrandeEnCurso.set(false);
            }
            return null;
        });
        if (tarea == null) {
            // Cola llena: devolver los chunks para el próximo lote.
            lote.forEach((d, sucios) -> chunksSucios.computeIfAbsent(d, k -> ConcurrentHashMap.newKeySet()).addAll(sucios));
            loteGrandeEnCurso.set(false);
        }
    }

    /**
     * {@link NivelesGrandes.Acceso} sobre el cache de disco de una dimensión.
     * Guarda las grillas del horizonte por región ya leídas: un nodo de nivel
     * 5 consulta miles de secciones que caen en la misma grilla.
     */
    private record NodoAproximado(int nivel, int x, int y, int z) {
    }

    private record AccesoStore(RegionFileStore store, byte dimension, Map<NodoAproximado, SuperVoxel[]> aproximadas)
            implements NivelesGrandes.Acceso {

        AccesoStore(RegionFileStore store, byte dimension) {
            this(store, dimension, new HashMap<>());
        }

        /** Grilla vacía: la zona se aproximó y esa banda quedó toda de aire. */
        private static final SuperVoxel[] SIN_GRILLA = new SuperVoxel[0];

        @Override
        public boolean chunkConDatos(int chunkX, int chunkZ) {
            return GeneradorAproximado.tieneLod(store, dimension, chunkX, chunkZ);
        }

        @Override
        public SuperVoxel seccionAproximada(int seccionX, int seccionY, int seccionZ) {
            for (int nivel = TerrenoAproximado.NIVEL_REGION_MIN; nivel <= TerrenoAproximado.NIVEL_REGION_MAX; nivel++) {
                int porNodo = NivelesGrandes.ladoEnSecciones(nivel);
                int nx = Math.floorDiv(seccionX, porNodo), nz = Math.floorDiv(seccionZ, porNodo);
                int ny = Math.floorDiv(seccionY, porNodo);
                NodoAproximado clave = new NodoAproximado(nivel, nx, ny, nz);
                SuperVoxel[] grilla = aproximadas.get(clave);
                if (grilla == null) {
                    RegionFileStore.ClaveRegion region = new RegionFileStore.ClaveRegion(dimension,
                            NivelesGrandes.regionDe(nivel, nx), NivelesGrandes.regionDe(nivel, nz));
                    if (!store.contiene(region, TerrenoAproximado.claveMarcaGrande(nivel, nx, nz))) {
                        continue;
                    }
                    byte[] bytes = store.leer(region, TerrenoAproximado.claveGrande(nivel, nx, ny, nz));
                    grilla = bytes == null ? SIN_GRILLA
                            : OctreeNodeCodec.deserializar(bytes, 0, VOXELES_GRANDE).voxeles();
                    aproximadas.put(clave, grilla);
                }
                return grilla == SIN_GRILLA ? null
                        : TerrenoAproximado.seccionDe(grilla, nivel, seccionX, seccionY, seccionZ);
            }
            return null;
        }
        @Override
        public SuperVoxel[] seccion(int nivel, int seccionX, int seccionY, int seccionZ) {
            RegionFileStore.ClaveRegion region = claveRegion(dimension, seccionX, seccionZ);
            byte[] bytes = store.leer(region, SectionExtractor.claveNodo(nivel, seccionX, seccionY, seccionZ));
            // Sin dato real y chunk nunca generado: el aproximado (si hay) arma el horizonte.
            if (bytes == null && nivel >= TerrenoAproximado.NIVEL_MIN && nivel <= TerrenoAproximado.NIVEL_MAX
                    && !store.contiene(region, claveMarca(seccionX, seccionZ))) {
                bytes = store.leer(region, SectionExtractor.claveNodo(TerrenoAproximado.nivelGuardado(nivel),
                        seccionX, seccionY, seccionZ));
            }
            return bytes == null ? null
                    : OctreeNodeCodec.deserializar(bytes, 0, SectionExtractor.voxelesPorNodo(nivel)).voxeles();
        }

        @Override
        public SuperVoxel[] grande(int nivel, int nodoX, int nodoY, int nodoZ) {
            byte[] bytes = store.leer(regionGrande(nivel, nodoX, nodoZ), NivelesGrandes.clave(nivel, nodoX, nodoY, nodoZ));
            return bytes == null ? null : OctreeNodeCodec.deserializar(bytes, 0, VOXELES_GRANDE).voxeles();
        }

        @Override
        public void guardarGrande(int nivel, int nodoX, int nodoY, int nodoZ, SuperVoxel[] grilla) {
            int lado = NivelesGrandes.ladoEnBloques(nivel);
            OctreeNode nodo = OctreeNode.mixto(nivel, nodoX * lado, nodoY * lado, nodoZ * lado, lado, grilla);
            store.guardar(regionGrande(nivel, nodoX, nodoZ), NivelesGrandes.clave(nivel, nodoX, nodoY, nodoZ),
                    OctreeNodeCodec.serializar(nodo, VOXELES_GRANDE));
        }

        private RegionFileStore.ClaveRegion regionGrande(int nivel, int nodoX, int nodoZ) {
            return new RegionFileStore.ClaveRegion(dimension,
                    NivelesGrandes.regionDe(nivel, nodoX), NivelesGrandes.regionDe(nivel, nodoZ));
        }
    }

    public static final int VOXELES_GRANDE = NivelesGrandes.LADO * NivelesGrandes.LADO * NivelesGrandes.LADO;

    private void encolar(ServerLevel nivel, ChunkAccess chunk) {
        if (!encolarSinPendiente(nivel, chunk)) {
            pendientes.add(new Pendiente(nivel.dimension(), chunk.getPos().toLong()));
        }
    }

    /** @return false si la cola estaba llena y el chunk no se encoló */
    private boolean encolarSinPendiente(ServerLevel nivel, ChunkAccess chunk) {
        if (store == null || scheduler == null) {
            return true;
        }
        long inicioCaptura = System.nanoTime();
        List<LectorSeccionMinecraft.Captura> capturas = LectorSeccionMinecraft.capturar(nivel, chunk);
        nanosCapturaHiloServidor += System.nanoTime() - inicioCaptura;
        RegionFileStore.ClaveRegion region = claveRegion(nivel, chunk);
        long marca = claveMarca(chunk);
        RegionFileStore destino = store;
        int colapsoDesde = calidad.colapsoDesdeNivel();
        Dimension dimension = new Dimension(idDimension(nivel.dimension()), nivel.getMinSection(), nivel.getMaxSection());
        long chunkEmpaquetado = NivelesGrandes.empaquetar(chunk.getPos().x, chunk.getPos().z);

        var tarea = scheduler.intentarEnviar(() -> {
            long inicioTarea = System.nanoTime();
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
            nanosChunks.add(System.nanoTime() - inicioTarea);
            chunksHechos.increment();
            chunksExtraidosTotal.incrementAndGet();
            chunksSucios.computeIfAbsent(dimension, d -> ConcurrentHashMap.newKeySet()).add(chunkEmpaquetado);
            return null;
        });
        if (tarea == null) {
            descartadosPorColaLlena++;
            return false;
        }
        return true;
    }

    /** Calidad con que genera el servidor en curso, o null si no hay servidor corriendo. */
    public ParametrosCalidad calidad() {
        return calidad;
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
