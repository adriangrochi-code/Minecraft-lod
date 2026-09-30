package com.example.minecraftlodmod.render;

import com.example.minecraftlodmod.config.ConfigLod;
import com.example.minecraftlodmod.config.ParametrosCalidad;
import com.example.minecraftlodmod.generation.GeneradorLocal;
import com.example.minecraftlodmod.generation.NivelesGrandes;
import com.example.minecraftlodmod.core.SuperVoxel;
import com.example.minecraftlodmod.generation.SectionExtractor;
import com.example.minecraftlodmod.storage.OctreeNodeCodec;
import com.example.minecraftlodmod.storage.RegionFileStore;
import com.mojang.blaze3d.platform.NativeImage;
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
import com.example.minecraftlodmod.generation.PrioridadVista;
import com.example.minecraftlodmod.generation.TerrenoAproximado;
import com.example.minecraftlodmod.generation.GeneradorAproximado;
import net.minecraft.client.renderer.texture.DynamicTexture;
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
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.util.ArrayList;
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
    static final VertexFormatElement POSICION_SPRITE = conVulkanMod() ? null : VertexFormatElement.register(
            VertexFormatElement.findNextId(), 7, VertexFormatElement.Type.SHORT, VertexFormatElement.Usage.UV, 4);

    /**
     * Con VulkanMod, los mismos 8 bytes partidos en dos: VulkanMod arma el
     * atributo de Vulkan por uso y tipo sin mirar la cantidad (UV + SHORT es
     * siempre R16G16_SINT), así que x, y y z, sprite van en dos {@code ivec2}
     * (ver lod_textura_vk.vsh). Minecraft no deja un segundo POSITION.
     */
    static final VertexFormatElement POSICION_XY_VK = conVulkanMod() ? VertexFormatElement.register(
            VertexFormatElement.findNextId(), 7, VertexFormatElement.Type.SHORT, VertexFormatElement.Usage.UV, 2)
            : null;
    static final VertexFormatElement POSICION_Z_SPRITE_VK = conVulkanMod() ? VertexFormatElement.register(
            VertexFormatElement.findNextId(), 8, VertexFormatElement.Type.SHORT, VertexFormatElement.Usage.UV, 2)
            : null;

    /**
     * Formato compacto texturizado, 12 bytes por vértice (ver
     * {@link GeometriaLod#escribirCompacto} y shaders/core/lod_textura.vsh):
     * posición + índice de sprite, y color ya sombreado con la cara en el alfa.
     * Se arma con {@link VertexFormat.Builder} y se sube con {@link VertexBuffer}
     * como cualquier formato del juego: sin llamadas GL propias. Mismos bytes
     * con o sin VulkanMod; solo cambia cómo se describen los atributos.
     */
    public static final VertexFormat FORMATO_TEXTURA = conVulkanMod()
            ? VertexFormat.builder()
                    .add("PosXY", POSICION_XY_VK)
                    .add("PosZSprite", POSICION_Z_SPRITE_VK)
                    .add("Color", VertexFormatElement.COLOR)
                    .build()
            : VertexFormat.builder()
                    .add("PosSprite", POSICION_SPRITE)
                    .add("Color", VertexFormatElement.COLOR)
                    .build();

    /**
     * Cómo está armada una malla: colores planos ({@code POSITION_COLOR}),
     * formato compacto con texturas ({@link #FORMATO_TEXTURA}) o formato de
     * bloque vanilla para shaderpacks ({@link GeometriaLod#escribirBloque}).
     */
    enum TipoMalla { PLANA, TEXTURA, BLOQUE }

    /** Shader texturizado, registrado como cualquier ShaderInstance del juego; null si no cargó. */
    private static volatile ShaderInstance shaderTextura;
    /** Sube cada vez que cambian las texturas (resource pack, F3+T): hay que rearmar todo. */
    private static volatile int versionTexturas;
    private int versionTexturasVista = -1;
    private boolean texturasEnUso;
    private boolean oclusionEnUso;
    private boolean shadersEnUso;
    /**
     * Con un shaderpack el LOD usa la proyección de vanilla (la que conoce el
     * pack): este es el far que necesita, en bloques; 0 sin shaders. Lo lee el
     * mixin de GameRenderer#getDepthFar.
     */
    private static volatile float farParaShaders;
    /** Textura blanca 1×1: en modo shaders el color va en el vértice. */
    private static final ResourceLocation TEXTURA_BLANCA =
            ResourceLocation.fromNamespaceAndPath(MinecraftLodMod.MOD_ID, "lod_blanco");
    private static boolean texturaBlancaLista;

    // Estadísticas para estimar el costo en cada hardware (log cada 10 s).
    static final long PERIODO_ESTADISTICAS_NANOS = 10_000_000_000L;
    private final LongAdder nanosArmado = new LongAdder();
    private final LongAdder mallasArmadas = new LongAdder();
    private long ultimaEstadisticaNanos = System.nanoTime();
    private long framesDesdeEstadistica;
    private long nanosDibujo;
    private volatile long nanosDibujoUltimoFrame;
    private int llamadasUltimoFrame;
    /** Distancia horizontal a la cámara del borde más lejano de LOD dibujado en el último frame. */
    private volatile float alcanceLodBloques;
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
                        + "| ocultas por relieve {} ({} ms) | heap {} / {} MB",
                mc.getFps(), String.format("%.2f", nanosDibujo / 1e6 / Math.max(1, framesDesdeEstadistica)),
                llamadasUltimoFrame, verticesUltimoFrame, piezas, vertices, vertices / 2, bytesVram >> 20, mallas,
                mallas == 0 ? 0 : String.format("%.1f", nanos / 1e6 / mallas),
                piezasOcultas, String.format("%.1f", nanosOclusion / 1e6),
                (rt.totalMemory() - rt.freeMemory()) >> 20, rt.maxMemory() >> 20);
        ultimaEstadisticaNanos = ahora;
        framesDesdeEstadistica = 0;
        nanosDibujo = 0;
    }

    /** Lo que muestra el HUD de rendimiento y escribe el log de depuración. */
    public record Resumen(boolean activo, int piezas, long vertices, long verticesDibujados, int llamadas,
                          double msDibujo, long vramMb, int ocultas, int mallasEnCola) {
    }

    /** Hilo de render (recorre las celdas: llamarlo como mucho una vez por segundo). */
    public Resumen resumen() {
        int piezas = 0;
        long vertices = 0, bytes = 0;
        for (EstadoCelda e : celdas.values()) {
            if (e.tieneMalla) {
                piezas++;
                vertices += e.vertices;
                bytes += (long) e.vertices * e.bytesVertice;
            }
        }
        int enCola = hiloMallas instanceof ThreadPoolExecutor t ? t.getQueue().size() : 0;
        return new Resumen(dibujoPermitido() && calidad != null, piezas, vertices, verticesUltimoFrame,
                llamadasUltimoFrame, nanosDibujoUltimoFrame / 1e6, bytes >> 20, piezasOcultas, enCola);
    }

    /**
     * VulkanMod reemplaza ShaderInstance/VertexBuffer por su pipeline de Vulkan y
     * convierte los shaders legacy con un conversor limitado: el LOD texturizado usa
     * la variante lod_textura_vk y su formato de atributos; los de FSR/escalado no
     * se registran (sin pipeline, el primer dibujo tiraría NullPointerException).
     */
    public static boolean conVulkanMod() {
        ModList mods = ModList.get();
        return mods != null && mods.isLoaded("vulkanmod");
    }

    private static boolean dibujoPermitido() {
        return ConfigLod.CLIENTE.lodActivo.get();
    }

    /** Bus del mod, solo cliente. */
    public static void registrarShaders(RegisterShadersEvent evento) {
        try {
            // Con VulkanMod, variante que su conversor GLSL acepta (ver lod_textura_vk.vsh), y el
            // constructor con String: es el único que VulkanMod intercepta para armar el pipeline.
            // El de ResourceLocation deja el shader sin pipeline y el primer dibujo tira NPE.
            ShaderInstance shader = conVulkanMod()
                    ? new ShaderInstance(evento.getResourceProvider(), MinecraftLodMod.MOD_ID + ":lod_textura_vk",
                            FORMATO_TEXTURA)
                    : new ShaderInstance(evento.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(MinecraftLodMod.MOD_ID, "lod_textura"), FORMATO_TEXTURA);
            evento.registerShader(shader, cargado -> shaderTextura = cargado);
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
    /**
     * Las celdas en el orden del plan (lo mirado primero, de cerca a lejos): dibujar
     * así deja que el test de profundidad descarte temprano lo lejano ya tapado.
     */
    private final List<EstadoCelda> ordenDibujo = new ArrayList<>();
    private final ConcurrentLinkedQueue<MallaLista> listas = new ConcurrentLinkedQueue<>();
    private volatile double fovGrados = 70;
    private volatile ParametrosCalidad calidad;
    private int chunkPlanX = Integer.MIN_VALUE, chunkPlanZ = Integer.MIN_VALUE;
    private double fovPlan;
    private double yPlan;
    private double miraPlanX, miraPlanZ;
    /** Con zoom, girar más que esto replanifica (el detalle fino sigue a la mirada). */
    static final double REPLANIFICAR_GIRO = Math.toRadians(10);
    /**
     * Más lejos que esto (bloques), una celda va en un solo buffer en vez de
     * uno por dirección: se ve chica y pesa más la cantidad de llamadas de
     * dibujo que los vértices que ahorra separar por dirección.
     */
    static final double UN_BUFFER_DESDE = 768;
    /** Margen del cono con zoom, a cada lado, para que al girar un poco ya esté armado. */
    static final double MARGEN_ZOOM = Math.toRadians(10);
    /** Si la cámara sube o baja esto, se replanifica (la oclusión por relieve depende de la altura). */
    static final double REPLANIFICAR_ALTURA = 8;
    /** Solo el relieve hasta esta distancia tapa (ver OclusionRelieve). */
    static final double RADIO_OCLUSORES = 4096;
    private final CacheRelieve relieve = new CacheRelieve();
    private int piezasOcultas;
    private long nanosOclusion;
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
        /** Escondida detrás del relieve en el último plan: ni se arma ni se dibuja (la malla se conserva). */
        boolean oculta;
        int chunksConDatos;
        TipoMalla tipo = TipoMalla.PLANA;
        long construidaNanos;
    }

    /**
     * Resultado del hilo de mallas: una malla por dirección de cara (null las
     * vacías). {@code mallas} y {@code memoria} null = nada que dibujar.
     */
    private record MallaLista(long clave, PlanCeldas.Celda celda, MeshData[] mallas, float[] planos,
                              ByteBufferBuilder memoria, int chunksConDatos, TipoMalla tipo) {
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

    /**
     * La niebla de terreno vanilla se corre hasta donde llega el LOD REALMENTE
     * dibujado (el frame anterior), no hasta el radio del preset: si el LOD
     * todavía no tiene datos más allá de la distancia vanilla (mundo recién
     * creado, zona sin explorar) queda la niebla normal, y si los tiene, el
     * borde del terreno conocido se esconde dentro de la niebla.
     */
    @SubscribeEvent
    public void alCalcularNiebla(ViewportEvent.RenderFog evento) {
        if (!dibujoPermitido() || calidad == null || evento.getMode() != FogRenderer.FogMode.FOG_TERRAIN
                || evento.getType() != FogType.NONE) {
            return; // bajo el agua, en lava o con ceguera se respeta la niebla de vanilla
        }
        float finLod = alcanceLodBloques;
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
        // Apagado desde la config: ni dibujo ni niebla (las mallas se conservan para volver rápido).
        if (evento.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) {
            return;
        }
        if (!dibujoPermitido()) {
            farParaShaders = 0;
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
        boolean usarOclusion = ConfigLod.CLIENTE.oclusionAmbiental.get();
        boolean usarShaders = ShadersIris.enUso();
        if (versionTexturas != versionTexturasVista || usarTexturas != texturasEnUso
                || usarOclusion != oclusionEnUso || usarShaders != shadersEnUso) {
            versionTexturasVista = versionTexturas;
            texturasEnUso = usarTexturas;
            oclusionEnUso = usarOclusion;
            if (usarShaders != shadersEnUso) {
                LOG.info("LOD: shaderpack {}; el LOD se dibuja {}", usarShaders ? "activo" : "apagado",
                        usarShaders ? "con el terreno del pack" : "con sus propios shaders");
            }
            shadersEnUso = usarShaders;
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
                    VertexBuffer anterior = estado.buffers[cara];
                    if (anterior != null && anterior.getFormat() != null
                            && !anterior.getFormat().equals(malla.drawState().format())) {
                        // Otro formato (se prendió o apagó un shaderpack, o las texturas): VAO
                        // nuevo. Re-subir al mismo dejaba atributos del formato viejo (con Iris,
                        // las celdas cercanas salían a medio dibujar).
                        anterior.close();
                        estado.buffers[cara] = null;
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
            estado.tipo = lista.tipo();
            estado.construidaNanos = System.nanoTime();
            estado.enConstruccion = false;
        }
    }

    private void replanificarSiHaceFalta(Minecraft mc, Vec3 camara, RegionFileStore store) {
        int chunkX = (int) Math.floor(camara.x / 16);
        int chunkZ = (int) Math.floor(camara.z / 16);
        long ahora = System.nanoTime();
        org.joml.Vector3f mirada = mc.gameRenderer.getMainCamera().getLookVector();
        double miraX = mirada.x(), miraZ = mirada.z();
        double fovNormal = mc.options.fov().get();
        boolean conZoom = fovGrados < fovNormal - 1;
        boolean giro = conZoom && angulo(miraX, miraZ, miraPlanX, miraPlanZ) > REPLANIFICAR_GIRO;
        if (chunkX == chunkPlanX && chunkZ == chunkPlanZ && Math.abs(fovGrados - fovPlan) < 1 && !giro
                && Math.abs(camara.y - yPlan) < REPLANIFICAR_ALTURA && ahora - ultimoPlanNanos < REPLANIFICAR_NANOS) {
            return;
        }
        miraPlanX = miraX;
        miraPlanZ = miraZ;
        chunkPlanX = chunkX;
        chunkPlanZ = chunkZ;
        fovPlan = fovGrados;
        yPlan = camara.y;
        ultimoPlanNanos = ahora;

        ParametrosCalidad c = calidad;
        // Vanilla dibuja hasta su distancia de render; se deja un chunk de
        // solapamiento para que no queden huecos en el borde (vanilla queda encima).
        int distanciaVanilla = Math.max(0, mc.options.getEffectiveRenderDistance() - 1);
        // Chunks que vanilla YA tiene cargados dentro de su distancia: solo esos se le
        // dejan; el resto lo sigue dibujando el LOD hasta que llegue (sin huecos).
        Set<Long> deVanilla = new HashSet<>();
        Set<Long> consultados = new HashSet<>();
        long vanilla2 = (long) distanciaVanilla * distanciaVanilla;
        for (int dx = -distanciaVanilla; dx <= distanciaVanilla; dx++) {
            for (int dz = -distanciaVanilla; dz <= distanciaVanilla; dz++) {
                if ((long) dx * dx + (long) dz * dz < vanilla2) {
                    long claveChunk = PlanCeldas.claveChunk(chunkX + dx, chunkZ + dz);
                    consultados.add(claveChunk);
                    if (vanillaLoDibujo(mc, chunkX + dx, chunkZ + dz)) {
                        deVanilla.add(claveChunk);
                    }
                }
            }
        }
        cargadoDesde.keySet().retainAll(consultados);
        Set<Long> cubiertos = Set.copyOf(deVanilla);
        // Con zoom, el detalle extra solo para lo que entra en el cono de la vista (+ margen).
        double aspecto = (double) mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight());
        double mediaApertura = Math.atan(Math.tan(Math.toRadians(fovGrados) / 2) * aspecto) + MARGEN_ZOOM;
        PlanCeldas.Vista vista = new PlanCeldas.Vista(miraX, miraZ, Math.toRadians(fovNormal), mediaApertura);
        List<PlanCeldas.Celda> plan = PlanCeldas.planificarConGrandes(camara.x, camara.z, c.radioLodChunks(),
                distanciaVanilla, cubiertos::contains, vista, Math.toRadians(fovGrados), mc.getWindow().getHeight(),
                c.umbralPx());
        // Primero lo que se mira, después el margen, al final lo de atrás.
        plan.sort(Comparator.comparingDouble(celda -> prioridad(celda, camara, miraX, miraZ)));
        byte dimension = GeneradorLocal.idDimension(mc.level.dimension());
        boolean[] ocultas = ocultasPorRelieve(plan, camara, store, dimension, mc.level.getMinSection(),
                mc.level.getMaxSection(), c.radioLodChunks());

        Set<Long> vigentes = new HashSet<>();
        ordenDibujo.clear();
        int encoladas = 0;
        boolean bloque = shadersEnUso;
        TipoMalla tipoEsperado = bloque ? TipoMalla.BLOQUE : texturasEnUso ? TipoMalla.TEXTURA : TipoMalla.PLANA;
        // Con shaders las texturas no se dibujan, pero dan el color promedio de cada cara.
        GeometriaLod.Texturas texturas = texturasEnUso || bloque ? PaletaTexturas.tabla() : null;
        boolean oclusion = oclusionEnUso;
        int minSeccion = mc.level.getMinSection();
        int maxSeccion = mc.level.getMaxSection();
        for (int i = 0; i < plan.size(); i++) {
            PlanCeldas.Celda celda = plan.get(i);
            long clave = clave(celda);
            vigentes.add(clave);
            EstadoCelda estado = celdas.computeIfAbsent(clave, k -> new EstadoCelda());
            ordenDibujo.add(estado);
            estado.plan = celda;
            estado.oculta = ocultas[i];
            if (estado.oculta) {
                continue; // tapada por el relieve: no gastar en armarla; si ya tenía malla, queda guardada
            }
            // Una malla de otro tipo (terminó de armarse con el modo anterior después de
            // cambiar shaders o texturas) no se dibuja en ninguna pasada: se rearma.
            boolean cambio = !celda.equals(estado.construidaCon)
                    || (estado.tieneMalla && estado.tipo != tipoEsperado);
            boolean incompleta = estado.construidaCon != null
                    && estado.chunksConDatos < chunksDibujables(celda)
                    && ahora - estado.construidaNanos > RECONSTRUIR_INCOMPLETA_NANOS;
            double mitad = celda.ladoEnBloques() / 2.0;
            // Con shaderpack cada llamada pasa por el apply() de Iris: un buffer por celda.
            boolean unBuffer = bloque || celda.esGrande() || Math.hypot(celda.origenX() + mitad - camara.x,
                    celda.origenZ() + mitad - camara.z) > UN_BUFFER_DESDE;
            if (!estado.enConstruccion && (cambio || incompleta) && encoladas < ENCOLADAS_POR_PLAN) {
                estado.enConstruccion = true;
                encoladas++;
                hiloMallas.execute(new TareaMalla(prioridad(celda, camara, miraX, miraZ), secuenciaTareas.incrementAndGet(),
                        () -> armar(clave, celda, store, dimension, minSeccion, maxSeccion,
                                cubiertos, texturas, oclusion, unBuffer, bloque)));
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
                       int minSeccion, int maxSeccion, Set<Long> deVanilla,
                       GeometriaLod.Texturas texturas, boolean oclusion, boolean unBuffer, boolean bloque) {
        long inicioArmado = System.nanoTime();
        try {
            GeometriaLod geometria = new GeometriaLod();
            geometria.usarTexturas(texturas);
            geometria.descartarCarasSinLuz(ConfigLod.CLIENTE.descartarCuevas.get());
            geometria.usarOclusionAmbiental(oclusion);
            int conDatos = celda.esGrande()
                    ? armarTesela(geometria, celda, store, dimension, minSeccion, maxSeccion)
                    : armarCelda(geometria, celda, store, dimension, minSeccion, maxSeccion, deVanilla);
            if (geometria.vertices() == 0) {
                listas.add(new MallaLista(clave, celda, null, null, null, conDatos, TipoMalla.PLANA));
                return;
            }
            TipoMalla tipo = bloque ? TipoMalla.BLOQUE : texturas != null ? TipoMalla.TEXTURA : TipoMalla.PLANA;
            VertexFormat formato = switch (tipo) {
                case PLANA -> DefaultVertexFormat.POSITION_COLOR;
                case TEXTURA -> FORMATO_TEXTURA;
                // Solo hay mallas BLOQUE con un pack de Iris activo: su formato extendido.
                case BLOQUE -> ShadersIris.formatoTerreno() != null ? ShadersIris.formatoTerreno()
                        : DefaultVertexFormat.BLOCK;
            };
            // Toda la memoria de una vez: las 6 mallas salen del mismo bloque, sin realocar.
            ByteBufferBuilder memoria = new ByteBufferBuilder(geometria.vertices() * formato.getVertexSize());
            MeshData[] mallas = new MeshData[GeometriaLod.CARAS];
            float[] planos = new float[2 * GeometriaLod.CARAS];
            if (unBuffer) {
                // Lejos (teselas y celdas pasando UN_BUFFER_DESDE): un solo buffer con todas las caras.
                // Se ven chicas y la GPU ya descarta las de espaldas; separarlas triplicaba las llamadas.
                mallas[0] = malla(geometria, -1, geometria.vertices(), formato, memoria, tipo);
                planos[0] = Float.NEGATIVE_INFINITY;
                planos[1] = Float.POSITIVE_INFINITY;
                listas.add(new MallaLista(clave, celda, mallas, planos, memoria, conDatos, tipo));
                nanosArmado.add(System.nanoTime() - inicioArmado);
                mallasArmadas.increment();
                return;
            }
            for (int cara = 0; cara < GeometriaLod.CARAS; cara++) {
                planos[2 * cara] = geometria.planoMin(cara);
                planos[2 * cara + 1] = geometria.planoMax(cara);
                int n = geometria.verticesDeCara(cara);
                if (n > 0) {
                    mallas[cara] = malla(geometria, cara, n, formato, memoria, tipo);
                }
            }
            listas.add(new MallaLista(clave, celda, mallas, planos, memoria, conDatos, tipo));
            nanosArmado.add(System.nanoTime() - inicioArmado);
            mallasArmadas.increment();
        } catch (RuntimeException e) {
            LOG.error("LOD: no se pudo armar la celda {},{}", celda.celdaX(), celda.celdaZ(), e);
            listas.add(new MallaLista(clave, celda, null, null, null, 0, TipoMalla.PLANA));
        }
    }

    /** @param cara 0-5, o -1 para todas las caras en una sola malla */
    private static MeshData malla(GeometriaLod geometria, int cara, int n, VertexFormat formato,
                                  ByteBufferBuilder memoria, TipoMalla tipo) {
        if (tipo != TipoMalla.PLANA) {
            // Bytes escritos directo y envueltos en un MeshData como el de BufferBuilder
            // (que exige POSITION en float y no conoce el formato compacto).
            int bytes = n * formato.getVertexSize();
            ByteBuffer destino = MemoryUtil.memByteBuffer(memoria.reserve(bytes), bytes);
            if (tipo == TipoMalla.TEXTURA) {
                geometria.escribirCompacto(destino, cara);
            } else {
                geometria.escribirBloque(destino, cara, formato.getVertexSize() == GeometriaLod.BYTES_BLOQUE_IRIS);
            }
            return new MeshData(memoria.build(), new MeshData.DrawState(formato, n,
                    VertexFormat.Mode.QUADS.indexCount(n), VertexFormat.Mode.QUADS, VertexFormat.IndexType.least(n)));
        }
        BufferBuilder builder = new BufferBuilder(memoria, VertexFormat.Mode.QUADS, formato);
        for (int i = 0; i < geometria.vertices(); i++) {
            if (cara < 0 || geometria.cara(i) == cara) {
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
                                  Set<Long> deVanilla) {
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
                boolean real = tieneDatos(store, dimension, chunkX, chunkZ);
                if (!real && !tieneAproximado(store, dimension, chunkX, chunkZ)) {
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
                    if (deVanilla.contains(PlanCeldas.claveChunk(vx, vz))) {
                        omitidas |= lados[l][2];
                    } else {
                        lod[l] = tieneDatos(store, dimension, vx, vz);
                    }
                }
                if (!real) {
                    armarAproximado(geometria, store, dimension, nivel, chunkX, chunkZ, dx, dz,
                            minSeccion, maxSeccion, omitidas);
                    continue;
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

    /**
     * Chunk sin datos reales pero con horizonte aproximado: niveles 3 o 4
     * (los únicos que existen aproximados; si la celda pide uno más fino, se
     * usa el 3). Vecinas solo arriba y abajo: los costados de un chunk
     * aproximado se dibujan (lejos, costo chico).
     */
    private static void armarAproximado(GeometriaLod geometria, RegionFileStore store, byte dimension, int nivel,
                                        int chunkX, int chunkZ, int dx, int dz, int minSeccion, int maxSeccion,
                                        int omitidas) {
        int nivelA = Math.max(TerrenoAproximado.NIVEL_MIN, Math.min(TerrenoAproximado.NIVEL_MAX, nivel));
        int lado = SectionExtractor.LADO >> nivelA;
        int total = SectionExtractor.voxelesPorNodo(nivelA);
        RegionFileStore.ClaveRegion region = GeneradorLocal.claveRegion(dimension, chunkX, chunkZ);
        SuperVoxel[][] grillas = new SuperVoxel[maxSeccion - minSeccion][];
        for (int sy = minSeccion; sy < maxSeccion; sy++) {
            byte[] bytes = store.leer(region, SectionExtractor.claveNodo(TerrenoAproximado.nivelGuardado(nivelA),
                    chunkX, sy, chunkZ));
            grillas[sy - minSeccion] = bytes == null ? null : OctreeNodeCodec.deserializar(bytes, 0, total).voxeles();
        }
        for (int i = 0; i < grillas.length; i++) {
            if (grillas[i] == null) {
                continue;
            }
            GreedyMesher.Vecinos vecinos = GreedyMesher.Vecinos.deGrillas(lado, null, null,
                    i > 0 ? grillas[i - 1] : null, i + 1 < grillas.length ? grillas[i + 1] : null, null, null);
            geometria.agregarSeccion(grillas[i], lado, dx * 16f, (minSeccion + i) * 16f, dz * 16f, 16f / lado,
                    omitidas, vecinos);
        }
    }

    private static boolean tieneAproximado(RegionFileStore store, byte dimension, int chunkX, int chunkZ) {
        return store.contiene(GeneradorLocal.claveRegion(dimension, chunkX, chunkZ),
                GeneradorAproximado.claveMarca(chunkX, chunkZ));
    }

    /** Marca de "nodo sin datos" en el memo de {@link #armarCelda} (computeIfAbsent no guarda null). */
    private static final SuperVoxel[] SIN_NODO = new SuperVoxel[0];

    private static SuperVoxel[] existente(SuperVoxel[] nodo) {
        return nodo == SIN_NODO ? null : nodo;
    }

    /** Sección (chunk x, sección y, chunk z): clave del memo de un armado. */
    private record PosSeccion(int x, int y, int z) {
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
            long costo = System.nanoTime() - inicio;
            nanosDibujo += costo;
            nanosDibujoUltimoFrame = costo;
        }
    }

    private void dibujarLod(Minecraft mc, RenderLevelStageEvent evento, Vec3 camara) {
        llamadasUltimoFrame = 0;
        verticesUltimoFrame = 0;
        alcanceLodBloques = alcance(camara);
        if (shadersEnUso) {
            dibujarConShaderpack(mc, evento, camara);
            return;
        }
        farParaShaders = 0;
        ParametrosCalidad c = calidad;
        float far = Math.max(NEAR_LOD * 2, c.radioLodChunks() * 16f * 1.5f);
        // La de vanilla (con balanceo de cámara, zoom y FOV reales) con near/far del LOD.
        Matrix4f proyeccion = PlanCeldas.conPlanosDeProfundidad(new Matrix4f(evento.getProjectionMatrix()),
                NEAR_LOD, far);

        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        // Todas las caras son antihorarias vistas desde afuera (GeometriaLod): culling normal.
        RenderSystem.enableCull();
        dibujarPasada(evento, camara, proyeccion, TipoMalla.PLANA, GameRenderer.getPositionColorShader());
        ShaderInstance conTextura = shaderTextura;
        if (conTextura != null) {
            // Atlas de bloques activo, con mipmaps: la textura se simplifica sola con la distancia.
            mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).setFilter(false, true);
            RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_BLOCKS);
            RenderSystem.setShaderTexture(1, PaletaTexturas.TABLA_SPRITES);
            dibujarPasada(evento, camara, proyeccion, TipoMalla.TEXTURA, conTextura);
        }
        VertexBuffer.unbind();
        RenderSystem.enableCull();
        // GL_DEPTH_BUFFER_BIT: el terreno vanilla se dibuja después, siempre delante del LOD.
        RenderSystem.clear(256, Minecraft.ON_OSX);
    }

    /**
     * Con shaderpack: el shader de terreno sólido de vanilla (Iris lo cambia por el
     * gbuffers_terrain del pack) y la proyección de vanilla sin tocar, porque el pack
     * reconstruye posiciones desde la profundidad con ella. Para que el LOD entre, el
     * far de vanilla se estira hasta el alcance del LOD (mixin de getDepthFar). Sin
     * limpiar la profundidad después: el pack la necesita, y vanilla tapa igual al LOD
     * en su zona porque el LOD no dibuja los chunks que vanilla ya tiene.
     */
    private void dibujarConShaderpack(Minecraft mc, RenderLevelStageEvent evento, Vec3 camara) {
        farParaShaders = alcanceLodBloques * 1.1f;
        if (ShadersIris.pasadaDeSombras()) {
            return;
        }
        asegurarTexturaBlanca(mc);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        RenderSystem.enableCull();
        RenderSystem.setShaderTexture(0, TEXTURA_BLANCA);
        mc.gameRenderer.lightTexture().turnOnLightLayer();
        dibujarPasada(evento, camara, new Matrix4f(evento.getProjectionMatrix()), TipoMalla.BLOQUE,
                GameRenderer.getRendertypeSolidShader());
        mc.gameRenderer.lightTexture().turnOffLightLayer();
        VertexBuffer.unbind();
    }

    private static void asegurarTexturaBlanca(Minecraft mc) {
        if (texturaBlancaLista) {
            return;
        }
        NativeImage blanca = new NativeImage(1, 1, false);
        blanca.setPixelRGBA(0, 0, 0xFFFFFFFF);
        mc.getTextureManager().register(TEXTURA_BLANCA, new DynamicTexture(blanca));
        texturaBlancaLista = true;
    }

    /** Far de la proyección de vanilla que necesita el LOD con shaderpack (0 = no tocarlo). */
    public static float farParaShaders() {
        return farParaShaders;
    }

    /**
     * Una pasada (plana, texturizada o de bloque para shaderpacks). Con el shader propio se hace como el
     * terreno vanilla: uniforms y shader una sola vez, y por buffer solo el
     * desplazamiento de la celda ({@code ChunkOffset}) y el draw; así los
     * buffers por dirección no multiplican el costo de CPU. El shader
     * {@code position_color} de vanilla no tiene ChunkOffset: ahí se usa
     * drawWithShader con la matriz desplazada.
     */
    private void dibujarPasada(RenderLevelStageEvent evento, Vec3 camara, Matrix4f proyeccion,
                               TipoMalla tipo, ShaderInstance shader) {
        if (shader == null) {
            return;
        }
        RenderSystem.setShader(() -> shader);
        // Con shaderpack, Iris cambia el shader por el del pack: siempre drawWithShader.
        Uniform desplazamiento = tipo == TipoMalla.BLOQUE ? null : shader.CHUNK_OFFSET;
        if (desplazamiento != null) {
            shader.setDefaultUniforms(VertexFormat.Mode.QUADS, evento.getModelViewMatrix(), proyeccion,
                    Minecraft.getInstance().getWindow());
            shader.apply();
        }
        for (EstadoCelda estado : ordenDibujo) {
            if (!estado.tieneMalla || estado.construidaCon == null || estado.tipo != tipo
                    || estado.oculta) {
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

    /**
     * Oclusión por relieve (ver {@link OclusionRelieve}); todo false si está
     * apagada en la config. Antes lee (con tope) el relieve que falte.
     */
    private boolean[] ocultasPorRelieve(List<PlanCeldas.Celda> plan, Vec3 camara, RegionFileStore store,
                                        byte dimension, int minSeccion, int maxSeccion, int radioLodChunks) {
        if (!ConfigLod.CLIENTE.ocultarTapado.get()) {
            piezasOcultas = 0;
            return new boolean[plan.size()];
        }
        long inicio = System.nanoTime();
        double radioOclusores = Math.min(RADIO_OCLUSORES, radioLodChunks * 16.0);
        relieve.preparar(store, dimension, minSeccion, maxSeccion, camara.x, camara.z, radioLodChunks * 16.0);
        List<OclusionRelieve.Pieza> piezas = new ArrayList<>(plan.size());
        for (PlanCeldas.Celda celda : plan) {
            piezas.add(new OclusionRelieve.Pieza(celda.origenX(), celda.origenZ(), celda.ladoEnBloques()));
        }
        boolean[] ocultas = OclusionRelieve.ocultas(camara.x, camara.y, camara.z, piezas, relieve, radioOclusores);
        int cuantas = 0;
        for (boolean o : ocultas) {
            if (o) cuantas++;
        }
        piezasOcultas = cuantas;
        nanosOclusion = System.nanoTime() - inicio;
        return ocultas;
    }

    /**
     * Relieve por región de 512 bloques, leído del nivel 5 guardado (una
     * grilla 16³ de vóxeles de 32 bloques por banda vertical). Se lee de a
     * poco ({@link #LECTURAS_POR_PLAN}) y se refresca cada
     * {@link #VIGENCIA_NANOS}: lo que todavía no se leyó cuenta como "sin
     * datos", que nunca oculta nada. Solo hilo de render.
     */
    private static final class CacheRelieve implements OclusionRelieve.Relieve {
        static final int LECTURAS_POR_PLAN = 32;
        static final long VIGENCIA_NANOS = 60_000_000_000L;

        private record Datos(float[] suelos, float tope, long leidoNanos) {
        }

        private final Map<Long, Datos> regiones = new HashMap<>();
        private byte dimension = -1;

        void preparar(RegionFileStore store, byte dimension, int minSeccion, int maxSeccion,
                      double camX, double camZ, double radio) {
            if (dimension != this.dimension) {
                regiones.clear();
                this.dimension = dimension;
            }
            int r = OclusionRelieve.REGION;
            int desdeX = Math.floorDiv((int) Math.floor(camX - radio), r), hastaX = Math.floorDiv((int) Math.floor(camX + radio), r);
            int desdeZ = Math.floorDiv((int) Math.floor(camZ - radio), r), hastaZ = Math.floorDiv((int) Math.floor(camZ + radio), r);
            int centroX = Math.floorDiv((int) Math.floor(camX), r), centroZ = Math.floorDiv((int) Math.floor(camZ), r);
            // Del centro hacia afuera: con el tope de lecturas, primero lo que más tapa.
            List<long[]> faltan = new ArrayList<>();
            long ahora = System.nanoTime();
            for (int rx = desdeX; rx <= hastaX; rx++) {
                for (int rz = desdeZ; rz <= hastaZ; rz++) {
                    Datos d = regiones.get(PlanCeldas.claveChunk(rx, rz));
                    if (d == null || ahora - d.leidoNanos() > VIGENCIA_NANOS) {
                        long dx = rx - centroX, dz = rz - centroZ;
                        faltan.add(new long[]{dx * dx + dz * dz, rx, rz});
                    }
                }
            }
            faltan.sort(Comparator.comparingLong(f -> f[0]));
            for (int i = 0; i < Math.min(LECTURAS_POR_PLAN, faltan.size()); i++) {
                int rx = (int) faltan.get(i)[1], rz = (int) faltan.get(i)[2];
                regiones.put(PlanCeldas.claveChunk(rx, rz), leer(store, dimension, rx, rz, minSeccion, maxSeccion, ahora));
            }
            // Las muy alejadas de la cámara ya no sirven: se sueltan.
            regiones.keySet().removeIf(k -> {
                int rx = (int) k.longValue(), rz = (int) (k >> 32);
                return rx < desdeX - 2 || rx > hastaX + 2 || rz < desdeZ - 2 || rz > hastaZ + 2;
            });
        }

        private static Datos leer(RegionFileStore store, byte dimension, int rx, int rz, int minSeccion,
                                  int maxSeccion, long ahora) {
            int n = OclusionRelieve.COLUMNAS_POR_REGION;
            int nivel = NivelesGrandes.NIVEL_MIN;
            int seccionesPorBanda = NivelesGrandes.ladoEnSecciones(nivel);
            float[] suelos = new float[n * n];
            java.util.Arrays.fill(suelos, Float.NaN);
            boolean alguna = false;
            float tope = Float.NEGATIVE_INFINITY;
            // De la banda de arriba hacia abajo: el primer sólido por columna es el suelo.
            for (int banda = Math.floorDiv(maxSeccion - 1, seccionesPorBanda);
                 banda >= Math.floorDiv(minSeccion, seccionesPorBanda); banda--) {
                SuperVoxel[] grilla = leerGrande(store, dimension, nivel, rx, banda, rz);
                if (grilla == null) {
                    continue;
                }
                alguna = true;
                for (int x = 0; x < n; x++) {
                    for (int z = 0; z < n; z++) {
                        if (!Float.isNaN(suelos[x * n + z])) {
                            continue;
                        }
                        for (int y = n - 1; y >= 0; y--) {
                            if (grilla[(x * n + y) * n + z].material() != SuperVoxel.Material.AIRE) {
                                float suelo = banda * seccionesPorBanda * 16f + y * OclusionRelieve.COLUMNA;
                                suelos[x * n + z] = suelo;
                                tope = Math.max(tope, suelo + OclusionRelieve.COLUMNA);
                                break;
                            }
                        }
                    }
                }
            }
            return alguna ? new Datos(suelos, tope, ahora) : new Datos(null, Float.NaN, ahora);
        }

        @Override
        public float[] suelos(int regionX, int regionZ) {
            Datos d = regiones.get(PlanCeldas.claveChunk(regionX, regionZ));
            return d == null ? null : d.suelos();
        }
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
                    RegionFileStore.ClaveRegion region = GeneradorLocal.claveRegion(dimension, seccionX, seccionZ);
                    byte[] bytes = store.leer(region, SectionExtractor.claveNodo(nivel, seccionX, seccionY, seccionZ));
                    if (bytes == null && !store.contiene(region, GeneradorLocal.claveMarca(seccionX, seccionZ))) {
                        // Chunk nunca generado: horizonte aproximado (las teselas 3-4 usan esos mismos niveles).
                        bytes = store.leer(region, SectionExtractor.claveNodo(TerrenoAproximado.nivelGuardado(nivel),
                                seccionX, seccionY, seccionZ));
                    }
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

    /** Borde más lejano (esquina de celda) entre las celdas con malla. */
    private float alcance(Vec3 camara) {
        double maximo = 0;
        for (EstadoCelda e : celdas.values()) {
            if (!e.tieneMalla || e.construidaCon == null || e.oculta) {
                continue;
            }
            PlanCeldas.Celda c = e.construidaCon;
            double dx = Math.max(Math.abs(c.origenX() - camara.x), Math.abs(c.origenX() + c.ladoEnBloques() - camara.x));
            double dz = Math.max(Math.abs(c.origenZ() - camara.z), Math.abs(c.origenZ() + c.ladoEnBloques() - camara.z));
            maximo = Math.max(maximo, dx * dx + dz * dz);
        }
        return (float) Math.sqrt(maximo);
    }

    /**
     * true si vanilla ya DIBUJA el chunk: cargado en el cliente y con la
     * sección de la superficie y las dos de abajo compiladas. Solo "cargado"
     * no alcanzaba: en el borde de la distancia de render, vanilla tiene el
     * chunk pero todavía no compiló (o no compila, por falta de vecinos) el
     * fondo, y a través del agua translúcida se veía el cielo. Mientras
     * vanilla no lo termina, el LOD lo sigue dibujando por debajo.
     */
    /**
     * Sodium/Embeddium reemplazan el renderer de chunks y su isSectionCompiled no
     * refleja lo que dibujan (da false aun para secciones a la vista): ahí se cede
     * el chunk cuando lleva {@link #ESPERA_MALLADO_NANOS} cargado en el cliente.
     */
    private static final boolean RENDERER_DE_CHUNKS_PROPIO =
            ModList.get().isLoaded("sodium") || ModList.get().isLoaded("embeddium");
    static final long ESPERA_MALLADO_NANOS = 2_000_000_000L;
    private final Map<Long, Long> cargadoDesde = new HashMap<>();

    private boolean vanillaLoDibujo(Minecraft mc, int chunkX, int chunkZ) {
        if (!mc.level.getChunkSource().hasChunk(chunkX, chunkZ)) {
            cargadoDesde.remove(PlanCeldas.claveChunk(chunkX, chunkZ));
            return false;
        }
        if (RENDERER_DE_CHUNKS_PROPIO) {
            long ahora = System.nanoTime();
            long desde = cargadoDesde.computeIfAbsent(PlanCeldas.claveChunk(chunkX, chunkZ), k -> ahora);
            return ahora - desde >= ESPERA_MALLADO_NANOS;
        }
        int x = chunkX * 16 + 8, z = chunkZ * 16 + 8;
        int superficie = mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
        for (int y = superficie; y > superficie - 48 && y >= mc.level.getMinBuildHeight(); y -= 16) {
            if (!mc.levelRenderer.isSectionCompiled(new net.minecraft.core.BlockPos(x, y, z))) {
                return false;
            }
        }
        return true;
    }

    private static double prioridad(PlanCeldas.Celda celda, Vec3 camara, double miraX, double miraZ) {
        double mitad = celda.ladoEnBloques() / 2.0;
        return PrioridadVista.costo(celda.origenX() + mitad - camara.x, celda.origenZ() + mitad - camara.z,
                miraX, miraZ);
    }

    private static double angulo(double ax, double az, double bx, double bz) {
        double na = Math.hypot(ax, az), nb = Math.hypot(bx, bz);
        if (na < 1e-9 || nb < 1e-9) {
            return Math.PI;
        }
        return Math.acos(Math.max(-1, Math.min(1, (ax * bx + az * bz) / (na * nb))));
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
        cargadoDesde.clear();
        celdas.values().forEach(RenderLod::cerrarBuffer);
        celdas.clear();
        ordenDibujo.clear();
        alcanceLodBloques = 0;
        MallaLista lista;
        while ((lista = listas.poll()) != null) {
            cerrar(lista);
        }
        chunkPlanX = Integer.MIN_VALUE;
        nivelActual = null;
    }
}
