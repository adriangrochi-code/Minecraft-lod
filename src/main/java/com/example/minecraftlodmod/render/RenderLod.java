package com.example.minecraftlodmod.render;

import com.example.minecraftlodmod.config.ConfigLod;
import com.example.minecraftlodmod.config.ParametrosCalidad;
import com.example.minecraftlodmod.generation.GeneradorLocal;
import com.example.minecraftlodmod.generation.NivelesGrandes;
import com.example.minecraftlodmod.core.SuperVoxel;
import com.example.minecraftlodmod.generation.SectionExtractor;
import com.example.minecraftlodmod.storage.OctreeNodeCodec;
import com.example.minecraftlodmod.storage.RegionFileStore;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.example.minecraftlodmod.MinecraftLodMod;
import net.minecraft.client.renderer.ShaderInstance;
import com.mojang.blaze3d.shaders.Uniform;
import com.example.minecraftlodmod.generation.GreedyMesher;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import java.io.IOException;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Render de LOD, backend compatible (sección 6 y 20): primer render
 * FUNCIONAL, sin optimizar — la optimización y el ajuste visual son de
 * Pista B. Solo cliente.
 *
 * Por frame, en la etapa {@code AFTER_SKY}:
 *  1. Sube a GPU hasta {@link #SUBIDAS_POR_FRAME} mallas que terminaron de
 *     armarse en el hilo de mallas.
 *  2. Si la cámara cambió de chunk o de FOV, rehace el {@link PlanCeldas}
 *     y encola las celdas nuevas o cambiadas.
 *  3. Dibuja cada celda con su {@link VertexBuffer} y una proyección propia
 *     con far plane hasta el radio de LOD (la de vanilla corta en 4× la
 *     distancia de render).
 *  4. Limpia la profundidad: el terreno vanilla se dibuja después, siempre
 *     encima del LOD. Es correcto porque el LOD solo cubre lo que vanilla
 *     no dibuja (más lejos), y evita mezclar dos rangos de profundidad.
 *
 * Además estira la niebla de terreno de vanilla hasta el radio de LOD, para
 * que el borde de la distancia de render no se funda con el cielo delante
 * del LOD.
 *
 * Compatibilidad: solo abstracciones de Minecraft ({@link VertexBuffer},
 * {@link BufferBuilder}, {@link RenderSystem}, shader {@code position_color}
 * del juego) — nunca GL crudo (regla de la sección 15).
 *
 * Fuente de datos: por ahora el cache de disco del servidor integrado
 * (singleplayer). En multiplayer el cliente todavía no guarda lo que llega
 * por red ({@code ProtocoloLod}), así que no dibuja nada — anotado en NOTES.
 */
public final class RenderLod {

    private static final Logger LOG = LogUtils.getLogger();

    static final int SUBIDAS_POR_FRAME = 4;
    /** Máximo de celdas encoladas para armar por cada replanificación (las más cercanas primero). */
    static final int ENCOLADAS_POR_PLAN = 48;
    /** Cada cuánto se replanifica aunque la cámara no se mueva (para levantar chunks recién generados). */
    static final long REPLANIFICAR_NANOS = 3_000_000_000L;
    /** Antigüedad a partir de la cual se reconstruye una celda incompleta (le faltaban chunks con datos). */
    static final long RECONSTRUIR_INCOMPLETA_NANOS = 10_000_000_000L;
    /** Profundidad de 24 bits: un near plane lejos mejora mucho la precisión a distancia. */
    static final float NEAR_LOD = 16f;
    static final int BYTES_POR_VERTICE = 16; // POSITION_COLOR: 3 floats + 4 bytes

    /**
     * x, y, z y sprite como 4 shorts enteros ({@code ivec4} en el shader).
     * Uso UV con tipo entero: Minecraft lo sube con glVertexAttribIPointer
     * sin normalizar, igual que sus propios UV1/UV2.
     */
    static final VertexFormatElement POSICION_SPRITE = VertexFormatElement.register(
            VertexFormatElement.findNextId(), 7, VertexFormatElement.Type.SHORT, VertexFormatElement.Usage.UV, 4);

    /**
     * Formato compacto texturizado, 12 bytes por vértice (ver
     * {@link GeometriaLod#escribirCompacto} y shaders/core/lod_textura.vsh):
     * posición + índice de sprite, y color ya sombreado con la cara en el alfa.
     * Se arma con {@link VertexFormat.Builder} y se sube con {@link VertexBuffer}
     * como cualquier formato del juego: sin llamadas GL propias.
     */
    public static final VertexFormat FORMATO_TEXTURA = VertexFormat.builder()
            .add("PosSprite", POSICION_SPRITE)
            .add("Color", VertexFormatElement.COLOR)
            .build();

    /** Shader texturizado, registrado como cualquier ShaderInstance del juego; null si no cargó. */
    private static volatile ShaderInstance shaderTextura;
    /** Sube cada vez que cambian las texturas (resource pack, F3+T): hay que rearmar todo. */
    private static volatile int versionTexturas;
    private int versionTexturasVista = -1;
    private boolean texturasEnUso;

    // Estadísticas para estimar el costo en cada hardware (log cada 10 s).
    static final long PERIODO_ESTADISTICAS_NANOS = 10_000_000_000L;
    private final LongAdder nanosArmado = new LongAdder();
    private final LongAdder mallasArmadas = new LongAdder();
    private long ultimaEstadisticaNanos = System.nanoTime();
    private long framesDesdeEstadistica;
    private long nanosDibujo;
    private int llamadasUltimoFrame;
    private long verticesUltimoFrame;

    private void registrarEstadisticas(Minecraft mc) {
        framesDesdeEstadistica++;
        long ahora = System.nanoTime();
        if (ahora - ultimaEstadisticaNanos < PERIODO_ESTADISTICAS_NANOS) {
            return;
        }
        long vertices = 0, bytesVram = 0;
        int piezas = 0;
        for (EstadoCelda e : celdas.values()) {
            if (e.tieneMalla) {
                piezas++;
                vertices += e.vertices;
                // Los índices de QUADS son un buffer secuencial compartido de Minecraft: no cuentan por malla.
                bytesVram += (long) e.vertices * e.bytesVertice;
            }
        }
        long mallas = mallasArmadas.sumThenReset();
        long nanos = nanosArmado.sumThenReset();
        Runtime rt = Runtime.getRuntime();
        LOG.info("LOD stats: fps={} | dibujo LOD {} ms/frame, {} llamadas/frame, {} vértices dibujados/frame | "
                        + "{} piezas, {} vértices ({} triángulos), VRAM LOD ~{} MB | mallas armadas {} ({} ms prom) "
                        + "| heap {} / {} MB",
                mc.getFps(), String.format("%.2f", nanosDibujo / 1e6 / Math.max(1, framesDesdeEstadistica)),
                llamadasUltimoFrame, verticesUltimoFrame, piezas, vertices, vertices / 2, bytesVram >> 20, mallas,
                mallas == 0 ? 0 : String.format("%.1f", nanos / 1e6 / mallas),
                (rt.totalMemory() - rt.freeMemory()) >> 20, rt.maxMemory() >> 20);
        ultimaEstadisticaNanos = ahora;
        framesDesdeEstadistica = 0;
        nanosDibujo = 0;
    }

    /** Bus del mod, solo cliente. */
    public static void registrarShaders(RegisterShadersEvent evento) {
        try {
            evento.registerShader(new ShaderInstance(evento.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(MinecraftLodMod.MOD_ID, "lod_textura"), FORMATO_TEXTURA),
                    cargado -> shaderTextura = cargado);
        } catch (IOException e) {
            LOG.error("LOD: no se pudo cargar el shader de texturas; se dibuja con colores planos", e);
            shaderTextura = null;
        }
    }

    /** Lo avisa {@link PaletaTexturas} al recalcular la tabla con el atlas nuevo. */
    static void texturasCambiaron() {
        versionTexturas++;
    }

    private final GeneradorLocal generador;
    /**
     * Un hilo con cola de PRIORIDAD por distancia a la cámara: las celdas se
     * arman del centro (el jugador) hacia afuera, aunque una lejana se haya
     * encolado antes. Solo {@code execute()}: {@code submit()} envolvería la
     * tarea en algo no comparable.
     */
    private final ExecutorService hiloMallas = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new PriorityBlockingQueue<>(), r -> {
        Thread hilo = new Thread(r, "LOD-Mallas");
        hilo.setDaemon(true);
        hilo.setPriority(Thread.MIN_PRIORITY);
        return hilo;
    });
    private final AtomicLong secuenciaTareas = new AtomicLong();

    /** Tarea de armado ordenada por distancia a la cámara al encolarse (y por orden de llegada si empatan). */
    private record TareaMalla(double distancia2, long secuencia, Runnable accion)
            implements Runnable, Comparable<TareaMalla> {
        @Override
        public void run() {
            accion.run();
        }

        @Override
        public int compareTo(TareaMalla otra) {
            int porDistancia = Double.compare(distancia2, otra.distancia2);
            return porDistancia != 0 ? porDistancia : Long.compare(secuencia, otra.secuencia);
        }
    }

    // Todo lo que sigue, salvo las colas y los volatile, es del hilo de render.
    private final Map<Long, EstadoCelda> celdas = new HashMap<>();
    private final ConcurrentLinkedQueue<MallaLista> listas = new ConcurrentLinkedQueue<>();
    private volatile double fovGrados = 70;
    private volatile ParametrosCalidad calidad;
    private int chunkPlanX = Integer.MIN_VALUE, chunkPlanZ = Integer.MIN_VALUE;
    private double fovPlan;
    private long ultimoPlanNanos;
    private ClientLevel nivelActual;

    private static final class EstadoCelda {
        PlanCeldas.Celda plan;
        PlanCeldas.Celda construidaCon;
        /** Un buffer por dirección de cara (GeometriaLod.CARAS), null si no tiene caras. */
        final VertexBuffer[] buffers = new VertexBuffer[GeometriaLod.CARAS];
        /** Planos extremos de cada grupo: [2*cara] mínimo, [2*cara+1] máximo. */
        float[] planos;
        final int[] verticesCara = new int[GeometriaLod.CARAS];
        boolean tieneMalla;
        int vertices;
        int bytesVertice;
        boolean enConstruccion;
        int chunksConDatos;
        boolean texturizada;
        long construidaNanos;
    }

    /**
     * Resultado del hilo de mallas: una malla por dirección de cara (null las
     * vacías). {@code mallas} y {@code memoria} null = nada que dibujar.
     */
    private record MallaLista(long clave, PlanCeldas.Celda celda, MeshData[] mallas, float[] planos,
                              ByteBufferBuilder memoria, int chunksConDatos, boolean texturizada) {
    }

    public RenderLod(GeneradorLocal generador) {
        this.generador = generador;
    }

    /** Calidad a usar; la calibración la cambia por escalón ({@code SesionCalibracion.asignarAplicador}). */
    public void aplicarCalidad(ParametrosCalidad nueva) {
        calidad = nueva;
        chunkPlanX = Integer.MIN_VALUE; // forzar replanificación
    }

    /** FOV efectivo, leído al final para incluir lo que hayan cambiado los mods de zoom (sección 3). */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void alCalcularFov(ViewportEvent.ComputeFov evento) {
        if (evento.usedConfiguredFov()) {
            fovGrados = evento.getFOV();
        }
    }

    @SubscribeEvent
    public void alCalcularNiebla(ViewportEvent.RenderFog evento) {
        ParametrosCalidad c = calidad;
        if (c == null || celdas.isEmpty() || evento.getMode() != FogRenderer.FogMode.FOG_TERRAIN
                || evento.getType() != FogType.NONE) {
            return; // bajo el agua, en lava o con ceguera se respeta la niebla de vanilla
        }
        float finLod = c.radioLodChunks() * 16f;
        if (finLod > evento.getFarPlaneDistance()) {
            evento.setNearPlaneDistance(finLod * 0.8f);
            evento.setFarPlaneDistance(finLod);
            evento.setCanceled(true); // en NeoForge, cancelar es lo que aplica los valores nuevos
        }
    }

    @SubscribeEvent
    public void alDescargarNivel(LevelEvent.Unload evento) {
        if (evento.getLevel() == nivelActual) {
            liberarTodo();
        }
    }

    @SubscribeEvent
    public void alRenderizar(RenderLevelStageEvent evento) {
        if (evento.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        RegionFileStore store = generador.store();
        if (mc.level == null || store == null) {
            return;
        }
        if (mc.level != nivelActual) {
            liberarTodo();
            nivelActual = mc.level;
            calidad = ConfigLod.calidadCliente();
            LOG.info("LOD: render activo ({} chunks, umbral {} px)", calidad.radioLodChunks(), calidad.umbralPx());
        }

        // Cambiaron las texturas o se prendió/apagó la opción: rearmar todas las
        // celdas (las viejas se siguen dibujando hasta que llegue su reemplazo).
        boolean usarTexturas = shaderTextura != null && PaletaTexturas.tabla() != null
                && ConfigLod.CLIENTE.texturasLod.get();
        if (versionTexturas != versionTexturasVista || usarTexturas != texturasEnUso) {
            versionTexturasVista = versionTexturas;
            texturasEnUso = usarTexturas;
            LOG.info("LOD: texturas {} (shader {}, tabla {}, opción {})", usarTexturas ? "activas" : "apagadas",
                    shaderTextura != null ? "ok" : "sin cargar", PaletaTexturas.tabla() != null ? "ok" : "sin calcular",
                    ConfigLod.CLIENTE.texturasLod.get());
            celdas.values().forEach(e -> e.construidaCon = null);
            chunkPlanX = Integer.MIN_VALUE;
        }
        subirMallasListas();
        Camera camara = evento.getCamera();
        replanificarSiHaceFalta(mc, camara.getPosition(), store);
        dibujar(mc, evento, camara.getPosition());
        registrarEstadisticas(mc);
    }

    private void subirMallasListas() {
        for (int i = 0; i < SUBIDAS_POR_FRAME; i++) {
            MallaLista lista = listas.poll();
            if (lista == null) {
                return;
            }
            EstadoCelda estado = celdas.get(lista.clave());
            if (estado == null) {
                cerrar(lista);
                continue;
            }
            if (lista.mallas() == null) {
                cerrarBuffer(estado);
            } else {
                estado.vertices = 0;
                for (int cara = 0; cara < GeometriaLod.CARAS; cara++) {
                    MeshData malla = lista.mallas()[cara];
                    estado.verticesCara[cara] = malla == null ? 0 : malla.drawState().vertexCount();
                    if (malla == null) {
                        if (estado.buffers[cara] != null) {
                            estado.buffers[cara].close();
                            estado.buffers[cara] = null;
                        }
                        continue;
                    }
                    if (estado.buffers[cara] == null) {
                        estado.buffers[cara] = new VertexBuffer(VertexBuffer.Usage.STATIC);
                    }
                    estado.vertices += malla.drawState().vertexCount();
                    estado.bytesVertice = malla.drawState().format().getVertexSize();
                    estado.buffers[cara].bind();
                    estado.buffers[cara].upload(malla); // cierra el MeshData
                }
                VertexBuffer.unbind();
                estado.planos = lista.planos();
                estado.tieneMalla = true;
            }
            if (lista.memoria() != null) {
                lista.memoria().close();
            }
            estado.construidaCon = lista.celda();
            estado.chunksConDatos = lista.chunksConDatos();
            estado.texturizada = lista.texturizada();
            estado.construidaNanos = System.nanoTime();
            estado.enConstruccion = false;
        }
    }

    private void replanificarSiHaceFalta(Minecraft mc, Vec3 camara, RegionFileStore store) {
        int chunkX = (int) Math.floor(camara.x / 16);
        int chunkZ = (int) Math.floor(camara.z / 16);
        long ahora = System.nanoTime();
        if (chunkX == chunkPlanX && chunkZ == chunkPlanZ && Math.abs(fovGrados - fovPlan) < 1
                && ahora - ultimoPlanNanos < REPLANIFICAR_NANOS) {
            return;
        }
        chunkPlanX = chunkX;
        chunkPlanZ = chunkZ;
        fovPlan = fovGrados;
        ultimoPlanNanos = ahora;

        ParametrosCalidad c = calidad;
        // Vanilla dibuja hasta su distancia de render; se deja un chunk de
        // solapamiento para que no queden huecos en el borde (vanilla queda encima).
        int distanciaVanilla = Math.max(0, mc.options.getEffectiveRenderDistance() - 1);
        List<PlanCeldas.Celda> plan = PlanCeldas.planificarConGrandes(camara.x, camara.z, c.radioLodChunks(),
                distanciaVanilla, Math.toRadians(fovGrados), mc.getWindow().getHeight(), c.umbralPx());
        plan.sort(Comparator.comparingDouble(celda -> distancia2(celda, camara)));

        Set<Long> vigentes = new HashSet<>();
        int encoladas = 0;
        byte dimension = GeneradorLocal.idDimension(mc.level.dimension());
        GeometriaLod.Texturas texturas = texturasEnUso ? PaletaTexturas.tabla() : null;
        int minSeccion = mc.level.getMinSection();
        int maxSeccion = mc.level.getMaxSection();
        for (PlanCeldas.Celda celda : plan) {
            long clave = clave(celda);
            vigentes.add(clave);
            EstadoCelda estado = celdas.computeIfAbsent(clave, k -> new EstadoCelda());
            estado.plan = celda;
            boolean cambio = !celda.equals(estado.construidaCon);
            boolean incompleta = estado.construidaCon != null
                    && estado.chunksConDatos < chunksDibujables(celda)
                    && ahora - estado.construidaNanos > RECONSTRUIR_INCOMPLETA_NANOS;
            if (!estado.enConstruccion && (cambio || incompleta) && encoladas < ENCOLADAS_POR_PLAN) {
                estado.enConstruccion = true;
                encoladas++;
                hiloMallas.execute(new TareaMalla(distancia2(celda, camara), secuenciaTareas.incrementAndGet(),
                        () -> armar(clave, celda, store, dimension, minSeccion, maxSeccion,
                                chunkX, chunkZ, distanciaVanilla, texturas)));
            }
        }
        celdas.entrySet().removeIf(e -> {
            if (!vigentes.contains(e.getKey())) {
                cerrarBuffer(e.getValue());
                return true;
            }
            return false;
        });
    }

    /** Hilo de mallas: lee los nodos del nivel elegido y arma los vértices de la celda. */
    private void armar(long clave, PlanCeldas.Celda celda, RegionFileStore store, byte dimension,
                       int minSeccion, int maxSeccion, int chunkCamX, int chunkCamZ, int distanciaVanilla,
                       GeometriaLod.Texturas texturas) {
        long inicioArmado = System.nanoTime();
        try {
            GeometriaLod geometria = new GeometriaLod();
            geometria.usarTexturas(texturas);
            geometria.descartarCarasSinLuz(ConfigLod.CLIENTE.descartarCuevas.get());
            int conDatos = celda.esGrande()
                    ? armarTesela(geometria, celda, store, dimension, minSeccion, maxSeccion)
                    : armarCelda(geometria, celda, store, dimension, minSeccion, maxSeccion,
                    chunkCamX, chunkCamZ, distanciaVanilla);
            if (geometria.vertices() == 0) {
                listas.add(new MallaLista(clave, celda, null, null, null, conDatos, false));
                return;
            }
            boolean texturizada = texturas != null;
            VertexFormat formato = texturizada ? FORMATO_TEXTURA : DefaultVertexFormat.POSITION_COLOR;
            // Toda la memoria de una vez: las 6 mallas salen del mismo bloque, sin realocar.
            ByteBufferBuilder memoria = new ByteBufferBuilder(geometria.vertices() * formato.getVertexSize());
            MeshData[] mallas = new MeshData[GeometriaLod.CARAS];
            float[] planos = new float[2 * GeometriaLod.CARAS];
            for (int cara = 0; cara < GeometriaLod.CARAS; cara++) {
                planos[2 * cara] = geometria.planoMin(cara);
                planos[2 * cara + 1] = geometria.planoMax(cara);
                int n = geometria.verticesDeCara(cara);
                if (n > 0) {
                    mallas[cara] = malla(geometria, cara, n, formato, memoria, texturizada);
                }
            }
            listas.add(new MallaLista(clave, celda, mallas, planos, memoria, conDatos, texturizada));
            nanosArmado.add(System.nanoTime() - inicioArmado);
            mallasArmadas.increment();
        } catch (RuntimeException e) {
            LOG.error("LOD: no se pudo armar la celda {},{}", celda.celdaX(), celda.celdaZ(), e);
            listas.add(new MallaLista(clave, celda, null, null, null, 0, false));
        }
    }

    private static MeshData malla(GeometriaLod geometria, int cara, int n, VertexFormat formato,
                                  ByteBufferBuilder memoria, boolean texturizada) {
        if (texturizada) {
            // Formato propio: BufferBuilder exige POSITION en float, así que los
            // bytes se escriben directo y se envuelven en un MeshData como el suyo.
            int bytes = n * GeometriaLod.BYTES_COMPACTO;
            geometria.escribirCompacto(MemoryUtil.memByteBuffer(memoria.reserve(bytes), bytes), cara);
            return new MeshData(memoria.build(), new MeshData.DrawState(formato, n,
                    VertexFormat.Mode.QUADS.indexCount(n), VertexFormat.Mode.QUADS, VertexFormat.IndexType.least(n)));
        }
        BufferBuilder builder = new BufferBuilder(memoria, VertexFormat.Mode.QUADS, formato);
        for (int i = 0; i < geometria.vertices(); i++) {
            if (geometria.cara(i) == cara) {
                builder.addVertex(geometria.x(i), geometria.y(i), geometria.z(i)).setColor(geometria.color(i));
            }
        }
        return builder.buildOrThrow();
    }

    /**
     * Celda de 4×4 chunks: cada sección se malla con sus vecinas del mismo
     * nivel (arriba, abajo y los chunks de al lado) para no generar las
     * caras que tapan. Los nodos se decodifican una sola vez por armado.
     *
     * @return chunks con datos
     */
    private static int armarCelda(GeometriaLod geometria, PlanCeldas.Celda celda, RegionFileStore store,
                                  byte dimension, int minSeccion, int maxSeccion,
                                  int chunkCamX, int chunkCamZ, int distanciaVanilla) {
        int nivel = celda.nivel();
        int lado = SectionExtractor.LADO >> nivel;
        int total = SectionExtractor.voxelesPorNodo(nivel);
        Map<PosSeccion, SuperVoxel[]> nodos = new HashMap<>();
        java.util.function.Function<PosSeccion, SuperVoxel[]> leerNodo = pos -> nodos.computeIfAbsent(pos, k -> {
            byte[] bytes = store.leer(GeneradorLocal.claveRegion(dimension, k.x(), k.z()),
                    SectionExtractor.claveNodo(nivel, k.x(), k.y(), k.z()));
            return bytes == null ? SIN_NODO : OctreeNodeCodec.deserializar(bytes, 0, total).voxeles();
        });
        int conDatos = 0;
        for (int dx = 0; dx < PlanCeldas.LADO_CELDA; dx++) {
            for (int dz = 0; dz < PlanCeldas.LADO_CELDA; dz++) {
                if (celda.omitido(dx, dz)) {
                    continue;
                }
                int chunkX = celda.celdaX() * PlanCeldas.LADO_CELDA + dx;
                int chunkZ = celda.celdaZ() * PlanCeldas.LADO_CELDA + dz;
                if (!tieneDatos(store, dimension, chunkX, chunkZ)) {
                    continue;
                }
                conDatos++;
                // Donde el vecino lo dibuja vanilla se omite todo el costado; donde es
                // LOD, deciden sus vóxeles (null = sin datos: el corte se ve, es real).
                int omitidas = 0;
                boolean[] lod = new boolean[4];
                int[][] lados = {{-1, 0, GeometriaLod.OMITIR_X_NEG}, {1, 0, GeometriaLod.OMITIR_X_POS},
                        {0, -1, GeometriaLod.OMITIR_Z_NEG}, {0, 1, GeometriaLod.OMITIR_Z_POS}};
                for (int l = 0; l < 4; l++) {
                    int vx = chunkX + lados[l][0], vz = chunkZ + lados[l][1];
                    if (dentroDeVanilla(vx, vz, chunkCamX, chunkCamZ, distanciaVanilla)) {
                        omitidas |= lados[l][2];
                    } else {
                        lod[l] = tieneDatos(store, dimension, vx, vz);
                    }
                }
                for (int sy = minSeccion; sy < maxSeccion; sy++) {
                    SuperVoxel[] grid = leerNodo.apply(new PosSeccion(chunkX, sy, chunkZ));
                    if (grid == SIN_NODO) {
                        continue;
                    }
                    GreedyMesher.Vecinos vecinos = GreedyMesher.Vecinos.deGrillas(lado,
                            lod[0] ? existente(leerNodo.apply(new PosSeccion(chunkX - 1, sy, chunkZ))) : null,
                            lod[1] ? existente(leerNodo.apply(new PosSeccion(chunkX + 1, sy, chunkZ))) : null,
                            sy > minSeccion ? existente(leerNodo.apply(new PosSeccion(chunkX, sy - 1, chunkZ))) : null,
                            sy + 1 < maxSeccion ? existente(leerNodo.apply(new PosSeccion(chunkX, sy + 1, chunkZ))) : null,
                            lod[2] ? existente(leerNodo.apply(new PosSeccion(chunkX, sy, chunkZ - 1))) : null,
                            lod[3] ? existente(leerNodo.apply(new PosSeccion(chunkX, sy, chunkZ + 1))) : null);
                    geometria.agregarSeccion(grid, lado, dx * 16f, sy * 16f, dz * 16f, 16f / lado,
                            omitidas, vecinos);
                }
            }
        }
        return conDatos;
    }

    /** Marca de "nodo sin datos" en el memo de {@link #armarCelda} (computeIfAbsent no guarda null). */
    private static final SuperVoxel[] SIN_NODO = new SuperVoxel[0];

    private static SuperVoxel[] existente(SuperVoxel[] nodo) {
        return nodo == SIN_NODO ? null : nodo;
    }

    /** Sección (chunk x, sección y, chunk z): clave del memo de un armado. */
    private record PosSeccion(int x, int y, int z) {
    }

    private static boolean dentroDeVanilla(int chunkX, int chunkZ, int chunkCamX, int chunkCamZ,
                                           int distanciaVanilla) {
        long dx = chunkX - chunkCamX, dz = chunkZ - chunkCamZ;
        return dx * dx + dz * dz < (long) distanciaVanilla * distanciaVanilla;
    }

    private static boolean tieneDatos(RegionFileStore store, byte dimension, int chunkX, int chunkZ) {
        return store.contiene(GeneradorLocal.claveRegion(dimension, chunkX, chunkZ),
                GeneradorLocal.claveMarca(chunkX, chunkZ));
    }

    private void dibujar(Minecraft mc, RenderLevelStageEvent evento, Vec3 camara) {
        long inicio = System.nanoTime();
        try {
            dibujarLod(mc, evento, camara);
        } finally {
            nanosDibujo += System.nanoTime() - inicio;
        }
    }

    private void dibujarLod(Minecraft mc, RenderLevelStageEvent evento, Vec3 camara) {
        llamadasUltimoFrame = 0;
        verticesUltimoFrame = 0;
        ParametrosCalidad c = calidad;
        float far = Math.max(NEAR_LOD * 2, c.radioLodChunks() * 16f * 1.5f);
        Matrix4f proyeccion = new Matrix4f().perspective((float) Math.toRadians(fovGrados),
                (float) mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight()), NEAR_LOD, far);

        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        // Todas las caras son antihorarias vistas desde afuera (GeometriaLod): culling normal.
        RenderSystem.enableCull();
        dibujarPasada(evento, camara, proyeccion, false, GameRenderer.getPositionColorShader());
        ShaderInstance conTextura = shaderTextura;
        if (conTextura != null) {
            // Atlas de bloques activo, con mipmaps: la textura se simplifica sola con la distancia.
            mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).setFilter(false, true);
            RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_BLOCKS);
            RenderSystem.setShaderTexture(1, PaletaTexturas.TABLA_SPRITES);
            dibujarPasada(evento, camara, proyeccion, true, conTextura);
        }
        VertexBuffer.unbind();
        RenderSystem.enableCull();
        // GL_DEPTH_BUFFER_BIT: el terreno vanilla se dibuja después, siempre delante del LOD.
        RenderSystem.clear(256, Minecraft.ON_OSX);
    }

    /**
     * Una pasada (plana o texturizada). Con el shader propio se hace como el
     * terreno vanilla: uniforms y shader una sola vez, y por buffer solo el
     * desplazamiento de la celda ({@code ChunkOffset}) y el draw; así los
     * buffers por dirección no multiplican el costo de CPU. El shader
     * {@code position_color} de vanilla no tiene ChunkOffset: ahí se usa
     * drawWithShader con la matriz desplazada.
     */
    private void dibujarPasada(RenderLevelStageEvent evento, Vec3 camara, Matrix4f proyeccion,
                               boolean texturizadas, ShaderInstance shader) {
        if (shader == null) {
            return;
        }
        RenderSystem.setShader(() -> shader);
        Uniform desplazamiento = shader.CHUNK_OFFSET;
        if (desplazamiento != null) {
            shader.setDefaultUniforms(VertexFormat.Mode.QUADS, evento.getModelViewMatrix(), proyeccion,
                    Minecraft.getInstance().getWindow());
            shader.apply();
        }
        for (EstadoCelda estado : celdas.values()) {
            if (!estado.tieneMalla || estado.construidaCon == null || estado.texturizada != texturizadas) {
                continue;
            }
            double origenX = estado.construidaCon.origenX(), origenZ = estado.construidaCon.origenZ();
            float ox = (float) (origenX - camara.x);
            float oz = (float) (origenZ - camara.z);
            Matrix4f vista = null;
            if (desplazamiento != null) {
                desplazamiento.set(ox, (float) -camara.y, oz);
                desplazamiento.upload();
            } else {
                vista = new Matrix4f(evento.getModelViewMatrix()).translate(ox, (float) -camara.y, oz);
            }
            for (int cara = 0; cara < GeometriaLod.CARAS; cara++) {
                VertexBuffer buffer = estado.buffers[cara];
                double camaraEnEje = switch (cara >> 1) {
                    case 0 -> camara.x - origenX;
                    case 1 -> camara.y;
                    default -> camara.z - origenZ;
                };
                if (buffer == null || !GeometriaLod.caraVisible(cara, camaraEnEje,
                        estado.planos[2 * cara], estado.planos[2 * cara + 1])) {
                    continue;
                }
                buffer.bind();
                if (desplazamiento != null) {
                    buffer.draw();
                } else {
                    buffer.drawWithShader(vista, proyeccion, shader);
                }
                llamadasUltimoFrame++;
                verticesUltimoFrame += estado.verticesCara[cara];
            }
        }
        if (desplazamiento != null) {
            desplazamiento.set(0f, 0f, 0f);
            shader.clear();
        }
    }

    /**
     * Tesela del quadtree: por cada banda vertical, una grilla de 16³
     * vóxeles de 2^nivel bloques — leída de {@link NivelesGrandes} (nivel
     * ≥ 5) o armada con los nodos por sección del mismo nivel (3 y 4).
     * Una sola grilla por banda también evita paredes entre secciones.
     *
     * @return bandas con datos
     */
    private static int armarTesela(GeometriaLod geometria, PlanCeldas.Celda tesela, RegionFileStore store,
                                   byte dimension, int minSeccion, int maxSeccion) {
        int nivel = tesela.nivel();
        int seccionesPorLado = NivelesGrandes.ladoEnSecciones(nivel);
        int primera = Math.floorDiv(minSeccion, seccionesPorLado);
        int ultima = Math.floorDiv(maxSeccion - 1, seccionesPorLado);
        SuperVoxel[][] grillas = new SuperVoxel[ultima - primera + 1][];
        for (int banda = primera; banda <= ultima; banda++) {
            grillas[banda - primera] = nivel >= NivelesGrandes.NIVEL_MIN
                    ? leerGrande(store, dimension, nivel, tesela.celdaX(), banda, tesela.celdaZ())
                    : desdeSecciones(store, dimension, nivel, tesela.celdaX(), banda, tesela.celdaZ(),
                    minSeccion, maxSeccion);
        }
        int conDatos = 0;
        for (int b = 0; b < grillas.length; b++) {
            if (grillas[b] == null) {
                continue;
            }
            conDatos++;
            // Solo las bandas de arriba y abajo como vecinas: los costados de la
            // tesela todavía se dibujan siempre (paredes de borde, ver NOTES.md).
            GreedyMesher.Vecinos vecinos = GreedyMesher.Vecinos.deGrillas(NivelesGrandes.LADO, null, null,
                    b > 0 ? grillas[b - 1] : null, b + 1 < grillas.length ? grillas[b + 1] : null, null, null);
            geometria.agregarSeccion(grillas[b], NivelesGrandes.LADO, 0, (primera + b) * seccionesPorLado * 16f, 0,
                    1 << nivel, 0, vecinos);
        }
        return conDatos;
    }

    private static SuperVoxel[] leerGrande(RegionFileStore store, byte dimension, int nivel, int x, int banda, int z) {
        byte[] bytes = store.leer(new RegionFileStore.ClaveRegion(dimension,
                NivelesGrandes.regionDe(nivel, x), NivelesGrandes.regionDe(nivel, z)),
                NivelesGrandes.clave(nivel, x, banda, z));
        return bytes == null ? null
                : OctreeNodeCodec.deserializar(bytes, 0, GeneradorLocal.VOXELES_GRANDE).voxeles();
    }

    /** Niveles 3-4: las (2^nivel)³ secciones de la tesela, cada una con su grilla de 16>>nivel. */
    private static SuperVoxel[] desdeSecciones(RegionFileStore store, byte dimension, int nivel, int teselaX,
                                               int banda, int teselaZ, int minSeccion, int maxSeccion) {
        int porLado = NivelesGrandes.ladoEnSecciones(nivel);
        int ladoSeccion = SectionExtractor.LADO >> nivel;
        int total = SectionExtractor.voxelesPorNodo(nivel);
        int lado = NivelesGrandes.LADO;
        SuperVoxel[] grilla = new SuperVoxel[lado * lado * lado];
        java.util.Arrays.fill(grilla, AIRE);
        boolean alguno = false;
        for (int sy = 0; sy < porLado; sy++) {
            int seccionY = banda * porLado + sy;
            if (seccionY < minSeccion || seccionY >= maxSeccion) {
                continue;
            }
            for (int sx = 0; sx < porLado; sx++) {
                for (int sz = 0; sz < porLado; sz++) {
                    int seccionX = teselaX * porLado + sx, seccionZ = teselaZ * porLado + sz;
                    byte[] bytes = store.leer(GeneradorLocal.claveRegion(dimension, seccionX, seccionZ),
                            SectionExtractor.claveNodo(nivel, seccionX, seccionY, seccionZ));
                    if (bytes == null) {
                        continue;
                    }
                    SuperVoxel[] nodo = OctreeNodeCodec.deserializar(bytes, 0, total).voxeles();
                    alguno = true;
                    for (int x = 0; x < ladoSeccion; x++) {
                        for (int y = 0; y < ladoSeccion; y++) {
                            for (int z = 0; z < ladoSeccion; z++) {
                                grilla[((sx * ladoSeccion + x) * lado + sy * ladoSeccion + y) * lado
                                        + sz * ladoSeccion + z] = nodo[(x * ladoSeccion + y) * ladoSeccion + z];
                            }
                        }
                    }
                }
            }
        }
        return alguno ? grilla : null;
    }

    private static final SuperVoxel AIRE =
            new SuperVoxel((byte) 0, (byte) 0, (byte) 0, (byte) 0, SuperVoxel.Material.AIRE, (byte) 0);

    private static int chunksDibujables(PlanCeldas.Celda celda) {
        if (celda.esGrande()) {
            return 1; // "incompleta" = ninguna banda con datos todavía
        }
        return PlanCeldas.LADO_CELDA * PlanCeldas.LADO_CELDA - Integer.bitCount(celda.mascaraOmitidos());
    }

    private static double distancia2(PlanCeldas.Celda celda, Vec3 camara) {
        double mitad = celda.ladoEnBloques() / 2.0;
        double cx = celda.origenX() + mitad - camara.x;
        double cz = celda.origenZ() + mitad - camara.z;
        return cx * cx + cz * cz;
    }

    /** Teselas y celdas en espacios de clave distintos (bit 62), y teselas separadas por nivel. */
    private static long clave(PlanCeldas.Celda celda) {
        if (celda.esGrande()) {
            return (1L << 62) | ((long) celda.nivel() << 56)
                    | ((long) (celda.celdaX() & 0x0FFFFFFF) << 28) | (celda.celdaZ() & 0x0FFFFFFF);
        }
        return ((long) (celda.celdaX() & 0x3FFFFFFF) << 30) | (celda.celdaZ() & 0x3FFFFFFF);
    }

    private static void cerrarBuffer(EstadoCelda estado) {
        for (int cara = 0; cara < GeometriaLod.CARAS; cara++) {
            if (estado.buffers[cara] != null) {
                estado.buffers[cara].close();
                estado.buffers[cara] = null;
            }
        }
        estado.tieneMalla = false;
    }

    private static void cerrar(MallaLista lista) {
        if (lista.mallas() != null) {
            for (MeshData malla : lista.mallas()) {
                if (malla != null) {
                    malla.close();
                }
            }
        }
        if (lista.memoria() != null) {
            lista.memoria().close();
        }
    }

    private void liberarTodo() {
        celdas.values().forEach(RenderLod::cerrarBuffer);
        celdas.clear();
        MallaLista lista;
        while ((lista = listas.poll()) != null) {
            cerrar(lista);
        }
        chunkPlanX = Integer.MIN_VALUE;
        nivelActual = null;
    }
}
