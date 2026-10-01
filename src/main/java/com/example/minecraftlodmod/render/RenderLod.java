package com.example.minecraftlodmod.render;

import com.example.minecraftlodmod.config.ConfigLod;
import com.example.minecraftlodmod.config.ConmutadorVulkan;
import com.example.minecraftlodmod.config.ParametrosCalidad;
import com.example.minecraftlodmod.generation.GeneradorLocal;
import com.example.minecraftlodmod.generation.NivelesGrandes;
import com.example.minecraftlodmod.core.HorizonteCurvo;
import com.example.minecraftlodmod.core.SuperVoxel;
import com.example.minecraftlodmod.generation.SectionExtractor;
import com.example.minecraftlodmod.storage.OctreeNodeCodec;
import com.example.minecraftlodmod.storage.RegionFileStore;
import com.example.minecraftlodmod.render.mixin.AccesoLightTexture;
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import com.example.minecraftlodmod.cubico.ClienteVertical;
import com.example.minecraftlodmod.cubico.RangoSecciones;
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
 *     armarse en el hilo de mallas (y no más de {@link #BYTES_SUBIDA_POR_FRAME}).
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
    /**
     * Tope de bytes de vértices subidos a GPU por cuadro (siempre entra al menos una
     * malla): una tesela lejana grande pesa varios MB y cuatro juntas trababan el cuadro.
     */
    static final long BYTES_SUBIDA_POR_FRAME = 4L << 20;
    /** Máximo de celdas encoladas para armar por cada replanificación (las más cercanas primero). */
    static final int ENCOLADAS_POR_PLAN = 48;
    /** Cada cuánto se replanifica aunque la cámara no se mueva (para levantar chunks recién generados). */
    static final long REPLANIFICAR_NANOS = 3_000_000_000L;
    /** Antigüedad a partir de la cual se reconstruye una celda incompleta (le faltaban chunks con datos). */
    static final long RECONSTRUIR_INCOMPLETA_NANOS = 10_000_000_000L;
    /** Profundidad de 24 bits: un near plane lejos mejora mucho la precisión a distancia. */
    static final float NEAR_LOD = 16f;
    /** Intensidad del SSAO del acabado (render/AcabadoLod); el ajuste fino es de Pista B. */
    static final float FUERZA_SSAO = 0.8f;
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
    /** Con shaderpack: el LOD se dibuja con el voxy_opaque del pack (DibujoVoxy) en vez de gbuffers_terrain. */
    private boolean voxyEnUso;
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
    /** Alto en píxeles con que se midió el último plan (el del mundo escalado, si hay escalado). */
    private int alturaPlan;
    /** Radio del último plan, en chunks (el del preset, el del auto-ajuste o el del horizonte real). */
    private int radioEnUso;
    private long verticesUltimoFrame;

    /** Replanificaciones (hilo de render, dentro del cuadro) desde la última estadística, y la más larga. */
    private int planesDesdeEstadistica;
    private long nanosPlanMaximo;

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
                        + "| ocultas por relieve {} ({} ms) | planes {} (máx {} ms) | cache RAM {} MB | secciones cliente {} ({} columnas parciales; {}) | luz cielo #{} | heap {} / {} MB",
                mc.getFps(), String.format("%.2f", nanosDibujo / 1e6 / Math.max(1, framesDesdeEstadistica)),
                llamadasUltimoFrame, verticesUltimoFrame, piezas, vertices, vertices / 2, bytesVram >> 20, mallas,
                mallas == 0 ? 0 : String.format("%.1f", nanos / 1e6 / mallas),
                piezasOcultas, String.format("%.1f", nanosOclusion / 1e6),
                planesDesdeEstadistica, String.format("%.1f", nanosPlanMaximo / 1e6),
                generador.store() == null ? 0 : generador.store().bytesEnCache() >> 20,
                seccionesConBloques(mc), ClienteVertical.columnasParciales(),
                com.example.minecraftlodmod.cubico.SincroVertical.ESTADISTICAS.resumenYReiniciar(),
                String.format("%06X", colorLuzCielo(mc)), (rt.totalMemory() - rt.freeMemory()) >> 20, rt.maxMemory() >> 20);
        ultimaEstadisticaNanos = ahora;
        framesDesdeEstadistica = 0;
        nanosDibujo = 0;
        planesDesdeEstadistica = 0;
        nanosPlanMaximo = 0;
    }

    /** Lo que muestra el HUD de rendimiento y escribe el log de depuración. */
    public record Resumen(boolean activo, int piezas, long vertices, long verticesDibujados, int llamadas,
                          double msDibujo, long vramMb, int ocultas, int mallasEnCola, int radioChunks) {
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
                llamadasUltimoFrame, nanosDibujoUltimoFrame / 1e6, bytes >> 20, piezasOcultas, enCola, radioEnUso);
    }

    /**
     * VulkanMod reemplaza ShaderInstance/VertexBuffer por su pipeline de Vulkan y
     * convierte los shaders legacy con un conversor limitado: el LOD texturizado usa
     * la variante lod_textura_vk y su formato de atributos; los de FSR/escalado no
     * se registran (sin pipeline, el primer dibujo tiraría NullPointerException).
     */
    public static boolean conVulkanMod() {
        ModList mods = ModList.get();
        return mods != null && (mods.isLoaded("vulkanmod") || ConmutadorVulkan.activoEnEstaSesion());
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
    /** Auto-ajuste según el cuello de botella (detalle, radio, agrupado de caras, oclusión). */
    private final BalanceCpuGpu balance;
    private int versionBalancePlan = -1;
    /** Versión de los rangos de la sincronización vertical con que se armó el plan. */
    private int versionVerticalPlan = -1;
    /** Al subir o bajar llegan cientos de rangos en pocos cuadros: como mucho un plan por esto cada medio segundo. */
    static final long REPLANIFICAR_VERTICAL_NANOS = 500_000_000L;
    /**
     * Un hilo con cola de PRIORIDAD por distancia a la cámara: las celdas se
     * arman del centro (el jugador) hacia afuera, aunque una lejana se haya
     * encolado antes. Solo {@code execute()}: {@code submit()} envolvería la
     * tarea en algo no comparable.
     */
    private final ExecutorService hiloMallas = new ThreadPoolExecutor(HILOS_MALLAS, HILOS_MALLAS, 0,
            TimeUnit.MILLISECONDS, new PriorityBlockingQueue<>(), new java.util.concurrent.ThreadFactory() {
        private final java.util.concurrent.atomic.AtomicInteger numero = new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread hilo = new Thread(r, "LOD-Mallas-" + numero.incrementAndGet());
            hilo.setDaemon(true);
            hilo.setPriority(Thread.MIN_PRIORITY);
            return hilo;
        }
    });

    /**
     * Hilos que arman mallas: con uno solo, en la 1060 (6 núcleos) la cola llegaba a ~960
     * celdas mientras se volaba y lo cercano tardaba en aparecer. Un tercio de los núcleos
     * (1 a 3): el resto queda para el juego, el servidor y la generación.
     */
    static final int HILOS_MALLAS = Integer.getInteger("minecraftlodmod.hilosMallas",
            Math.max(1, Math.min(3, Runtime.getRuntime().availableProcessors() / 3)));
    private final AtomicLong secuenciaTareas = new AtomicLong();
    /** Una por hilo de mallas: se reutiliza entre celdas ({@link GeometriaLod#reiniciar}). */
    private final ThreadLocal<GeometriaLod> geometriaMallas = ThreadLocal.withInitial(GeometriaLod::new);

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
     * Mientras el FOV se anima (catalejo, mods de zoom) no se replanifica en
     * cada cuadro: se espera a que quede quieto este tiempo, o como mucho
     * {@link #REPLANIFICAR_FOV_MAXIMO_NANOS} entre planes. Replanificar en cada
     * cuadro de la animación era el tirón al usar el catalejo.
     */
    static final long FOV_QUIETO_NANOS = 150_000_000L, REPLANIFICAR_FOV_MAXIMO_NANOS = 400_000_000L;
    private double fovVisto = 70;
    /**
     * Distancia (horizontal, en bloques) desde la que no se dibuja con lluvia: la neblina la
     * tapa. Se calcula en cada cuadro y NO toca el plan: hasta la 0.25.4 la lluvia achicaba el
     * radio del plan en décimos, y cada décimo replanificaba y rearmaba celdas (y al parar se
     * rearmaba todo lo lejano): tirones al empezar y terminar de llover o nevar.
     */
    private float corteLluvia = Float.MAX_VALUE;
    /** Cuánto se achica el radio del LOD con lluvia o tormenta plena (la neblina tapa lo de más allá). */
    static final double RADIO_CON_LLUVIA = 0.5;
    /** Neblina mínima con lluvia plena. */
    static final float NEBLINA_LLUVIA = 0.85f;

    /** Lluvia y tormenta, 0 a 1 (la tormenta cuenta doble); 0 si la opción está apagada o no hay mundo. */
    private static float lluvia(Minecraft mc) {
        if (mc.level == null || !ConfigLod.CLIENTE.nieblaLluvia.get()) {
            return 0f;
        }
        return Math.min(1f, mc.level.getRainLevel(1f) * 0.7f + mc.level.getThunderLevel(1f) * 0.3f);
    }
    private long fovCambioNanos;
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
        /**
         * Buffers por dirección de cara (GeometriaLod.CARAS), null si no tiene caras.
         * Casi siempre uno por cara; con VulkanMod, más si pasa de {@link #MAX_VERTICES_VULKANMOD}.
         */
        final VertexBuffer[][] buffers = new VertexBuffer[GeometriaLod.CARAS][];
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
        /** Inicio de su fundido de entrada ({@link FundidoNiveles}); 0 = entera. */
        long aparicionNanos;
        /** Malla nueva que espera, sin dibujarse, a cruzarse con las salientes que tapa. */
        boolean esperando;
        /** Rangos verticales de sus columnas con que se armó (sincronización vertical); 0 = ninguno. */
        long firmaVertical;
    }

    /**
     * Malla que salió del plan (otro nivel de detalle): se sigue dibujando
     * hasta que las que la reemplazan estén listas y después se desvanece
     * con tramado mientras ellas aparecen (sección 6).
     */
    private static final class Saliente {
        final VertexBuffer[][] buffers;
        final float[] planos;
        final int[] verticesCara;
        final double origenX, origenZ, lado;
        final TipoMalla tipo;
        final long creadaNanos;
        /** Inicio del desvanecimiento; 0 = todavía esperando a su reemplazo (entera). */
        long inicioNanos;

        Saliente(EstadoCelda e, long ahora, boolean yaReemplazada) {
            buffers = e.buffers.clone();
            Arrays.fill(e.buffers, null);
            planos = e.planos;
            verticesCara = e.verticesCara.clone();
            origenX = e.construidaCon.origenX();
            origenZ = e.construidaCon.origenZ();
            lado = e.construidaCon.ladoEnBloques();
            tipo = e.tipo;
            creadaNanos = ahora;
            inicioNanos = yaReemplazada ? ahora : 0;
            e.tieneMalla = false;
        }

        boolean solapa(EstadoCelda e) {
            PlanCeldas.Celda c = e.plan != null ? e.plan : e.construidaCon;
            return c != null && FundidoNiveles.solapan(origenX, origenZ, lado, c.origenX(), c.origenZ(), c.ladoEnBloques());
        }

        void cerrar() {
            for (VertexBuffer[] piezas : buffers) {
                if (piezas != null) {
                    for (VertexBuffer b : piezas) {
                        b.close();
                    }
                }
            }
        }
    }

    private final List<Saliente> salientes = new ArrayList<>();

    /**
     * Resultado del hilo de mallas: una malla por dirección de cara (null las
     * vacías). {@code mallas} y {@code memoria} null = nada que dibujar.
     */
    private record MallaLista(long clave, PlanCeldas.Celda celda, MeshData[][] mallas, float[] planos,
                              ByteBufferBuilder memoria, int chunksConDatos, TipoMalla tipo) {
    }

    public RenderLod(GeneradorLocal generador, BalanceCpuGpu balance) {
        this.generador = generador;
        this.balance = balance;
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
        } else if (!com.example.minecraftlodmod.benchmark.SesionCalibracion.enCurso()) {
            // La config se puede cambiar con el mundo abierto (preset, radio): se toma sin volver a entrar.
            ParametrosCalidad desdeConfig = ConfigLod.calidadCliente();
            if (!desdeConfig.equals(calidad)) {
                calidad = desdeConfig;
                chunkPlanX = Integer.MIN_VALUE;
                LOG.info("LOD: calidad nueva ({} chunks, umbral {} px)", calidad.radioLodChunks(), calidad.umbralPx());
            }
        }

        // Cambiaron las texturas o se prendió/apagó la opción: rearmar todas las
        // celdas (las viejas se siguen dibujando hasta que llegue su reemplazo).
        boolean usarTexturas = shaderTextura != null && PaletaTexturas.tabla() != null
                && ConfigLod.CLIENTE.texturasLod.get();
        boolean usarOclusion = ConfigLod.CLIENTE.oclusionAmbiental.get();
        boolean usarShaders = ShadersIris.enUso();
        boolean usarVoxy = false;
        if (usarShaders) {
            ShadersVoxy.revisar(); // contrato Voxy del pack: diagnóstico en el log y, con la opción, el programa
            usarVoxy = DibujoVoxy.listo();
        }
        if (versionTexturas != versionTexturasVista || usarTexturas != texturasEnUso
                || usarOclusion != oclusionEnUso || usarShaders != shadersEnUso || usarVoxy != voxyEnUso) {
            versionTexturasVista = versionTexturas;
            texturasEnUso = usarTexturas;
            oclusionEnUso = usarOclusion;
            if (usarShaders != shadersEnUso) {
                LOG.info("LOD: shaderpack {}; el LOD se dibuja {}", usarShaders ? "activo" : "apagado",
                        usarShaders ? "con el terreno del pack" : "con sus propios shaders");
            }
            if (usarVoxy != voxyEnUso) {
                LOG.info("LOD: contrato Voxy del pack {}", usarVoxy ? "en uso (el LOD se dibuja con voxy_opaque)" : "sin usar");
            }
            shadersEnUso = usarShaders;
            voxyEnUso = usarVoxy;
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
        long subidos = 0;
        for (int i = 0; i < SUBIDAS_POR_FRAME && subidos < BYTES_SUBIDA_POR_FRAME; i++) {
            MallaLista lista = listas.poll();
            if (lista == null) {
                return;
            }
            EstadoCelda estado = celdas.get(lista.clave());
            if (estado == null) {
                cerrar(lista);
                continue;
            }
            long ahora = System.nanoTime();
            boolean fundir = ConfigLod.CLIENTE.fundidoNiveles.get();
            boolean otroNivel = estado.tieneMalla && estado.construidaCon != null
                    && estado.construidaCon.nivel() != lista.celda().nivel();
            if (fundir && otroNivel && lista.mallas() != null) {
                // Misma celda, otro nivel: la vieja se desvanece mientras la nueva aparece.
                agregarSaliente(new Saliente(estado, ahora, true));
                estado.aparicionNanos = ahora;
            } else if (fundir && !estado.tieneMalla && lista.mallas() != null) {
                // Malla nueva: si tapa una saliente que espera, aparece junto con su desvanecimiento.
                estado.aparicionNanos = ahora;
                estado.esperando = salientes.stream().anyMatch(s -> s.inicioNanos == 0 && s.solapa(estado));
            }
            if (lista.mallas() == null) {
                cerrarBuffer(estado);
            } else {
                estado.vertices = 0;
                for (int cara = 0; cara < GeometriaLod.CARAS; cara++) {
                    MeshData[] piezas = lista.mallas()[cara];
                    VertexBuffer[] anteriores = estado.buffers[cara];
                    int n = piezas == null ? 0 : piezas.length;
                    VertexBuffer[] nuevos = n == 0 ? null : new VertexBuffer[n];
                    estado.verticesCara[cara] = 0;
                    for (int p = 0; p < n; p++) {
                        MeshData malla = piezas[p];
                        VertexBuffer buffer = anteriores != null && p < anteriores.length ? anteriores[p] : null;
                        if (buffer != null && buffer.getFormat() != null
                                && !buffer.getFormat().equals(malla.drawState().format())) {
                            // Otro formato (se prendió o apagó un shaderpack, o las texturas): VAO
                            // nuevo. Re-subir al mismo dejaba atributos del formato viejo (con Iris,
                            // las celdas cercanas salían a medio dibujar).
                            buffer.close();
                            buffer = null;
                        }
                        if (buffer == null) {
                            buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
                        }
                        estado.verticesCara[cara] += malla.drawState().vertexCount();
                        estado.bytesVertice = malla.drawState().format().getVertexSize();
                        subidos += (long) malla.drawState().vertexCount() * estado.bytesVertice;
                        buffer.bind();
                        buffer.upload(malla); // cierra el MeshData
                        nuevos[p] = buffer;
                    }
                    if (anteriores != null) {
                        for (int p = n; p < anteriores.length; p++) {
                            anteriores[p].close();
                        }
                    }
                    estado.buffers[cara] = nuevos;
                    estado.vertices += estado.verticesCara[cara];
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
        if (Math.abs(fovGrados - fovVisto) > 0.05) {
            fovVisto = fovGrados;
            fovCambioNanos = ahora;
        }
        boolean fovAnimandose = ahora - fovCambioNanos < FOV_QUIETO_NANOS;
        if (fovAnimandose && chunkPlanX != Integer.MIN_VALUE && ahora - ultimoPlanNanos < REPLANIFICAR_FOV_MAXIMO_NANOS) {
            return; // el plan nuevo se arma cuando el zoom termine de moverse
        }
        ParametrosCalidad c = calidad;
        balance.usarBase(c);
        // Con escalado, el detalle se mide en píxeles del mundo (la resolución interna), no de la pantalla.
        int alturaDibujo = Escalado.alturaDelMundo(mc);
        if (chunkX == chunkPlanX && chunkZ == chunkPlanZ && Math.abs(fovGrados - fovPlan) < 1 && !giro
                && alturaDibujo == alturaPlan
                && Math.abs(camara.y - yPlan) < REPLANIFICAR_ALTURA && ahora - ultimoPlanNanos < REPLANIFICAR_NANOS
                && balance.version() == versionBalancePlan
                && (ClienteVertical.version() == versionVerticalPlan || ahora - ultimoPlanNanos < REPLANIFICAR_VERTICAL_NANOS)) {
            return;
        }
        versionBalancePlan = balance.version();
        alturaPlan = alturaDibujo;
        miraPlanX = miraX;
        miraPlanZ = miraZ;
        chunkPlanX = chunkX;
        chunkPlanZ = chunkZ;
        fovPlan = fovGrados;
        yPlan = camara.y;
        ultimoPlanNanos = ahora;
        planesDesdeEstadistica++;
        try {
            planificar(mc, camara, store, chunkX, chunkZ, miraX, miraZ, fovNormal, alturaDibujo, ahora);
        } finally {
            nanosPlanMaximo = Math.max(nanosPlanMaximo, System.nanoTime() - ahora);
        }
    }

    private void planificar(Minecraft mc, Vec3 camara, RegionFileStore store, int chunkX, int chunkZ,
                            double miraX, double miraZ, double fovNormal, int alturaDibujo,
                            long ahora) {
        ParametrosCalidad c = calidad;
        // Detalle y radio del auto-ajuste (los del preset si está apagado).
        int radioChunks = balance.radioChunks(c);
        int distanciaVanillaChunks = mc.options.getEffectiveRenderDistance();
        boolean tierraReal = com.example.minecraftlodmod.tierra.TierraReal.radioPlaneta(mc.level) > 0;
        if (radioPlanetaActivo() > 0 && (ConfigLod.CLIENTE.horizonteReal.get() || tierraReal)) {
            // Horizonte real: hasta dónde se ve la superficie curva desde los ojos, en vez del radio del
            // preset; el auto-ajuste lo sigue recortando en la misma proporción que al del preset.
            // En Tierra real va siempre (el planeta a escala es chico: lo de atrás del horizonte no se ve).
            int horizonte = HorizonteCurvo.radioChunks(camara.y - mc.level.getSeaLevel(), radioPlanetaActivo(),
                    com.example.minecraftlodmod.tierra.TierraReal.relieveHorizonte(mc.level),
                    distanciaVanillaChunks + 2, ParametrosCalidad.RADIO_MAX);
            radioChunks = (int) Math.round(horizonte * (double) radioChunks / Math.max(1, c.radioLodChunks()));
            GeneradorLocal.radioHorizonteCliente = horizonte;
        } else {
            GeneradorLocal.radioHorizonteCliente = 0;
        }
        radioEnUso = radioChunks;
        // RAM para LOD llena primero con lo cercano: el store precarga alrededor de la cámara.
        store.ponerCentro(GeneradorLocal.idDimension(mc.level.dimension()),
                com.example.minecraftlodmod.generation.SectionExtractor.regionDe(chunkX),
                com.example.minecraftlodmod.generation.SectionExtractor.regionDe(chunkZ),
                radioChunks / com.example.minecraftlodmod.generation.SectionExtractor.LADO_REGION + 1);
        double umbralPx = balance.umbralPx(c);
        double distanciaUnBuffer = balance.distanciaUnBuffer();
        // Vanilla dibuja hasta su distancia de render; se deja un chunk de
        // solapamiento para que no queden huecos en el borde (vanilla queda encima).
        int distanciaVanilla = Math.max(0, mc.options.getEffectiveRenderDistance() - 1);
        // Chunks que vanilla YA tiene cargados dentro de su distancia: solo esos se le
        // dejan; el resto lo sigue dibujando el LOD hasta que llegue (sin huecos).
        Set<Long> deVanilla = new HashSet<>();
        Map<Long, RangoSecciones> parciales = new HashMap<>();
        Set<Long> consultados = new HashSet<>();
        long vanilla2 = (long) distanciaVanilla * distanciaVanilla;
        for (int dx = -distanciaVanilla; dx <= distanciaVanilla; dx++) {
            for (int dz = -distanciaVanilla; dz <= distanciaVanilla; dz++) {
                if ((long) dx * dx + (long) dz * dz < vanilla2) {
                    long claveChunk = PlanCeldas.claveChunk(chunkX + dx, chunkZ + dz);
                    consultados.add(claveChunk);
                    if (vanillaLoDibujo(mc, chunkX + dx, chunkZ + dz)) {
                        // Con la sincronización vertical, vanilla tiene solo parte de la columna:
                        // el LOD dibuja el resto (LOD vertical, ver cubico/ClienteVertical).
                        RangoSecciones parcial = ClienteVertical.rango(chunkX + dx, chunkZ + dz);
                        if (parcial == null) {
                            deVanilla.add(claveChunk);
                        } else {
                            parciales.put(claveChunk, parcial);
                        }
                    }
                }
            }
        }
        cargadoDesde.keySet().retainAll(consultados);
        Set<Long> cubiertos = Set.copyOf(deVanilla);
        Map<Long, RangoSecciones> verticales = Map.copyOf(parciales);
        versionVerticalPlan = ClienteVertical.version();
        // Con zoom, el detalle extra solo para lo que entra en el cono de la vista (+ margen).
        double aspecto = (double) mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight());
        double mediaApertura = Math.atan(Math.tan(Math.toRadians(fovGrados) / 2) * aspecto) + MARGEN_ZOOM;
        PlanCeldas.Vista vista = new PlanCeldas.Vista(miraX, miraZ, Math.toRadians(fovNormal), mediaApertura);
        // El tope visual es en píxeles de PANTALLA: con escalado se pasa a píxeles del mundo.
        double aPantalla = alturaDibujo / (double) Math.max(1, mc.getWindow().getHeight());
        PlanCeldas.configurar(ConfigLod.CLIENTE.pixelesMaximos.get() * aPantalla,
                TerrenoAproximado.CHUNKS_POR_REGION_DESDE * 16.0);
        List<PlanCeldas.Celda> plan = PlanCeldas.planificarConGrandes(camara.x, camara.z, radioChunks,
                distanciaVanilla, cubiertos::contains, vista, Math.toRadians(fovGrados), alturaDibujo,
                umbralPx);
        // Primero lo que se mira, después el margen, al final lo de atrás.
        plan.sort(Comparator.comparingDouble(celda -> prioridad(celda, camara, miraX, miraZ)));
        byte dimension = GeneradorLocal.idDimension(mc.level.dimension());
        boolean[] ocultas = ocultasPorRelieve(plan, camara, store, dimension, mc.level.getMinSection(),
                mc.level.getMaxSection(), radioChunks);

        Set<Long> vigentes = new HashSet<>();
        ordenDibujo.clear();
        int encoladas = 0;
        boolean bloque = shadersEnUso && !voxyEnUso;
        // Con el contrato Voxy, el formato compacto con texturas (es la entrada del vértice de ContratoVoxy).
        TipoMalla tipoEsperado = bloque ? TipoMalla.BLOQUE : texturasEnUso || voxyEnUso ? TipoMalla.TEXTURA : TipoMalla.PLANA;
        // Con shaders las texturas no se dibujan, pero dan el color promedio de cada cara.
        GeometriaLod.Texturas texturas = texturasEnUso || bloque || voxyEnUso ? PaletaTexturas.tabla() : null;
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
            long firma = celda.esGrande() ? 0 : firmaVertical(celda, verticales);
            boolean cambio = !celda.equals(estado.construidaCon)
                    || (estado.tieneMalla && estado.tipo != tipoEsperado)
                    || firma != estado.firmaVertical;
            boolean incompleta = estado.construidaCon != null
                    && estado.chunksConDatos < chunksDibujables(celda)
                    && ahora - estado.construidaNanos > RECONSTRUIR_INCOMPLETA_NANOS;
            double mitad = celda.ladoEnBloques() / 2.0;
            // Con shaderpack cada llamada pasa por el apply() de Iris: un buffer por celda.
            boolean unBuffer = bloque || celda.esGrande() || Math.hypot(celda.origenX() + mitad - camara.x,
                    celda.origenZ() + mitad - camara.z) > distanciaUnBuffer;
            if (!estado.enConstruccion && (cambio || incompleta) && encoladas < ENCOLADAS_POR_PLAN) {
                estado.enConstruccion = true;
                estado.firmaVertical = firma;
                encoladas++;
                hiloMallas.execute(new TareaMalla(prioridad(celda, camara, miraX, miraZ), secuenciaTareas.incrementAndGet(),
                        () -> armar(clave, celda, store, dimension, minSeccion, maxSeccion,
                                cubiertos, verticales, texturas, oclusion, unBuffer, bloque)));
            }
        }
        boolean fundir = ConfigLod.CLIENTE.fundidoNiveles.get();
        celdas.entrySet().removeIf(e -> {
            if (!vigentes.contains(e.getKey())) {
                EstadoCelda vieja = e.getValue();
                if (fundir && vieja.tieneMalla && vieja.construidaCon != null && !vieja.oculta) {
                    // Sigue dibujada hasta que lo que la reemplaza esté armado (sin huecos).
                    agregarSaliente(new Saliente(vieja, System.nanoTime(), false));
                } else {
                    cerrarBuffer(vieja);
                }
                return true;
            }
            return false;
        });
    }

    /** Hilo de mallas: lee los nodos del nivel elegido y arma los vértices de la celda. */
    private void armar(long clave, PlanCeldas.Celda celda, RegionFileStore store, byte dimension,
                       int minSeccion, int maxSeccion, Set<Long> deVanilla, Map<Long, RangoSecciones> verticales,
                       GeometriaLod.Texturas texturas, boolean oclusion, boolean unBuffer, boolean bloque) {
        long inicioArmado = System.nanoTime();
        try {
            GeometriaLod geometria = geometriaMallas.get();
            geometria.reiniciar();
            geometria.usarTexturas(texturas);
            geometria.descartarCarasSinLuz(ConfigLod.CLIENTE.descartarCuevas.get());
            geometria.usarOclusionAmbiental(oclusion);
            int conDatos = celda.esGrande()
                    ? armarTesela(geometria, celda, store, dimension, minSeccion, maxSeccion)
                    : armarCelda(geometria, celda, store, dimension, minSeccion, maxSeccion, deVanilla, verticales);
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
            MeshData[][] mallas = new MeshData[GeometriaLod.CARAS][];
            float[] planos = new float[2 * GeometriaLod.CARAS];
            if (unBuffer) {
                // Lejos (teselas y celdas pasada la distancia de agrupado, 768 bloques o la que fije el
                // auto-ajuste): un solo buffer con todas las caras.
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

    /**
     * Tope de vértices por buffer con VulkanMod (0.5.5): su buffer de índices de quads es
     * compartido y empieza con 65536 vértices; cuando un buffer más grande lo hace crecer,
     * libera el anterior y los buffers ya subidos siguen apuntando a él (índices basura:
     * espigas). Con mallas de hasta este tamaño nunca crece.
     */
    static final int MAX_VERTICES_VULKANMOD = 65536;

    /**
     * Una malla, partida en piezas de hasta {@link #MAX_VERTICES_VULKANMOD} vértices con
     * VulkanMod (una sola pieza si no).
     *
     * @param cara 0-5, o -1 para todas las caras en una sola malla
     */
    private static MeshData[] malla(GeometriaLod geometria, int cara, int n, VertexFormat formato,
                                    ByteBufferBuilder memoria, TipoMalla tipo) {
        int maximo = conVulkanMod() ? MAX_VERTICES_VULKANMOD : Integer.MAX_VALUE;
        MeshData[] piezas = new MeshData[n <= maximo ? 1 : (n - 1) / maximo + 1];
        if (tipo != TipoMalla.PLANA) {
            // Bytes escritos directo y envueltos en un MeshData como el de BufferBuilder
            // (que exige POSITION en float y no conoce el formato compacto).
            int tamVertice = formato.getVertexSize();
            int bytes = n * tamVertice;
            boolean partida = piezas.length > 1;
            ByteBuffer destino = partida ? MemoryUtil.memAlloc(bytes)
                    : MemoryUtil.memByteBuffer(memoria.reserve(bytes), bytes);
            try {
                if (tipo == TipoMalla.TEXTURA) {
                    geometria.escribirCompacto(destino, cara);
                } else {
                    geometria.escribirBloque(destino, cara, tamVertice == GeometriaLod.BYTES_BLOQUE_IRIS);
                }
                for (int p = 0; p < piezas.length; p++) {
                    int desde = p * maximo;
                    int cuantos = Math.min(maximo, n - desde);
                    if (partida) {
                        MemoryUtil.memCopy(MemoryUtil.memAddress(destino) + (long) desde * tamVertice,
                                memoria.reserve(cuantos * tamVertice), (long) cuantos * tamVertice);
                    }
                    piezas[p] = new MeshData(memoria.build(), new MeshData.DrawState(formato, cuantos,
                            VertexFormat.Mode.QUADS.indexCount(cuantos), VertexFormat.Mode.QUADS,
                            VertexFormat.IndexType.least(cuantos)));
                }
            } finally {
                if (partida) {
                    MemoryUtil.memFree(destino);
                }
            }
            return piezas;
        }
        int i = 0;
        for (int p = 0; p < piezas.length; p++) {
            BufferBuilder builder = new BufferBuilder(memoria, VertexFormat.Mode.QUADS, formato);
            int escritos = 0;
            for (; i < geometria.vertices() && escritos < maximo; i++) {
                if (cara < 0 || geometria.cara(i) == cara) {
                    builder.addVertex(geometria.x(i), geometria.y(i), geometria.z(i)).setColor(geometria.color(i));
                    escritos++;
                }
            }
            piezas[p] = builder.buildOrThrow();
        }
        return piezas;
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
                                  Set<Long> deVanilla, Map<Long, RangoSecciones> verticales) {
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
                // Vecinos que vanilla tiene solo en parte (sincronización vertical): por sección.
                RangoSecciones[] vecinoParcial = new RangoSecciones[4];
                int[][] lados = {{-1, 0, GeometriaLod.OMITIR_X_NEG}, {1, 0, GeometriaLod.OMITIR_X_POS},
                        {0, -1, GeometriaLod.OMITIR_Z_NEG}, {0, 1, GeometriaLod.OMITIR_Z_POS}};
                for (int l = 0; l < 4; l++) {
                    int vx = chunkX + lados[l][0], vz = chunkZ + lados[l][1];
                    long claveVecino = PlanCeldas.claveChunk(vx, vz);
                    if (deVanilla.contains(claveVecino)) {
                        omitidas |= lados[l][2];
                    } else {
                        vecinoParcial[l] = verticales.get(claveVecino);
                        lod[l] = tieneDatos(store, dimension, vx, vz);
                    }
                }
                // LOD vertical: de esta columna, vanilla dibuja este rango; el LOD, el resto.
                RangoSecciones propio = verticales.get(PlanCeldas.claveChunk(chunkX, chunkZ));
                if (!real) {
                    armarAproximado(geometria, store, dimension, nivel, chunkX, chunkZ, dx, dz,
                            minSeccion, maxSeccion, omitidas);
                    continue;
                }
                for (int sy = minSeccion; sy < maxSeccion; sy++) {
                    if (propio != null && propio.contiene(sy)) {
                        continue; // la dibuja vanilla
                    }
                    SuperVoxel[] grid = leerNodo.apply(new PosSeccion(chunkX, sy, chunkZ));
                    if (grid == SIN_NODO) {
                        continue;
                    }
                    int omitidasSeccion = omitidas;
                    for (int l = 0; l < 4; l++) {
                        if (vecinoParcial[l] != null && vecinoParcial[l].contiene(sy)) {
                            omitidasSeccion |= lados[l][2]; // a ese lado, a esa altura, dibuja vanilla
                        }
                    }
                    GreedyMesher.Vecinos vecinos = GreedyMesher.Vecinos.deGrillas(lado,
                            lod[0] ? existente(leerNodo.apply(new PosSeccion(chunkX - 1, sy, chunkZ))) : null,
                            lod[1] ? existente(leerNodo.apply(new PosSeccion(chunkX + 1, sy, chunkZ))) : null,
                            sy > minSeccion ? existente(leerNodo.apply(new PosSeccion(chunkX, sy - 1, chunkZ))) : null,
                            sy + 1 < maxSeccion ? existente(leerNodo.apply(new PosSeccion(chunkX, sy + 1, chunkZ))) : null,
                            lod[2] ? existente(leerNodo.apply(new PosSeccion(chunkX, sy, chunkZ - 1))) : null,
                            lod[3] ? existente(leerNodo.apply(new PosSeccion(chunkX, sy, chunkZ + 1))) : null);
                    geometria.agregarSeccion(grid, lado, dx * 16f, sy * 16f, dz * 16f, 16f / lado,
                            omitidasSeccion, vecinos);
                }
            }
        }
        return conDatos;
    }

    /**
     * Chunk sin datos reales pero con horizonte aproximado: el nivel pedido
     * si existe ({@link #nivelAproximadoDisponible}: 1 y 2 solo cerca del
     * jugador, 3 y 4 siempre); si no, el más fino que haya. Vecinas solo arriba y abajo: los costados de un chunk
     * aproximado se dibujan (lejos, costo chico).
     */
    private static void armarAproximado(GeometriaLod geometria, RegionFileStore store, byte dimension, int nivel,
                                        int chunkX, int chunkZ, int dx, int dz, int minSeccion, int maxSeccion,
                                        int omitidas) {
        RegionFileStore.ClaveRegion region = GeneradorLocal.claveRegion(dimension, chunkX, chunkZ);
        int nivelA = nivelAproximadoDisponible(store, region, nivel, chunkX, chunkZ);
        int lado = SectionExtractor.LADO >> nivelA;
        int total = SectionExtractor.voxelesPorNodo(nivelA);
        SuperVoxel[][] grillas = new SuperVoxel[maxSeccion - minSeccion][];
        for (int sy = minSeccion; sy < maxSeccion; sy++) {
            byte[] bytes = store.leer(region, TerrenoAproximado.claveNodo(nivelA, chunkX, sy, chunkZ));
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

    /**
     * El nivel aproximado más fino que tiene el chunk sin pasar del pedido:
     * cerca se aproxima en vóxeles de 2 o 4 bloques (marcas finas), el resto
     * solo en 3 y 4.
     */
    static int nivelAproximadoDisponible(RegionFileStore store, RegionFileStore.ClaveRegion region, int pedido,
                                         int chunkX, int chunkZ) {
        if (pedido >= TerrenoAproximado.NIVEL_MIN) {
            return Math.min(pedido, TerrenoAproximado.NIVEL_MAX);
        }
        boolean hay1 = store.contiene(region, TerrenoAproximado.claveMarcaFina(1, chunkX, chunkZ));
        if (pedido <= 1 && hay1) {
            return 1;
        }
        // Un chunk aproximado en nivel 1 también guardó su reducción a 2.
        if (hay1 || store.contiene(region, TerrenoAproximado.claveMarcaFina(2, chunkX, chunkZ))) {
            return 2;
        }
        return TerrenoAproximado.NIVEL_MIN;
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
        avanzarFundidos(System.nanoTime());
        float intensidadLluvia = lluvia(mc);
        float minimoLluvia = (mc.options.getEffectiveRenderDistance() + 4) * 16f;
        corteLluvia = intensidadLluvia <= 0 ? Float.MAX_VALUE : Math.max(minimoLluvia,
                radioEnUso * 16f * (1f - (float) RADIO_CON_LLUVIA * intensidadLluvia));
        // La niebla termina donde se corta: el borde queda dentro de ella.
        alcanceLodBloques = Math.min(alcance(camara), corteLluvia);
        alcancePorSector = ConfigLod.CLIENTE.nieblaSinDatos.get()
                ? nieblaSectores.alcances(mc.options.getEffectiveRenderDistance() * 16f + 48f) : null;
        if (alcancePorSector != null && corteLluvia < Float.MAX_VALUE) {
            for (int sector = 0; sector < alcancePorSector.length; sector++) {
                alcancePorSector[sector] = Math.min(alcancePorSector[sector], corteLluvia);
            }
        }
        if (shadersEnUso) {
            dibujarConShaderpack(mc, evento, camara);
            return;
        }
        farParaShaders = 0;
        ParametrosCalidad c = calidad;
        float far = Math.max(NEAR_LOD * 2, Math.max(c.radioLodChunks(), radioEnUso) * 16f * 1.5f);
        // La de vanilla (con balanceo de cámara, zoom y FOV reales) con near/far del LOD.
        Matrix4f proyeccion = PlanCeldas.conPlanosDeProfundidad(new Matrix4f(evento.getProjectionMatrix()),
                NEAR_LOD, far);

        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        // Todas las caras son antihorarias vistas desde afuera (GeometriaLod): culling normal.
        RenderSystem.enableCull();
        // La luz horneada es la del mediodía: el lightmap actual la lleva a la hora del día.
        int luzCielo = colorLuzCielo(mc);
        RenderSystem.setShaderColor(((luzCielo >> 16) & 0xFF) / 255f, ((luzCielo >> 8) & 0xFF) / 255f,
                (luzCielo & 0xFF) / 255f, 1f);
        dibujarPasada(evento, camara, proyeccion, TipoMalla.PLANA, GameRenderer.getPositionColorShader());
        ShaderInstance conTextura = shaderTextura;
        if (conTextura != null) {
            // Atlas propio del LOD (texturas del pack activo + modelos horneados), con mipmaps:
            // la textura se simplifica sola con la distancia.
            RenderSystem.setShaderTexture(0, PaletaTexturas.ATLAS);
            RenderSystem.setShaderTexture(1, PaletaTexturas.TABLA_SPRITES);
            dibujarPasada(evento, camara, proyeccion, TipoMalla.TEXTURA, conTextura);
        }
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        VertexBuffer.unbind();
        // Con lluvia la neblina se espesa (y lo de más allá de corteLluvia no se dibuja).
        float neblina = Math.max(ConfigLod.CLIENTE.neblinaAtmosferica.get().floatValue(),
                ConfigLod.CLIENTE.nieblaLluvia.get() ? NEBLINA_LLUVIA * lluvia(mc) : 0f);
        AcabadoLod.aplicar(mc, proyeccion, evento.getModelViewMatrix(), alcanceLodBloques,
                mc.options.getEffectiveRenderDistance() * 16f, neblina,
                ConfigLod.CLIENTE.oclusionPantalla.get() ? FUERZA_SSAO : 0f, alcancePorSector);
        if (ConfigLod.CLIENTE.nubesLejanas.get()) {
            NubesLejanas.dibujar(mc, evento.getModelViewMatrix(), proyeccion, camara,
                    evento.getPartialTick().getGameTimeDeltaPartialTick(false), alcanceLodBloques,
                    radioPlanetaActivo(),
                    mc.options.getEffectiveRenderDistance() * 16.0);
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
        if (voxyEnUso) {
            dibujarConContratoVoxy(evento, camara);
            return;
        }
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

    /**
     * Con el contrato Voxy del pack ({@link DibujoVoxy}): proyección propia del LOD
     * (como sin shaders; el far de vanilla no se toca), profundidad propia y el
     * voxy_opaque del pack. Las mallas son las del formato compacto con texturas.
     */
    private void dibujarConContratoVoxy(RenderLevelStageEvent evento, Vec3 camara) {
        farParaShaders = 0;
        if (ShadersIris.pasadaDeSombras()) {
            return; // el LOD no proyecta sombras (los packs usan su profundidad para las sombras lejanas)
        }
        float far = Math.max(NEAR_LOD * 2, Math.max(calidad.radioLodChunks(), radioEnUso) * 16f * 1.5f);
        Matrix4f proyeccion = PlanCeldas.conPlanosDeProfundidad(new Matrix4f(evento.getProjectionMatrix()), NEAR_LOD, far);
        double radioPlaneta = radioPlanetaActivo();
        double inicioCurva = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0;
        DesplazamientoCelda desplazamiento = DibujoVoxy.empezar(evento.getModelViewMatrix(), proyeccion, alcanceLodBloques,
                radioPlaneta > 0 ? HorizonteCurvo.coeficiente(radioPlaneta) : 0f, (float) inicioCurva);
        try {
            Malla m = new Malla(evento.getModelViewMatrix(), proyeccion, null, null, desplazamiento, null, camara,
                    false, inicioCurva, radioPlaneta);
            recorrerMallas(TipoMalla.TEXTURA, m, false);
        } finally {
            DibujoVoxy.terminar();
            VertexBuffer.unbind();
        }
    }

    /**
     * Color del lightmap con el cielo a pleno y sin luz de bloque, como 0xRRGGBB normalizado
     * al del mediodía: blanco de día, oscuro y azulado de noche. Sigue la hora, la lluvia,
     * los rayos, la visión nocturna y el brillo de la config, como el terreno vanilla. La
     * luz de bloque (antorchas) no se separa de la horneada: de noche se oscurece igual.
     */
    static int colorLuzCielo(Minecraft mc) {
        NativeImage pixeles = ((AccesoLightTexture) mc.gameRenderer.lightTexture()).minecraftlodmod$pixeles();
        if (pixeles == null) {
            return 0xFFFFFF;
        }
        return normalizarLuz(pixeles.getPixelRGBA(0, 15));
    }

    /** Valor del lightmap a pleno sol con el brillo por defecto (vanilla lo acerca a 0.75 un 4%). */
    static final int LUZ_MEDIODIA = 250;

    /**
     * Píxel ABGR del lightmap (como lo guarda NativeImage) a 0xRRGGBB, llevando
     * {@link #LUZ_MEDIODIA} a 255 para que de día el LOD quede igual que sin el ajuste.
     */
    static int normalizarLuz(int abgr) {
        int r = Math.min(255, (abgr & 0xFF) * 255 / LUZ_MEDIODIA);
        int g = Math.min(255, ((abgr >> 8) & 0xFF) * 255 / LUZ_MEDIODIA);
        int b = Math.min(255, ((abgr >> 16) & 0xFF) * 255 / LUZ_MEDIODIA);
        return r << 16 | g << 8 | b;
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

    /** Radio del planeta de la curvatura, en bloques. */
    static double radioCurvatura() {
        return ConfigLod.CLIENTE.radioCurvaturaKm.get() * 1000.0;
    }

    /**
     * Radio de la curvatura en uso, en bloques (0 = sin curvatura): en un mundo
     * Tierra real, el del planeta a su escala aunque la opción esté apagada
     * ({@code docs/tierra-real/04-integracion-lod.md}); si no, el de la config.
     */
    static double radioPlanetaActivo() {
        double tierra = com.example.minecraftlodmod.tierra.TierraReal.radioPlaneta(Minecraft.getInstance().level);
        if (tierra > 0) return tierra;
        return ConfigLod.CLIENTE.curvatura.get() ? radioCurvatura() : 0;
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
        // Curvatura: por vértice en el shader propio; si no lo tiene (colores planos, VulkanMod,
        // shaderpack), cada celda baja entera lo que corresponde a su centro.
        double radioPlaneta = radioPlanetaActivo();
        double inicioCurva = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0;
        Uniform curvatura = desplazamiento != null ? shader.getUniform("Curvatura") : null;
        boolean curvaPorCelda = radioPlaneta > 0 && curvatura == null;
        if (desplazamiento != null) {
            shader.setDefaultUniforms(VertexFormat.Mode.QUADS, evento.getModelViewMatrix(), proyeccion,
                    Minecraft.getInstance().getWindow());
            if (curvatura != null) {
                curvatura.set(radioPlaneta > 0 ? HorizonteCurvo.coeficiente(radioPlaneta) : 0f, (float) inicioCurva);
            }
            shader.apply();
        }
        // Fundido entre niveles: solo con el shader propio (sin él, las salientes se van al empezar).
        Uniform fundido = desplazamiento != null || tipo == TipoMalla.TEXTURA ? shader.getUniform("Fundido") : null;
        Malla m = new Malla(evento.getModelViewMatrix(), proyeccion, shader, desplazamiento, null, fundido, camara,
                curvaPorCelda, inicioCurva, radioPlaneta);
        recorrerMallas(tipo, m, fundido != null);
        if (fundido != null) {
            fundido.set(1f, 1f);
            fundido.upload();
        }
        if (desplazamiento != null) {
            desplazamiento.set(0f, 0f, 0f);
            shader.clear();
        }
    }

    /** Las celdas con malla del tipo pedido y las salientes (las que se desvanecen), en orden de dibujo. */
    private void recorrerMallas(TipoMalla tipo, Malla m, boolean conFundido) {
        long ahora = System.nanoTime();
        for (EstadoCelda estado : ordenDibujo) {
            if (!estado.tieneMalla || estado.construidaCon == null || estado.tipo != tipo
                    || estado.oculta || estado.esperando) {
                continue;
            }
            float visible = FundidoNiveles.fraccionEntrada(ahora, estado.aparicionNanos);
            if (visible >= 1f) {
                estado.aparicionNanos = 0;
            }
            m.dibujar(estado.buffers, estado.planos, estado.verticesCara, estado.construidaCon.origenX(),
                    estado.construidaCon.origenZ(), estado.construidaCon.ladoEnBloques(), conFundido ? visible : 1f,
                    true);
        }
        for (Saliente s : salientes) {
            if (s.tipo != tipo || (s.inicioNanos != 0 && !conFundido)) {
                continue;
            }
            float queda = 1f - FundidoNiveles.fraccionEntrada(ahora, s.inicioNanos);
            if (s.inicioNanos == 0) {
                queda = 1f;
            }
            m.dibujar(s.buffers, s.planos, s.verticesCara, s.origenX, s.origenZ, s.lado, queda, false);
        }
    }

    /** Lo común a dibujar las mallas de una pasada (celdas y salientes). */
    private final class Malla {
        final Matrix4f modelView, proyeccion;
        final ShaderInstance shader;
        final Uniform desplazamiento, fundido;
        /** Programa propio sin ShaderInstance (contrato Voxy): el desplazamiento va por acá. */
        final DesplazamientoCelda desplazamientoPrograma;
        final Vec3 camara;
        final boolean curvaPorCelda;
        final double inicioCurva, radioPlaneta;

        Malla(Matrix4f modelView, Matrix4f proyeccion, ShaderInstance shader, Uniform desplazamiento,
              DesplazamientoCelda desplazamientoPrograma, Uniform fundido, Vec3 camara, boolean curvaPorCelda,
              double inicioCurva, double radioPlaneta) {
            this.modelView = modelView;
            this.proyeccion = proyeccion;
            this.shader = shader;
            this.desplazamiento = desplazamiento;
            this.desplazamientoPrograma = desplazamientoPrograma;
            this.fundido = fundido;
            this.camara = camara;
            this.curvaPorCelda = curvaPorCelda;
            this.inicioCurva = inicioCurva;
            this.radioPlaneta = radioPlaneta;
        }

        /**
         * @param visible fracción visible del tramado (1 = entera)
         * @param entra   true la malla que aparece, false la que se desvanece (patrón complementario)
         */
        void dibujar(VertexBuffer[][] buffers, float[] planos, int[] verticesCara, double origenX, double origenZ,
                     double lado, float visible, boolean entra) {
            if (corteLluvia < Float.MAX_VALUE) {
                double dx = Math.max(0, Math.max(origenX - camara.x, camara.x - (origenX + lado)));
                double dz = Math.max(0, Math.max(origenZ - camara.z, camara.z - (origenZ + lado)));
                if (dx * dx + dz * dz > (double) corteLluvia * corteLluvia) {
                    return; // entera detrás de la neblina de la lluvia: la malla se guarda, no se dibuja
                }
            }
            float ox = (float) (origenX - camara.x);
            float oz = (float) (origenZ - camara.z);
            float oy = (float) -camara.y;
            if (curvaPorCelda) {
                double mitad = lado / 2.0;
                oy -= (float) HorizonteCurvo.bajada(Math.hypot(ox + mitad, oz + mitad), inicioCurva, radioPlaneta);
            }
            if (fundido != null) {
                fundido.set(visible, entra ? 1f : 0f);
                if (desplazamiento != null) {
                    fundido.upload();
                }
            }
            Matrix4f vista = null;
            if (desplazamiento != null) {
                desplazamiento.set(ox, oy, oz);
                desplazamiento.upload();
            } else if (desplazamientoPrograma != null) {
                desplazamientoPrograma.poner(ox, oy, oz);
            } else {
                vista = new Matrix4f(modelView).translate(ox, oy, oz);
            }
            for (int cara = 0; cara < GeometriaLod.CARAS; cara++) {
                VertexBuffer[] piezas = buffers[cara];
                double camaraEnEje = switch (cara >> 1) {
                    case 0 -> camara.x - origenX;
                    case 1 -> camara.y;
                    default -> camara.z - origenZ;
                };
                if (piezas == null || !GeometriaLod.caraVisible(cara, camaraEnEje, planos[2 * cara], planos[2 * cara + 1])) {
                    continue;
                }
                for (VertexBuffer buffer : piezas) {
                    buffer.bind();
                    if (desplazamiento != null || desplazamientoPrograma != null) {
                        buffer.draw();
                    } else {
                        buffer.drawWithShader(vista, proyeccion, shader);
                    }
                    llamadasUltimoFrame++;
                }
                verticesUltimoFrame += verticesCara[cara];
            }
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
        double radioOclusores = Math.min(RADIO_OCLUSORES, radioLodChunks * 16.0) * balance.factorOclusion();
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
     * poco ({@link #LECTURAS_POR_PLAN}) en un hilo propio y se refresca cada
     * {@link #VIGENCIA_NANOS}: lo que todavía no se leyó cuenta como "sin
     * datos", que nunca oculta nada. Antes se leía en el hilo de render, dentro
     * del cuadro (disco + descompresión de hasta 32 regiones por plan, y cada
     * 60 s vencían todas juntas): tirones al moverse. {@link #preparar} y
     * {@link #suelos} son del hilo de render; las lecturas llegan solas.
     */
    private static final class CacheRelieve implements OclusionRelieve.Relieve {
        static final int LECTURAS_POR_PLAN = 32;
        static final long VIGENCIA_NANOS = 60_000_000_000L;

        private record Datos(float[] suelos, float tope, long leidoNanos) {
        }

        private final Map<Long, Datos> regiones = new java.util.concurrent.ConcurrentHashMap<>();
        private final Set<Long> enCurso = java.util.concurrent.ConcurrentHashMap.newKeySet();
        private volatile byte dimension = -1;
        private java.util.concurrent.ExecutorService lector;

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
                    long clave = PlanCeldas.claveChunk(rx, rz);
                    Datos d = regiones.get(clave);
                    if ((d == null || ahora - d.leidoNanos() > VIGENCIA_NANOS) && !enCurso.contains(clave)) {
                        long dx = rx - centroX, dz = rz - centroZ;
                        faltan.add(new long[]{dx * dx + dz * dz, rx, rz});
                    }
                }
            }
            faltan.sort(Comparator.comparingLong(f -> f[0]));
            for (int i = 0; i < Math.min(LECTURAS_POR_PLAN - enCurso.size(), faltan.size()); i++) {
                int rx = (int) faltan.get(i)[1], rz = (int) faltan.get(i)[2];
                long clave = PlanCeldas.claveChunk(rx, rz);
                enCurso.add(clave);
                lector().execute(() -> {
                    try {
                        Datos leido = leer(store, dimension, rx, rz, minSeccion, maxSeccion, System.nanoTime());
                        if (this.dimension == dimension) {
                            regiones.put(clave, leido);
                        }
                    } catch (RuntimeException e) {
                        LOG.debug("LOD: no se pudo leer el relieve de la región {},{}", rx, rz, e);
                    } finally {
                        enCurso.remove(clave);
                    }
                });
            }
            // Las muy alejadas de la cámara ya no sirven: se sueltan.
            regiones.keySet().removeIf(k -> {
                int rx = (int) k.longValue(), rz = (int) (k >> 32);
                return rx < desdeX - 2 || rx > hastaX + 2 || rz < desdeZ - 2 || rz > hastaZ + 2;
            });
        }

        private java.util.concurrent.ExecutorService lector() {
            if (lector == null) {
                lector = java.util.concurrent.Executors.newSingleThreadExecutor(tarea -> {
                    Thread hilo = new Thread(tarea, "LOD-Relieve");
                    hilo.setDaemon(true);
                    hilo.setPriority(Thread.MIN_PRIORITY);
                    return hilo;
                });
            }
            return lector;
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

    /** Secciones con algún bloque que tiene el cliente en su distancia de render (medición de la sincronización vertical). */
    private static int seccionesConBloques(Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            return 0;
        }
        int distancia = mc.options.getEffectiveRenderDistance();
        int cx = mc.player.chunkPosition().x, cz = mc.player.chunkPosition().z;
        int total = 0;
        for (int x = cx - distancia; x <= cx + distancia; x++) {
            for (int z = cz - distancia; z <= cz + distancia; z++) {
                net.minecraft.world.level.chunk.LevelChunk chunk = mc.level.getChunkSource().getChunk(x, z,
                        net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
                if (chunk != null) {
                    for (net.minecraft.world.level.chunk.LevelChunkSection s : chunk.getSections()) {
                        if (!s.hasOnlyAir()) {
                            total++;
                        }
                    }
                }
            }
        }
        return total;
    }

    /** Huella de los rangos verticales de las columnas de la celda (0 si vanilla no tiene ninguna en parte). */
    private static long firmaVertical(PlanCeldas.Celda celda, Map<Long, RangoSecciones> verticales) {
        if (verticales.isEmpty()) {
            return 0;
        }
        long firma = 0;
        for (int dx = 0; dx < PlanCeldas.LADO_CELDA; dx++) {
            for (int dz = 0; dz < PlanCeldas.LADO_CELDA; dz++) {
                RangoSecciones r = verticales.get(PlanCeldas.claveChunk(celda.celdaX() * PlanCeldas.LADO_CELDA + dx,
                        celda.celdaZ() * PlanCeldas.LADO_CELDA + dz));
                if (r != null) {
                    firma = firma * 1_000_003L + r.empaquetado() * 31 + dx * PlanCeldas.LADO_CELDA + dz + 1;
                }
            }
        }
        return firma;
    }

    private static int chunksDibujables(PlanCeldas.Celda celda) {
        if (celda.esGrande()) {
            return 1; // "incompleta" = ninguna banda con datos todavía
        }
        return PlanCeldas.LADO_CELDA * PlanCeldas.LADO_CELDA - Integer.bitCount(celda.mascaraOmitidos());
    }

    /** Alcance de la niebla por sector ({@link NieblaSectores}), recalculado en cada cuadro. */
    private final NieblaSectores nieblaSectores = new NieblaSectores();
    private float[] alcancePorSector;

    /**
     * Borde más lejano (esquina de celda) entre las celdas con malla. De paso
     * cuenta, por sector, lo dibujado y la primera zona sin datos.
     */
    private float alcance(Vec3 camara) {
        double maximo = 0;
        nieblaSectores.reiniciar();
        for (EstadoCelda e : celdas.values()) {
            PlanCeldas.Celda p = e.construidaCon != null ? e.construidaCon : e.plan;
            if (p != null && !e.oculta) {
                double mitad = p.ladoEnBloques() / 2.0;
                double cx = p.origenX() + mitad - camara.x, cz = p.origenZ() + mitad - camara.z;
                boolean sinDatos = e.construidaCon == null || (!e.tieneMalla && e.chunksConDatos == 0);
                if (sinDatos) {
                    nieblaSectores.faltante(cx, cz, mitad * Math.sqrt(2));
                } else if (e.tieneMalla) {
                    nieblaSectores.dibujada(cx, cz, mitad * Math.sqrt(2));
                }
            }
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

    private void agregarSaliente(Saliente s) {
        if (salientes.size() >= FundidoNiveles.MAXIMO_SALIENTES) {
            salientes.remove(0).cerrar(); // la más vieja se va sin fundido
        }
        salientes.add(s);
    }

    /**
     * Una vez por cuadro: arranca el desvanecimiento de las salientes cuyo
     * reemplazo ya está armado (o que esperaron demasiado), libera las que
     * terminaron y suelta las mallas nuevas que esperaban.
     */
    private void avanzarFundidos(long ahora) {
        if (salientes.isEmpty()) {
            for (EstadoCelda e : ordenDibujo) {
                e.esperando = false;
            }
            return;
        }
        for (Saliente s : salientes) {
            if (s.inicioNanos != 0) {
                continue;
            }
            boolean listo = ahora - s.creadaNanos > FundidoNiveles.ESPERA_MAXIMA_NANOS;
            if (!listo) {
                listo = true;
                for (EstadoCelda e : ordenDibujo) {
                    if (!e.oculta && s.solapa(e) && (e.construidaCon == null || !e.construidaCon.equals(e.plan))) {
                        listo = false;
                        break;
                    }
                }
            }
            if (listo) {
                s.inicioNanos = ahora;
                for (EstadoCelda e : ordenDibujo) {
                    if (e.esperando && s.solapa(e)) {
                        e.esperando = false;
                        e.aparicionNanos = ahora;
                    }
                }
            }
        }
        salientes.removeIf(s -> {
            if (s.inicioNanos != 0 && ahora - s.inicioNanos >= FundidoNiveles.DURACION_NANOS) {
                s.cerrar();
                return true;
            }
            return false;
        });
        for (EstadoCelda e : ordenDibujo) {
            if (e.esperando && salientes.stream().noneMatch(s -> s.inicioNanos == 0 && s.solapa(e))) {
                e.esperando = false;
                e.aparicionNanos = ahora;
            }
        }
    }

    private void cerrarSalientes() {
        salientes.forEach(Saliente::cerrar);
        salientes.clear();
    }

    private static void cerrarBuffer(EstadoCelda estado) {
        for (int cara = 0; cara < GeometriaLod.CARAS; cara++) {
            if (estado.buffers[cara] != null) {
                for (VertexBuffer buffer : estado.buffers[cara]) {
                    buffer.close();
                }
                estado.buffers[cara] = null;
            }
        }
        estado.tieneMalla = false;
    }

    private static void cerrar(MallaLista lista) {
        if (lista.mallas() != null) {
            for (MeshData[] piezas : lista.mallas()) {
                if (piezas != null) {
                    for (MeshData malla : piezas) {
                        malla.close();
                    }
                }
            }
        }
        if (lista.memoria() != null) {
            lista.memoria().close();
        }
    }

    private void liberarTodo() {
        cerrarSalientes();
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
