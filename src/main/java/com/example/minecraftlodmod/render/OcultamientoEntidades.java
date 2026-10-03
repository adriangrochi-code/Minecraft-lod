package com.example.minecraftlodmod.render;

import com.example.minecraftlodmod.config.ConfigLod;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.neoforged.fml.loading.LoadingModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Entidades y bloques con entidad (cofres, carteles, cabezas…) tapados por el
 * terreno no se dibujan: un hilo aparte prueba con {@link RayosVisibilidad}
 * cada cosa que vanilla quiso dibujar en los últimos cuadros (ya pasó el
 * frustum), y el cuadro siguiente se la saltea si quedó tapada. La misma idea
 * que el mod EntityCulling (si está instalado, este no hace nada).
 *
 * <p>Conservador: algo recién aparecido, una prueba vieja (más de
 * {@link #VIGENCIA_NS}) o hecha con la cámara en otro lugar cuentan como
 * visibles. No corre en la pasada de sombras de Iris (desde la luz sí se ve),
 * en espectador (la cámara atraviesa paredes) ni para lo que brilla.
 *
 * <p>También aplica la distancia máxima de entidades de las opciones
 * ({@code distanciaEntidades}), que no depende del hilo.
 */
public final class OcultamientoEntidades {

    private static final Logger LOG = LoggerFactory.getLogger(OcultamientoEntidades.class);

    /** Más lejos que esto no se prueba (se dibuja): rayos largos cuestan y vanilla ya corta antes. */
    private static final double MAX_BLOQUES = 128;
    private static final long PERIODO_MS = 10;
    private static final long VIGENCIA_NS = 250_000_000L;
    /** Lo que vanilla no pide dibujar en este tiempo se olvida. */
    private static final long OLVIDO_NS = 1_000_000_000L;
    /** Si la cámara se movió más que esto (al cuadrado) desde la prueba, no se le cree. */
    private static final double MOVIMIENTO_MAX2 = 2 * 2;

    private static final boolean CON_ENTITY_CULLING = LoadingModList.get() != null
            && LoadingModList.get().getModFileById("entityculling") != null;

    private static final Map<Object, Candidato> CANDIDATOS = new ConcurrentHashMap<>();
    private static volatile double camX, camY, camZ;
    private static volatile boolean hiloIniciado;

    /** Lo último que se sabe de una cosa a dibujar; escrito por los dos hilos sin candado (valores sueltos). */
    private static final class Candidato {
        volatile AABB caja;
        volatile long vistoNs;
        volatile boolean oculto;
        volatile long verificadoNs;
        volatile double verifX, verifY, verifZ;
    }

    private OcultamientoEntidades() {
    }

    // ------------------------------------------------------------------ hilo de render

    /** Desde {@code EntityRenderDispatcher#shouldRender}, solo si vanilla la iba a dibujar. */
    public static boolean ocultarEntidad(Entity entidad, double x, double y, double z) {
        Minecraft mc = Minecraft.getInstance();
        if (entidad == mc.getCameraEntity() || entidad.noCulling || mc.shouldEntityAppearGlowing(entidad)
                || mc.player != null && (entidad.hasPassenger(mc.player) || mc.player.hasPassenger(entidad))) {
            return false;
        }
        if (fueraDeDistancia(entidad.getX() - x, entidad.getY() - y, entidad.getZ() - z,
                !(entidad instanceof net.minecraft.world.entity.player.Player))) {
            return true;
        }
        return tapado(entidad, entidad.getBoundingBox(), x, y, z);
    }

    /** Desde {@code BlockEntityRenderDispatcher#render}; caja = la de dibujo del renderer. */
    public static boolean ocultarBloque(Object bloque, AABB caja, double x, double y, double z) {
        if (caja.isInfinite()) {
            return false;
        }
        if (fueraDeDistancia((caja.minX + caja.maxX) / 2 - x, (caja.minY + caja.maxY) / 2 - y,
                (caja.minZ + caja.maxZ) / 2 - z, true)) {
            return true;
        }
        return tapado(bloque, caja, x, y, z);
    }

    private static boolean fueraDeDistancia(double dx, double dy, double dz, boolean aplica) {
        if (!aplica || !ConfigLod.SPEC_CLIENTE.isLoaded()) {
            return false;
        }
        int max = ConfigLod.CLIENTE.distanciaEntidades.get();
        return max > 0 && !ShadersIris.pasadaDeSombras() && dx * dx + dy * dy + dz * dz > (double) max * max;
    }

    private static boolean tapado(Object clave, AABB caja, double x, double y, double z) {
        if (!activo()) {
            return false;
        }
        camX = x;
        camY = y;
        camZ = z;
        iniciarHilo();
        long ahora = System.nanoTime();
        // Caja y momento ya puestos al crearlo: el hilo de pruebas lo podía ver sin caja (NullPointerException,
        // que dejaba todo visible una vuelta) o con vistoNs en 0 (y lo olvidaba enseguida).
        Candidato c = CANDIDATOS.computeIfAbsent(clave, k -> {
            Candidato nuevo = new Candidato();
            nuevo.caja = caja;
            nuevo.vistoNs = ahora;
            return nuevo;
        });
        c.caja = caja;
        c.vistoNs = ahora;
        if (!c.oculto || ahora - c.verificadoNs > VIGENCIA_NS) {
            return false;
        }
        double dx = x - c.verifX, dy = y - c.verifY, dz = z - c.verifZ;
        return dx * dx + dy * dy + dz * dz <= MOVIMIENTO_MAX2;
    }

    private static boolean activo() {
        if (CON_ENTITY_CULLING || !ConfigLod.SPEC_CLIENTE.isLoaded()
                || !ConfigLod.CLIENTE.ocultarEntidadesTapadas.get()) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && (mc.player == null || !mc.player.isSpectator()) && !ShadersIris.pasadaDeSombras();
    }

    // ------------------------------------------------------------------ hilo de pruebas

    private static synchronized void iniciarHilo() {
        if (hiloIniciado) {
            return;
        }
        hiloIniciado = true;
        Thread hilo = new Thread(OcultamientoEntidades::bucle, "LOD ocultamiento de entidades");
        hilo.setDaemon(true);
        hilo.setPriority(Thread.NORM_PRIORITY - 1);
        hilo.start();
    }

    private static void bucle() {
        Opacidad opacidad = new Opacidad();
        while (true) {
            try {
                Thread.sleep(PERIODO_MS);
                ClientLevel nivel = Minecraft.getInstance().level;
                if (nivel == null || !activo()) {
                    CANDIDATOS.clear();
                    continue;
                }
                opacidad.empezar(nivel);
                double x = camX, y = camY, z = camZ;
                long ahora = System.nanoTime();
                for (Iterator<Candidato> it = CANDIDATOS.values().iterator(); it.hasNext(); ) {
                    Candidato c = it.next();
                    if (ahora - c.vistoNs > OLVIDO_NS) {
                        it.remove();
                        continue;
                    }
                    AABB caja = c.caja;
                    boolean visible = RayosVisibilidad.visible(x, y, z, caja.minX, caja.minY, caja.minZ,
                            caja.maxX, caja.maxY, caja.maxZ, opacidad, MAX_BLOQUES);
                    c.verifX = x;
                    c.verifY = y;
                    c.verifZ = z;
                    c.oculto = !visible;
                    c.verificadoNs = ahora;
                }
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                // Lectura del mundo desde otro hilo: un chunk que se descarga a mitad de la prueba. Se reintenta.
                LOG.debug("LOD: prueba de ocultamiento interrumpida", e);
                CANDIDATOS.values().forEach(c -> c.oculto = false);
            }
        }
    }

    /**
     * Bloques opacos del nivel del cliente, leídos desde el hilo de pruebas (las
     * lecturas de secciones no toman candados; a lo sumo se ve un bloque recién
     * cambiado un cuadro tarde). Recuerda lo ya consultado durante una vuelta.
     */
    private static final class Opacidad implements RayosVisibilidad.Opacidad {
        private static final byte OPACO = 1, TRANSPARENTE = 2;
        private final Long2ByteOpenHashMap memo = new Long2ByteOpenHashMap();
        private ClientLevel nivel;
        private long columna = Long.MIN_VALUE;
        private LevelChunk chunk;

        void empezar(ClientLevel nivel) {
            this.nivel = nivel;
            memo.clear();
            columna = Long.MIN_VALUE;
            chunk = null;
        }

        @Override
        public boolean opaco(int x, int y, int z) {
            long clave = BlockPos.asLong(x, y, z);
            byte v = memo.get(clave);
            if (v == 0) {
                v = leer(x, y, z) ? OPACO : TRANSPARENTE;
                memo.put(clave, v);
            }
            return v == OPACO;
        }

        private boolean leer(int x, int y, int z) {
            if (y < nivel.getMinBuildHeight() || y >= nivel.getMaxBuildHeight()) {
                return false;
            }
            long col = ((long) (x >> 4) << 32) | ((z >> 4) & 0xFFFFFFFFL);
            if (col != columna) {
                columna = col;
                chunk = nivel.getChunkSource().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) instanceof LevelChunk lc
                        ? lc : null;
            }
            if (chunk == null) {
                return false;
            }
            LevelChunkSection seccion = chunk.getSection(nivel.getSectionIndex(y));
            if (seccion.hasOnlyAir()) {
                return false;
            }
            BlockState estado = seccion.getBlockState(x & 15, y & 15, z & 15);
            return estado.isSolidRender(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        }
    }
}
