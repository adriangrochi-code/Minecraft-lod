package com.example.minecraftlodmod.network;

import com.example.minecraftlodmod.config.ConfigLod;
import com.example.minecraftlodmod.config.PresupuestoMemoria;
import com.example.minecraftlodmod.generation.GeneradorLocal;
import com.example.minecraftlodmod.storage.RegionFileStore;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Cliente en multijugador (modo REMOTO, sección 10): un store local por
 * servidor ({@code .minecraft/minecraftlodmod/servidores/<servidor>/}) que es
 * un espejo de lo que el servidor tiene generado. El render lee de acá igual
 * que del store del servidor integrado en singleplayer; cada clave que busca
 * y no está ({@link RegionFileStore#observarFaltantes}) se convierte en el
 * pedido de su rebanada ({@link RebanadaId}), que el servidor manda entera.
 * Lo recibido queda en disco: al volver a entrar, el LOD aparece enseguida y
 * se refresca de a poco ({@link PedidosRebanadas}). Solo cliente.
 */
public final class EspejoServidor {

    private static final Logger LOG = LogUtils.getLogger();

    /** Rebanadas pedidas por tick como mucho (las primeras que el render buscó: lo más cercano). */
    static final int PEDIDOS_POR_TICK = 16;

    private static volatile RegionFileStore store;
    /** Huellas de lo recibido ({@link PedidosRebanadas}), junto al espejo. */
    private static volatile Path archivoHuellas;
    private static volatile byte dimension;
    private static final PedidosRebanadas pedidos = new PedidosRebanadas();
    private static volatile long recibidas, bytesRecibidos;
    private static volatile long sinCambios;

    private EspejoServidor() {
    }

    /** El store del servidor actual, o null (singleplayer, sin companion o desconectado). */
    public static RegionFileStore store() {
        return store;
    }

    /** Para el HUD: rebanadas en vuelo, recibidas (cuántas sin cambios) y lo bajado en esta sesión. */
    public static String resumen() {
        return pedidos.enVuelo() + " ↓ · " + recibidas + " ✓ (" + sinCambios + " =) · " + (bytesRecibidos >> 20) + " MB";
    }

    @SubscribeEvent
    public static void alEntrar(ClientPlayerNetworkEvent.LoggingIn evento) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.hasSingleplayerServer() || !ClienteLod.servidorTieneRebanadas()) {
            return;
        }
        cerrar();
        ServerData datos = mc.getCurrentServer();
        String nombre = datos == null ? "desconocido" : datos.ip;
        Path carpeta = mc.gameDirectory.toPath().resolve("minecraftlodmod").resolve("servidores")
                .resolve(nombre.replaceAll("[^A-Za-z0-9._-]", "_"));
        int ramMb = ConfigLod.calidadCliente().cacheRamMb();
        long bytes = Math.min(PresupuestoMemoria.para(ramMb, 1).bytesCacheRegiones(),
                Runtime.getRuntime().maxMemory() / 4);
        RegionFileStore nuevo = new RegionFileStore(carpeta, GeneradorLocal.VERSION_ALGORITMO, 3000, Math.max(4, bytes));
        nuevo.observarFaltantes(EspejoServidor::falta);
        pedidos.reiniciarTodo();
        ultimasHuellasNanos = System.nanoTime();
        archivoHuellas = carpeta.resolve("huellas.bin");
        if (java.nio.file.Files.exists(archivoHuellas)) {
            try (var entrada = new java.io.DataInputStream(new java.io.BufferedInputStream(
                    java.nio.file.Files.newInputStream(archivoHuellas)))) {
                pedidos.leerHuellas(entrada);
            } catch (IOException e) {
                LOG.warn("LOD: huellas del espejo ilegibles; se vuelve a pedir todo", e);
                pedidos.reiniciarTodo();
            }
        }
        recibidas = 0;
        bytesRecibidos = 0;
        store = nuevo;
        LOG.info("LOD: modo REMOTO, espejo del LOD del servidor en {}", carpeta);
    }

    @SubscribeEvent
    public static void alSalir(ClientPlayerNetworkEvent.LoggingOut evento) {
        cerrar();
    }

    private static void cerrar() {
        RegionFileStore viejo = store;
        store = null;
        guardarHuellas(viejo);
        pedidos.reiniciarTodo();
        if (viejo != null) {
            try {
                viejo.close();
            } catch (IOException e) {
                LOG.warn("LOD: no se pudo cerrar el espejo del servidor", e);
            }
        }
    }

    /** Cada cuánto se guardan las huellas aunque no haya desconexión prolija (cierre forzado, cuelgue). */
    static final long PERIODO_HUELLAS_NANOS = 60_000_000_000L;
    private static long ultimasHuellasNanos;
    private static final java.util.concurrent.atomic.AtomicBoolean guardandoHuellas =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Store a disco y después las huellas: una huella guardada sin sus datos en disco
     * haría creer que ya están. Sincronizado: lo llaman el hilo de fondo y la desconexión.
     */
    private static synchronized void guardarHuellas(RegionFileStore s) {
        Path huellas = archivoHuellas;
        if (s == null || huellas == null) {
            return;
        }
        try {
            s.vaciar();
            Path temporal = huellas.resolveSibling("huellas.bin.tmp");
            try (var salida = new java.io.DataOutputStream(new java.io.BufferedOutputStream(
                    java.nio.file.Files.newOutputStream(temporal)))) {
                pedidos.escribirHuellas(salida);
            }
            java.nio.file.Files.move(temporal, huellas, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            LOG.warn("LOD: no se pudieron guardar las huellas del espejo", e);
        }
    }

    /** Cualquier hilo (render, mallas): una clave que el render buscó y no estaba. */
    private static void falta(RegionFileStore.ClaveRegion region, long claveNodo) {
        if (region.dimensionId() != dimension) {
            return;
        }
        RebanadaId r = RebanadaId.de(region.regionX(), region.regionZ(), claveNodo);
        if (r != null) {
            pedidos.querer(r, System.nanoTime());
        }
    }

    @SubscribeEvent
    public static void alTerminarTick(ClientTickEvent.Post evento) {
        Minecraft mc = Minecraft.getInstance();
        if (store == null || mc.level == null) {
            return;
        }
        byte actual = GeneradorLocal.idDimension(mc.level.dimension());
        if (actual != dimension) {
            dimension = actual;
            pedidos.reiniciar();
            pedidos.dimension(actual);
        }
        long ahora = System.nanoTime();
        if (ahora - ultimasHuellasNanos > PERIODO_HUELLAS_NANOS && guardandoHuellas.compareAndSet(false, true)) {
            ultimasHuellasNanos = ahora;
            RegionFileStore s = store;
            Thread hilo = new Thread(() -> {
                try {
                    guardarHuellas(s);
                } finally {
                    guardandoHuellas.set(false);
                }
            }, "LOD-HuellasEspejo");
            hilo.setDaemon(true);
            hilo.start();
        }
        List<RebanadaId> lote = pedidos.aMandar(System.nanoTime(), PEDIDOS_POR_TICK);
        if (!lote.isEmpty()) {
            long[] huellas = new long[lote.size()];
            for (int i = 0; i < huellas.length; i++) {
                huellas[i] = pedidos.huella(lote.get(i));
            }
            PacketDistributor.sendToServer(new PedidoRebanadasPayload(new ArrayList<>(lote), huellas));
        }
    }

    /** Hilo de red: guarda lo recibido tal cual (ya viene comprimido). */
    static void recibir(RebanadaPayload parte) {
        RegionFileStore s = store;
        if (s == null || parte.dimensionId() != dimension) {
            return;
        }
        RegionFileStore.ClaveRegion region = new RegionFileStore.ClaveRegion(parte.dimensionId(),
                parte.rebanada().regionX(), parte.rebanada().regionZ());
        long bytes = 0;
        for (int i = 0; i < parte.claves().length; i++) {
            // Solo claves de la rebanada pedida: el servidor no puede escribir otra cosa en el espejo.
            if (RebanadaId.codigo(parte.claves()[i]) == parte.rebanada().codigo()) {
                s.guardarComprimido(region, parte.claves()[i], parte.datos()[i]);
                bytes += parte.datos()[i].length;
            }
        }
        bytesRecibidos += bytes;
        if (parte.ultima()) {
            recibidas++;
            if (parte.sinCambios()) {
                sinCambios++;
            } else {
                com.example.minecraftlodmod.render.RenderLod.regionConDatosNuevos(parte.rebanada().regionX(),
                        parte.rebanada().regionZ());
            }
            pedidos.recibida(parte.rebanada(), System.nanoTime(), parte.huella());
        }
    }
}
