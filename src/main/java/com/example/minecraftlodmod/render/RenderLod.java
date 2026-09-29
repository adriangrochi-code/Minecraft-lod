package com.example.minecraftlodmod.render;

import com.example.minecraftlodmod.config.ConfigLod;
import com.example.minecraftlodmod.config.ParametrosCalidad;
import com.example.minecraftlodmod.generation.GeneradorLocal;
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
import java.util.concurrent.Executors;

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

    private final GeneradorLocal generador;
    private final ExecutorService hiloMallas = Executors.newSingleThreadExecutor(r -> {
        Thread hilo = new Thread(r, "LOD-Mallas");
        hilo.setDaemon(true);
        hilo.setPriority(Thread.MIN_PRIORITY);
        return hilo;
    });

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
        boolean enConstruccion;
        int chunksConDatos;
        long construidaNanos;
    }

    /** Resultado del hilo de mallas; {@code malla} y {@code memoria} null = la celda no tiene nada que dibujar. */
    private record MallaLista(long clave, PlanCeldas.Celda celda, MeshData malla, ByteBufferBuilder memoria,
                              int chunksConDatos) {
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

        subirMallasListas();
        Camera camara = evento.getCamera();
        replanificarSiHaceFalta(mc, camara.getPosition(), store);
        dibujar(mc, evento, camara.getPosition());
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
                estado.buffer.bind();
                estado.buffer.upload(lista.malla()); // cierra el MeshData
                VertexBuffer.unbind();
            }
            if (lista.memoria() != null) {
                lista.memoria().close();
            }
            estado.construidaCon = lista.celda();
            estado.chunksConDatos = lista.chunksConDatos();
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
        List<PlanCeldas.Celda> plan = PlanCeldas.planificar(camara.x, camara.z, c.radioLodChunks(),
                distanciaVanilla, Math.toRadians(fovGrados), mc.getWindow().getHeight(), c.umbralPx());
        plan.sort(Comparator.comparingDouble(celda -> distancia2(celda, camara)));

        Set<Long> vigentes = new HashSet<>();
        int encoladas = 0;
        byte dimension = GeneradorLocal.idDimension(mc.level.dimension());
        int minSeccion = mc.level.getMinSection();
        int maxSeccion = mc.level.getMaxSection();
        for (PlanCeldas.Celda celda : plan) {
            long clave = clave(celda.celdaX(), celda.celdaZ());
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
                hiloMallas.execute(() -> armar(clave, celda, store, dimension, minSeccion, maxSeccion,
                        chunkX, chunkZ, distanciaVanilla));
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
                       int minSeccion, int maxSeccion, int chunkCamX, int chunkCamZ, int distanciaVanilla) {
        try {
            GeometriaLod geometria = new GeometriaLod();
            int nivel = celda.nivel();
            int lado = SectionExtractor.LADO >> nivel;
            int total = SectionExtractor.voxelesPorNodo(nivel);
            int conDatos = 0;
            for (int dx = 0; dx < PlanCeldas.LADO_CELDA; dx++) {
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
                listas.add(new MallaLista(clave, celda, null, null, conDatos));
                return;
            }
            ByteBufferBuilder memoria = new ByteBufferBuilder(geometria.vertices() * BYTES_POR_VERTICE);
            BufferBuilder builder = new BufferBuilder(memoria, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            for (int i = 0; i < geometria.vertices(); i++) {
                builder.addVertex(geometria.x(i), geometria.y(i), geometria.z(i)).setColor(geometria.color(i));
            }
            listas.add(new MallaLista(clave, celda, builder.buildOrThrow(), memoria, conDatos));
        } catch (RuntimeException e) {
            LOG.error("LOD: no se pudo armar la celda {},{}", celda.celdaX(), celda.celdaZ(), e);
            listas.add(new MallaLista(clave, celda, null, null, 0));
        }
    }

    private void dibujar(Minecraft mc, RenderLevelStageEvent evento, Vec3 camara) {
        ParametrosCalidad c = calidad;
        float far = Math.max(NEAR_LOD * 2, c.radioLodChunks() * 16f * 1.5f);
        Matrix4f proyeccion = new Matrix4f().perspective((float) Math.toRadians(fovGrados),
                (float) mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight()), NEAR_LOD, far);

        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        // Sin culling: el orden de vértices todavía no está unificado por cara (Pista B).
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        var shader = GameRenderer.getPositionColorShader();
        for (EstadoCelda estado : celdas.values()) {
            if (estado.buffer == null || estado.construidaCon == null) {
                continue;
            }
            float ox = (float) (estado.construidaCon.celdaX() * PlanCeldas.LADO_CELDA * 16 - camara.x);
            float oz = (float) (estado.construidaCon.celdaZ() * PlanCeldas.LADO_CELDA * 16 - camara.z);
            Matrix4f vista = new Matrix4f(evento.getModelViewMatrix()).translate(ox, (float) -camara.y, oz);
            estado.buffer.bind();
            estado.buffer.drawWithShader(vista, proyeccion, shader);
        }
        VertexBuffer.unbind();
        RenderSystem.enableCull();
        // GL_DEPTH_BUFFER_BIT: el terreno vanilla se dibuja después, siempre delante del LOD.
        RenderSystem.clear(256, Minecraft.ON_OSX);
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

    private static int chunksDibujables(PlanCeldas.Celda celda) {
        return PlanCeldas.LADO_CELDA * PlanCeldas.LADO_CELDA - Integer.bitCount(celda.mascaraOmitidos());
    }

    private static double distancia2(PlanCeldas.Celda celda, Vec3 camara) {
        double cx = (celda.celdaX() * PlanCeldas.LADO_CELDA + PlanCeldas.LADO_CELDA / 2.0) * 16 - camara.x;
        double cz = (celda.celdaZ() * PlanCeldas.LADO_CELDA + PlanCeldas.LADO_CELDA / 2.0) * 16 - camara.z;
        return cx * cx + cz * cz;
    }

    private static long clave(int celdaX, int celdaZ) {
        return ((long) celdaX << 32) | (celdaZ & 0xFFFFFFFFL);
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
