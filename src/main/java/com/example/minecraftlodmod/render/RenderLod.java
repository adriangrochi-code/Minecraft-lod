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
     * Formato texturizado (ver shaders/core/lod_textura.vsh): posición, color
     * plano de la cara, esquina de la textura en el atlas (UV0), promedio de
     * la textura empaquetado (UV1) y tamaño de la textura (UV2), normal.
     * Solo elementos estándar de Minecraft: sin atributos propios.
     */
    public static final VertexFormat FORMATO_TEXTURA = VertexFormat.builder()
            .add("Position", VertexFormatElement.POSITION)
            .add("Color", VertexFormatElement.COLOR)
            .add("UV0", VertexFormatElement.UV0)
            .add("UV1", VertexFormatElement.UV1)
            .add("UV2", VertexFormatElement.UV2)
            .add("Normal", VertexFormatElement.NORMAL)
            .padding(1)
            .build();
    static final int ESCALA_TAMANO_UV = 32768;

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

    private void registrarEstadisticas(Minecraft mc) {
        framesDesdeEstadistica++;
        long ahora = System.nanoTime();
        if (ahora - ultimaEstadisticaNanos < PERIODO_ESTADISTICAS_NANOS) {
            return;
        }
        long vertices = 0, bytesVram = 0;
        int piezas = 0;
        for (EstadoCelda e : celdas.values()) {
            if (e.buffer != null) {
                piezas++;
                vertices += e.vertices;
                bytesVram += (long) e.vertices * e.bytesVertice + (long) e.vertices / 4 * 6 * 4; // + índices
            }
        }
        long mallas = mallasArmadas.sumThenReset();
        long nanos = nanosArmado.sumThenReset();
        Runtime rt = Runtime.getRuntime();
        LOG.info("LOD stats: fps={} | dibujo LOD {} ms/frame, {} llamadas/frame | {} piezas, {} vértices "
                        + "({} triángulos), VRAM LOD ~{} MB | mallas armadas {} ({} ms prom) | heap {} / {} MB",
                mc.getFps(), String.format("%.2f", nanosDibujo / 1e6 / Math.max(1, framesDesdeEstadistica)),
                llamadasUltimoFrame, piezas, vertices, vertices / 2, bytesVram >> 20, mallas,
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
        VertexBuffer buffer;
        int vertices;
        int bytesVertice;
        boolean enConstruccion;
        int chunksConDatos;
        boolean texturizada;
        long construidaNanos;
    }

    /** Resultado del hilo de mallas; {@code malla} y {@code memoria} null = la celda no tiene nada que dibujar. */
    private record MallaLista(long clave, PlanCeldas.Celda celda, MeshData malla, ByteBufferBuilder memoria,
                              int chunksConDatos, boolean texturizada) {
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
            if (lista.malla() == null) {
                cerrarBuffer(estado);
            } else {
                if (estado.buffer == null) {
                    estado.buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
                }
                estado.vertices = lista.malla().drawState().vertexCount();
                estado.bytesVertice = lista.malla().drawState().format().getVertexSize();
                estado.buffer.bind();
                estado.buffer.upload(lista.malla()); // cierra el MeshData
                VertexBuffer.unbind();
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
            int nivel = celda.nivel();
            int lado = SectionExtractor.LADO >> nivel;
            int total = SectionExtractor.voxelesPorNodo(nivel);
            int conDatos = celda.esGrande() ? armarTesela(geometria, celda, store, dimension, minSeccion, maxSeccion) : 0;
            for (int dx = 0; dx < PlanCeldas.LADO_CELDA && !celda.esGrande(); dx++) {
                for (int dz = 0; dz < PlanCeldas.LADO_CELDA; dz++) {
                    if (celda.omitido(dx, dz)) {
                        continue;
                    }
                    int chunkX = celda.celdaX() * PlanCeldas.LADO_CELDA + dx;
                    int chunkZ = celda.celdaZ() * PlanCeldas.LADO_CELDA + dz;
                    RegionFileStore.ClaveRegion region = GeneradorLocal.claveRegion(dimension, chunkX, chunkZ);
                    if (!store.contiene(region, GeneradorLocal.claveMarca(chunkX, chunkZ))) {
                        continue;
                    }
                    conDatos++;
                    int omitidas = 0;
                    if (vecinoCubierto(store, dimension, chunkX - 1, chunkZ, chunkCamX, chunkCamZ, distanciaVanilla)) {
                        omitidas |= GeometriaLod.OMITIR_X_NEG;
                    }
                    if (vecinoCubierto(store, dimension, chunkX + 1, chunkZ, chunkCamX, chunkCamZ, distanciaVanilla)) {
                        omitidas |= GeometriaLod.OMITIR_X_POS;
                    }
                    if (vecinoCubierto(store, dimension, chunkX, chunkZ - 1, chunkCamX, chunkCamZ, distanciaVanilla)) {
                        omitidas |= GeometriaLod.OMITIR_Z_NEG;
                    }
                    if (vecinoCubierto(store, dimension, chunkX, chunkZ + 1, chunkCamX, chunkCamZ, distanciaVanilla)) {
                        omitidas |= GeometriaLod.OMITIR_Z_POS;
                    }
                    for (int sy = minSeccion; sy < maxSeccion; sy++) {
                        byte[] bytes = store.leer(region, SectionExtractor.claveNodo(nivel, chunkX, sy, chunkZ));
                        if (bytes == null) {
                            continue;
                        }
                        var nodo = OctreeNodeCodec.deserializar(bytes, 0, total);
                        geometria.agregarSeccion(nodo.voxeles(), lado, dx * 16f, sy * 16f, dz * 16f, 16f / lado,
                                omitidas);
                    }
                }
            }
            if (geometria.vertices() == 0) {
                listas.add(new MallaLista(clave, celda, null, null, conDatos, false));
                return;
            }
            boolean texturizada = texturas != null;
            VertexFormat formato = texturizada ? FORMATO_TEXTURA : DefaultVertexFormat.POSITION_COLOR;
            ByteBufferBuilder memoria = new ByteBufferBuilder(geometria.vertices() * formato.getVertexSize());
            BufferBuilder builder = new BufferBuilder(memoria, VertexFormat.Mode.QUADS, formato);
            for (int i = 0; i < geometria.vertices(); i++) {
                var vertice = builder.addVertex(geometria.x(i), geometria.y(i), geometria.z(i))
                        .setColor(geometria.color(i));
                if (texturizada) {
                    int promedio = geometria.promedioTextura(i);
                    vertice.setUv(geometria.u0(i), geometria.v0(i))
                            .setUv1((promedio >> 8) & 0xFFFF, promedio & 0xFF)
                            .setUv2(Math.round(geometria.du(i) * ESCALA_TAMANO_UV),
                                    Math.round(geometria.dv(i) * ESCALA_TAMANO_UV))
                            .setNormal(geometria.normalX(i) / 127f, geometria.normalY(i) / 127f,
                                    geometria.normalZ(i) / 127f);
                }
            }
            listas.add(new MallaLista(clave, celda, builder.buildOrThrow(), memoria, conDatos, texturizada));
            nanosArmado.add(System.nanoTime() - inicioArmado);
            mallasArmadas.increment();
        } catch (RuntimeException e) {
            LOG.error("LOD: no se pudo armar la celda {},{}", celda.celdaX(), celda.celdaZ(), e);
            listas.add(new MallaLista(clave, celda, null, null, 0, false));
        }
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
        ParametrosCalidad c = calidad;
        float far = Math.max(NEAR_LOD * 2, c.radioLodChunks() * 16f * 1.5f);
        Matrix4f proyeccion = new Matrix4f().perspective((float) Math.toRadians(fovGrados),
                (float) mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight()), NEAR_LOD, far);

        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        // Sin culling: el orden de vértices todavía no está unificado por cara (Pista B).
        RenderSystem.disableCull();
        dibujarPasada(evento, camara, proyeccion, false, GameRenderer.getPositionColorShader());
        ShaderInstance conTextura = shaderTextura;
        if (conTextura != null) {
            // Atlas de bloques activo, con mipmaps: la textura se simplifica sola con la distancia.
            mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).setFilter(false, true);
            RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_BLOCKS);
            dibujarPasada(evento, camara, proyeccion, true, conTextura);
        }
        VertexBuffer.unbind();
        RenderSystem.enableCull();
        // GL_DEPTH_BUFFER_BIT: el terreno vanilla se dibuja después, siempre delante del LOD.
        RenderSystem.clear(256, Minecraft.ON_OSX);
    }

    private void dibujarPasada(RenderLevelStageEvent evento, Vec3 camara, Matrix4f proyeccion,
                               boolean texturizadas, ShaderInstance shader) {
        if (shader == null) {
            return;
        }
        RenderSystem.setShader(() -> shader);
        for (EstadoCelda estado : celdas.values()) {
            if (estado.buffer == null || estado.construidaCon == null || estado.texturizada != texturizadas) {
                continue;
            }
            float ox = (float) (estado.construidaCon.origenX() - camara.x);
            float oz = (float) (estado.construidaCon.origenZ() - camara.z);
            Matrix4f vista = new Matrix4f(evento.getModelViewMatrix()).translate(ox, (float) -camara.y, oz);
            estado.buffer.bind();
            estado.buffer.drawWithShader(vista, proyeccion, shader);
            llamadasUltimoFrame++;
        }
    }

    /**
     * true si el chunk vecino tapa la cara compartida: lo dibuja vanilla, o
     * tiene datos de LOD. Si no tiene datos (borde de lo generado) la cara
     * se dibuja: es el corte real del terreno conocido.
     */
    private static boolean vecinoCubierto(RegionFileStore store, byte dimension, int chunkX, int chunkZ,
                                          int chunkCamX, int chunkCamZ, int distanciaVanilla) {
        long dx = chunkX - chunkCamX, dz = chunkZ - chunkCamZ;
        if (dx * dx + dz * dz < (long) distanciaVanilla * distanciaVanilla) {
            return true;
        }
        return store.contiene(GeneradorLocal.claveRegion(dimension, chunkX, chunkZ),
                GeneradorLocal.claveMarca(chunkX, chunkZ));
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
        int conDatos = 0;
        for (int banda = Math.floorDiv(minSeccion, seccionesPorLado);
             banda <= Math.floorDiv(maxSeccion - 1, seccionesPorLado); banda++) {
            SuperVoxel[] grilla = nivel >= NivelesGrandes.NIVEL_MIN
                    ? leerGrande(store, dimension, nivel, tesela.celdaX(), banda, tesela.celdaZ())
                    : desdeSecciones(store, dimension, nivel, tesela.celdaX(), banda, tesela.celdaZ(),
                    minSeccion, maxSeccion);
            if (grilla == null) {
                continue;
            }
            conDatos++;
            geometria.agregarSeccion(grilla, NivelesGrandes.LADO, 0, banda * seccionesPorLado * 16f, 0, 1 << nivel);
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
        if (estado.buffer != null) {
            estado.buffer.close();
            estado.buffer = null;
        }
    }

    private static void cerrar(MallaLista lista) {
        if (lista.malla() != null) {
            lista.malla().close();
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
