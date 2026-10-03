package com.example.minecraftlodmod.render;

import com.example.minecraftlodmod.lwjglvk.VK;
import com.example.minecraftlodmod.lwjglvk.VkApplicationInfo;
import com.example.minecraftlodmod.lwjglvk.VkCommandBuffer;
import com.example.minecraftlodmod.lwjglvk.VkCommandBufferAllocateInfo;
import com.example.minecraftlodmod.lwjglvk.VkCommandBufferBeginInfo;
import com.example.minecraftlodmod.lwjglvk.VkCommandPoolCreateInfo;
import com.example.minecraftlodmod.lwjglvk.VkDevice;
import com.example.minecraftlodmod.lwjglvk.VkDeviceCreateInfo;
import com.example.minecraftlodmod.lwjglvk.VkDeviceQueueCreateInfo;
import com.example.minecraftlodmod.lwjglvk.VkExportMemoryAllocateInfo;
import com.example.minecraftlodmod.lwjglvk.VkExportSemaphoreCreateInfo;
import com.example.minecraftlodmod.lwjglvk.VkExtensionProperties;
import com.example.minecraftlodmod.lwjglvk.VkExternalMemoryImageCreateInfo;
import com.example.minecraftlodmod.lwjglvk.VkFenceCreateInfo;
import com.example.minecraftlodmod.lwjglvk.VkImageCreateInfo;
import com.example.minecraftlodmod.lwjglvk.VkImageMemoryBarrier;
import com.example.minecraftlodmod.lwjglvk.VkImageViewCreateInfo;
import com.example.minecraftlodmod.lwjglvk.VkInstance;
import com.example.minecraftlodmod.lwjglvk.VkInstanceCreateInfo;
import com.example.minecraftlodmod.lwjglvk.VkMemoryAllocateInfo;
import com.example.minecraftlodmod.lwjglvk.VkMemoryDedicatedAllocateInfo;
import com.example.minecraftlodmod.lwjglvk.VkMemoryGetFdInfoKHR;
import com.example.minecraftlodmod.lwjglvk.VkMemoryGetWin32HandleInfoKHR;
import com.example.minecraftlodmod.lwjglvk.VkMemoryRequirements;
import com.example.minecraftlodmod.lwjglvk.VkPhysicalDevice;
import com.example.minecraftlodmod.lwjglvk.VkPhysicalDeviceIDProperties;
import com.example.minecraftlodmod.lwjglvk.VkPhysicalDeviceMemoryProperties;
import com.example.minecraftlodmod.lwjglvk.VkPhysicalDeviceProperties2;
import com.example.minecraftlodmod.lwjglvk.VkQueue;
import com.example.minecraftlodmod.lwjglvk.VkQueueFamilyProperties;
import com.example.minecraftlodmod.lwjglvk.VkSemaphoreCreateInfo;
import com.example.minecraftlodmod.lwjglvk.VkSemaphoreGetFdInfoKHR;
import com.example.minecraftlodmod.lwjglvk.VkSemaphoreGetWin32HandleInfoKHR;
import com.example.minecraftlodmod.lwjglvk.VkSubmitInfo;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.logging.LogUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.EXTMemoryObject;
import org.lwjgl.opengl.EXTMemoryObjectFD;
import org.lwjgl.opengl.EXTMemoryObjectWin32;
import org.lwjgl.opengl.EXTSemaphore;
import org.lwjgl.opengl.EXTSemaphoreFD;
import org.lwjgl.opengl.EXTSemaphoreWin32;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Platform;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static com.example.minecraftlodmod.lwjglvk.KHRExternalMemoryFd.VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME;
import static com.example.minecraftlodmod.lwjglvk.KHRExternalMemoryFd.vkGetMemoryFdKHR;
import static com.example.minecraftlodmod.lwjglvk.KHRExternalMemoryWin32.VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME;
import static com.example.minecraftlodmod.lwjglvk.KHRExternalMemoryWin32.vkGetMemoryWin32HandleKHR;
import static com.example.minecraftlodmod.lwjglvk.KHRExternalSemaphoreFd.VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME;
import static com.example.minecraftlodmod.lwjglvk.KHRExternalSemaphoreFd.vkGetSemaphoreFdKHR;
import static com.example.minecraftlodmod.lwjglvk.KHRExternalSemaphoreWin32.VK_KHR_EXTERNAL_SEMAPHORE_WIN32_EXTENSION_NAME;
import static com.example.minecraftlodmod.lwjglvk.KHRExternalSemaphoreWin32.vkGetSemaphoreWin32HandleKHR;
import static com.example.minecraftlodmod.lwjglvk.VK10.*;
import static com.example.minecraftlodmod.lwjglvk.VK11.*;

/**
 * Puente OpenGL ↔ Vulkan para los escaladores que solo tienen versión Vulkan
 * (XeSS, DLSS): Minecraft sigue dibujando en OpenGL y el mod levanta un
 * dispositivo Vulkan propio, en la MISMA GPU (se compara el UUID del
 * dispositivo). Las imágenes se crean en Vulkan con memoria exportable y se
 * importan en OpenGL como texturas ({@code GL_EXT_memory_object}): los dos
 * ven la misma memoria, sin copias por la CPU.
 *
 * Sincronización: con semáforos compartidos ({@code GL_EXT_semaphore} y
 * {@code VK_KHR_external_semaphore}) todo queda del lado de la GPU: OpenGL
 * señala "entradas listas", Vulkan espera eso, trabaja y señala "salida
 * lista", y OpenGL la espera antes de leerla; la CPU sigue con el cuadro
 * siguiente. Sin semáforos (drivers viejos), glFinish antes y esperar la
 * fence después: correcto pero frena la CPU cada cuadro (el escalado
 * perdía FPS en vez de ganar). Todas las imágenes viven en layout GENERAL.
 *
 * Es la única parte del mod con llamadas GL directas y Vulkan: se usa solo
 * con los modos XESS/DLSS del escalado (experimentales).
 */
public final class InteropVulkan implements AutoCloseable {

    private static final Logger LOG = LogUtils.getLogger();

    // Constantes de GL_EXT_memory_object (LWJGL no las expone todas por nombre).
    static final int GL_TEXTURE_TILING_EXT = 0x9580, GL_OPTIMAL_TILING_EXT = 0x9584;
    static final int GL_DEDICATED_MEMORY_OBJECT_EXT = 0x9581, GL_DEVICE_UUID_EXT = 0x9597;
    static final int GL_HANDLE_TYPE_OPAQUE_FD_EXT = 0x9586, GL_HANDLE_TYPE_OPAQUE_WIN32_EXT = 0x9587;

    /** Lo que pide cada escalador para la instancia y el dispositivo. */
    public interface Requisitos {
        default int versionApi() {
            return VK_API_VERSION_1_1;
        }

        default List<String> extensionesInstancia() {
            return List.of();
        }

        default List<String> extensionesDispositivo(VkInstance instancia, VkPhysicalDevice fisico) {
            return List.of();
        }

        /** Cadena pNext de características a habilitar (0 = ninguna). */
        default long caracteristicas(VkInstance instancia, VkPhysicalDevice fisico) {
            return 0;
        }

        /** Colas extra de la misma familia que usa el escalador por su cuenta (Streamline). */
        default int colasExtra() {
            return 0;
        }
    }

    private static boolean vkCargado;

    private final boolean windows = Platform.get() == Platform.WINDOWS;
    private final VkInstance instancia;
    private final VkPhysicalDevice fisico;
    private final VkDevice dispositivo;
    private final VkQueue cola;
    private final int familia, colas;
    private final long comandos;
    private final VkCommandBuffer buffer;
    private final long fence;
    private final String nombreGpu;
    /** Semáforos compartidos (0 si el driver no los tiene): OpenGL → Vulkan y Vulkan → OpenGL. */
    private long semEntradas, semSalida;
    private int semEntradasGl, semSalidaGl;
    /** Hay un lote enviado cuya fence todavía no se esperó (con semáforos, se espera al empezar el siguiente). */
    private boolean loteEnVuelo;
    private final List<ImagenCompartida> imagenes = new ArrayList<>();

    private InteropVulkan(Requisitos req) {
        GLCapabilities gl = GL.getCapabilities();
        if (!gl.GL_EXT_memory_object || !(windows ? gl.GL_EXT_memory_object_win32 : gl.GL_EXT_memory_object_fd)) {
            throw new IllegalStateException("el driver OpenGL no tiene GL_EXT_memory_object"
                    + (windows ? "_win32" : "_fd") + " (compartir memoria con Vulkan)");
        }
        if (!vkCargado) {
            // LWJGL carga el loader de Vulkan sola al inicializar la clase VK.
            VK.getFunctionProvider();
            vkCargado = true;
        }
        try (MemoryStack st = MemoryStack.stackPush()) {
            VkApplicationInfo app = VkApplicationInfo.calloc(st).sType$Default()
                    .pApplicationName(st.UTF8("minecraftlodmod")).pEngineName(st.UTF8("minecraftlodmod"))
                    .apiVersion(Math.max(VK_API_VERSION_1_1, req.versionApi()));
            VkInstanceCreateInfo ci = VkInstanceCreateInfo.calloc(st).sType$Default().pApplicationInfo(app)
                    .ppEnabledExtensionNames(nombres(st, req.extensionesInstancia()));
            PointerBuffer pp = st.mallocPointer(1);
            revisar(vkCreateInstance(ci, null, pp), "vkCreateInstance");
            instancia = new VkInstance(pp.get(0), ci);
        }
        fisico = elegirGpu(uuidOpenGl());
        try (MemoryStack st = MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties2 props = VkPhysicalDeviceProperties2.calloc(st).sType$Default();
            vkGetPhysicalDeviceProperties2(fisico, props);
            nombreGpu = props.properties().deviceNameString();

            familia = familiaDeCola(st);
            colas = 1 + Math.min(req.colasExtra(), colasDeFamilia(st, familia) - 1);
            float[] prioridades = new float[colas];
            Arrays.fill(prioridades, 1f);
            Set<String> extensiones = new LinkedHashSet<>();
            extensiones.add(windows ? VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME : VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME);
            String extSemaforo = windows ? VK_KHR_EXTERNAL_SEMAPHORE_WIN32_EXTENSION_NAME
                    : VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME;
            boolean semaforosGl = gl.GL_EXT_semaphore && (windows ? gl.GL_EXT_semaphore_win32 : gl.GL_EXT_semaphore_fd);
            // -Dminecraftlodmod.sinSemaforos=true vuelve a la sincronización con glFinish (por si un driver falla).
            boolean conSemaforos = semaforosGl && !Boolean.getBoolean("minecraftlodmod.sinSemaforos")
                    && dispositivoSoporta(fisico, extSemaforo);
            if (conSemaforos) {
                extensiones.add(extSemaforo);
            }
            extensiones.addAll(req.extensionesDispositivo(instancia, fisico));
            VkDeviceQueueCreateInfo.Buffer colas = VkDeviceQueueCreateInfo.calloc(1, st).sType$Default()
                    .queueFamilyIndex(familia).pQueuePriorities(st.floats(prioridades));
            VkDeviceCreateInfo ci = VkDeviceCreateInfo.calloc(st).sType$Default()
                    .pNext(req.caracteristicas(instancia, fisico)).pQueueCreateInfos(colas)
                    .ppEnabledExtensionNames(nombres(st, List.copyOf(extensiones)));
            PointerBuffer pp = st.mallocPointer(1);
            revisar(vkCreateDevice(fisico, ci, null, pp), "vkCreateDevice");
            dispositivo = new VkDevice(pp.get(0), fisico, ci);
            vkGetDeviceQueue(dispositivo, familia, 0, pp);
            cola = new VkQueue(pp.get(0), dispositivo);

            LongBuffer lp = st.mallocLong(1);
            revisar(vkCreateCommandPool(dispositivo, VkCommandPoolCreateInfo.calloc(st).sType$Default()
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT).queueFamilyIndex(familia), null, lp),
                    "vkCreateCommandPool");
            comandos = lp.get(0);
            revisar(vkAllocateCommandBuffers(dispositivo, VkCommandBufferAllocateInfo.calloc(st).sType$Default()
                    .commandPool(comandos).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), pp),
                    "vkAllocateCommandBuffers");
            buffer = new VkCommandBuffer(pp.get(0), dispositivo);
            revisar(vkCreateFence(dispositivo, VkFenceCreateInfo.calloc(st).sType$Default(), null, lp), "vkCreateFence");
            fence = lp.get(0);
            if (conSemaforos) {
                try {
                    semEntradas = crearSemaforo();
                    semEntradasGl = importarSemaforo(semEntradas);
                    semSalida = crearSemaforo();
                    semSalidaGl = importarSemaforo(semSalida);
                } catch (RuntimeException e) {
                    LOG.warn("LOD: no se pudieron compartir semáforos con OpenGL ({}); se sincroniza con glFinish",
                            e.toString());
                    cerrarSemaforos();
                }
            }
        }
        LOG.info("LOD: Vulkan listo en {} (memoria compartida con OpenGL por {}, sincronización {})", nombreGpu,
                windows ? "handles de Windows" : "descriptores de archivo",
                semEntradas != 0 ? "por semáforos en la GPU" : "con glFinish (el driver no comparte semáforos)");
    }

    /** true si el puente sincroniza en la GPU (semáforos compartidos), sin frenar la CPU. */
    public boolean conSemaforos() {
        return semEntradas != 0;
    }

    private static boolean dispositivoSoporta(VkPhysicalDevice fisico, String extension) {
        try (MemoryStack st = MemoryStack.stackPush()) {
            IntBuffer n = st.mallocInt(1);
            vkEnumerateDeviceExtensionProperties(fisico, (ByteBuffer) null, n, null);
            VkExtensionProperties.Buffer props = VkExtensionProperties.malloc(n.get(0), st);
            vkEnumerateDeviceExtensionProperties(fisico, (ByteBuffer) null, n, props);
            for (int i = 0; i < props.capacity(); i++) {
                if (extension.equals(props.get(i).extensionNameString())) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Semáforo binario exportable (fd u handle de Windows). */
    private long crearSemaforo() {
        try (MemoryStack st = MemoryStack.stackPush()) {
            VkExportSemaphoreCreateInfo exportar = VkExportSemaphoreCreateInfo.calloc(st).sType$Default()
                    .handleTypes(windows ? VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT
                            : VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT);
            LongBuffer lp = st.mallocLong(1);
            revisar(vkCreateSemaphore(dispositivo, VkSemaphoreCreateInfo.calloc(st).sType$Default()
                    .pNext(exportar.address()), null, lp), "vkCreateSemaphore");
            return lp.get(0);
        }
    }

    /** El mismo semáforo visto desde OpenGL. */
    private int importarSemaforo(long semaforo) {
        int gl = EXTSemaphore.glGenSemaphoresEXT();
        try (MemoryStack st = MemoryStack.stackPush()) {
            if (windows) {
                PointerBuffer handle = st.mallocPointer(1);
                revisar(vkGetSemaphoreWin32HandleKHR(dispositivo, VkSemaphoreGetWin32HandleInfoKHR.calloc(st)
                        .sType$Default().semaphore(semaforo)
                        .handleType(VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT), handle),
                        "vkGetSemaphoreWin32HandleKHR");
                EXTSemaphoreWin32.glImportSemaphoreWin32HandleEXT(gl, GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, handle.get(0));
            } else {
                IntBuffer fd = st.mallocInt(1);
                revisar(vkGetSemaphoreFdKHR(dispositivo, VkSemaphoreGetFdInfoKHR.calloc(st).sType$Default()
                        .semaphore(semaforo).handleType(VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT), fd),
                        "vkGetSemaphoreFdKHR");
                EXTSemaphoreFD.glImportSemaphoreFdEXT(gl, GL_HANDLE_TYPE_OPAQUE_FD_EXT, fd.get(0));
            }
        }
        if (!EXTSemaphore.glIsSemaphoreEXT(gl)) {
            throw new IllegalStateException("OpenGL no importó el semáforo");
        }
        return gl;
    }

    private void cerrarSemaforos() {
        if (semEntradasGl != 0) {
            EXTSemaphore.glDeleteSemaphoresEXT(semEntradasGl);
        }
        if (semSalidaGl != 0) {
            EXTSemaphore.glDeleteSemaphoresEXT(semSalidaGl);
        }
        if (semEntradas != 0) {
            vkDestroySemaphore(dispositivo, semEntradas, null);
        }
        if (semSalida != 0) {
            vkDestroySemaphore(dispositivo, semSalida, null);
        }
        semEntradas = semSalida = 0;
        semEntradasGl = semSalidaGl = 0;
    }

    /** Levanta el puente, o null (con el motivo en el log) si esta PC no puede. */
    public static InteropVulkan crear(Requisitos req) {
        try {
            return new InteropVulkan(req);
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            LOG.warn("LOD: no se pudo levantar Vulkan para el escalado: {}", e.toString());
            return null;
        }
    }

    public VkInstance instancia() {
        return instancia;
    }

    public VkPhysicalDevice fisico() {
        return fisico;
    }

    public VkDevice dispositivo() {
        return dispositivo;
    }

    public String nombreGpu() {
        return nombreGpu;
    }

    /** Familia de la cola del puente; las colas 1.. de esa familia quedan para el escalador. */
    public int familiaCola() {
        return familia;
    }

    public int colasCreadas() {
        return colas;
    }

    /**
     * Imagen de {@code ancho × alto} en {@code formatoVk}, visible en OpenGL
     * como textura de formato interno {@code formatoGl} (con su framebuffer
     * para dibujarle encima).
     */
    public ImagenCompartida crearImagen(int ancho, int alto, int formatoVk, int formatoGl, int usos) {
        ImagenCompartida imagen = new ImagenCompartida(ancho, alto, formatoVk, formatoGl, usos);
        imagenes.add(imagen);
        ejecutar(cmd -> barrera(cmd, List.of(imagen), VK_IMAGE_LAYOUT_UNDEFINED), false);
        return imagen;
    }

    /** Libera una imagen creada con {@link #crearImagen}. */
    public void liberar(ImagenCompartida imagen) {
        if (imagen != null && imagenes.remove(imagen)) {
            vkDeviceWaitIdle(dispositivo);
            imagen.cerrar();
        }
    }

    /**
     * Graba y ejecuta un lote de comandos Vulkan sobre las imágenes
     * compartidas, esperando a que termine: glFinish antes (lo que OpenGL
     * escribió queda en memoria) y la fence después (lo que escribió Vulkan
     * ya está para OpenGL).
     */
    public void ejecutar(Consumer<VkCommandBuffer> grabar) {
        ejecutar(grabar, true);
    }

    private void ejecutar(Consumer<VkCommandBuffer> grabar, boolean conBarreras) {
        if (conBarreras && semEntradas != 0) {
            ejecutarConSemaforos(grabar);
            return;
        }
        esperarLoteAnterior();
        GL11.glFinish();
        try (MemoryStack st = MemoryStack.stackPush()) {
            vkResetCommandBuffer(buffer, 0);
            revisar(vkBeginCommandBuffer(buffer, VkCommandBufferBeginInfo.calloc(st).sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "vkBeginCommandBuffer");
            if (conBarreras) {
                barrera(buffer, imagenes, VK_IMAGE_LAYOUT_GENERAL);
            }
            grabar.accept(buffer);
            if (conBarreras) {
                barrera(buffer, imagenes, VK_IMAGE_LAYOUT_GENERAL);
            }
            revisar(vkEndCommandBuffer(buffer), "vkEndCommandBuffer");
            revisar(vkQueueSubmit(cola, VkSubmitInfo.calloc(st).sType$Default().pCommandBuffers(st.pointers(buffer)),
                    fence), "vkQueueSubmit");
            revisar(vkWaitForFences(dispositivo, fence, true, Long.MAX_VALUE), "vkWaitForFences");
            vkResetFences(dispositivo, fence);
        }
    }

    /**
     * Sin frenar la CPU: OpenGL señala que las entradas están escritas,
     * Vulkan espera eso en la GPU, corre y señala la salida, y OpenGL espera
     * esa señal (también en la GPU) antes de leerla. La fence de este lote se
     * espera recién al empezar el siguiente (para reusar el buffer de
     * comandos), cuando normalmente ya terminó.
     */
    private void ejecutarConSemaforos(Consumer<VkCommandBuffer> grabar) {
        esperarLoteAnterior();
        try (MemoryStack st = MemoryStack.stackPush()) {
            IntBuffer texturas = st.mallocInt(imagenes.size());
            IntBuffer layouts = st.mallocInt(imagenes.size());
            for (ImagenCompartida imagen : imagenes) {
                texturas.put(imagen.texturaGl);
                layouts.put(EXTSemaphore.GL_LAYOUT_GENERAL_EXT);
            }
            texturas.flip();
            layouts.flip();
            EXTSemaphore.glSignalSemaphoreEXT(semEntradasGl, null, texturas, layouts);
            GL11.glFlush(); // que la señal llegue a la GPU antes de que Vulkan la espere

            vkResetCommandBuffer(buffer, 0);
            revisar(vkBeginCommandBuffer(buffer, VkCommandBufferBeginInfo.calloc(st).sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "vkBeginCommandBuffer");
            barrera(buffer, imagenes, VK_IMAGE_LAYOUT_GENERAL);
            grabar.accept(buffer);
            barrera(buffer, imagenes, VK_IMAGE_LAYOUT_GENERAL);
            revisar(vkEndCommandBuffer(buffer), "vkEndCommandBuffer");
            revisar(vkQueueSubmit(cola, VkSubmitInfo.calloc(st).sType$Default()
                    .waitSemaphoreCount(1).pWaitSemaphores(st.longs(semEntradas))
                    .pWaitDstStageMask(st.ints(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT))
                    .pCommandBuffers(st.pointers(buffer))
                    .pSignalSemaphores(st.longs(semSalida)), fence), "vkQueueSubmit");
            loteEnVuelo = true;
            EXTSemaphore.glWaitSemaphoreEXT(semSalidaGl, null, texturas, layouts);
        }
    }

    private void esperarLoteAnterior() {
        if (loteEnVuelo) {
            revisar(vkWaitForFences(dispositivo, fence, true, Long.MAX_VALUE), "vkWaitForFences");
            vkResetFences(dispositivo, fence);
            loteEnVuelo = false;
        }
    }

    /** Todas las {@code imagenes} de {@code desde} a GENERAL, con todo lo anterior terminado. */
    private static void barrera(VkCommandBuffer cmd, List<ImagenCompartida> imagenes, int desde) {
        if (imagenes.isEmpty()) {
            return;
        }
        try (MemoryStack st = MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer barreras = VkImageMemoryBarrier.calloc(imagenes.size(), st);
            for (int i = 0; i < imagenes.size(); i++) {
                barreras.get(i).sType$Default()
                        .srcAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT)
                        .dstAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT)
                        .oldLayout(desde).newLayout(VK_IMAGE_LAYOUT_GENERAL)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .image(imagenes.get(i).imagen)
                        .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1));
            }
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0,
                    null, null, barreras);
        }
    }

    @Override
    public void close() {
        GL11.glFinish(); // OpenGL puede estar esperando un semáforo o leyendo una imagen compartida
        vkDeviceWaitIdle(dispositivo);
        cerrarSemaforos();
        for (ImagenCompartida imagen : imagenes) {
            imagen.cerrar();
        }
        imagenes.clear();
        vkDestroyFence(dispositivo, fence, null);
        vkDestroyCommandPool(dispositivo, comandos, null);
        vkDestroyDevice(dispositivo, null);
        vkDestroyInstance(instancia, null);
    }

    /** Imagen Vulkan con su memoria importada en OpenGL. */
    public final class ImagenCompartida {
        public final int ancho, alto, formatoVk;
        public final long imagen, memoria, vista;
        /** Textura y framebuffer de OpenGL sobre la misma memoria. */
        public final int texturaGl, framebufferGl;
        private final int memoriaGl;

        private ImagenCompartida(int ancho, int alto, int formatoVk, int formatoGl, int usos) {
            this.ancho = ancho;
            this.alto = alto;
            this.formatoVk = formatoVk;
            int tipoHandle = windows ? VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT
                    : VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT;
            long tamano;
            try (MemoryStack st = MemoryStack.stackPush()) {
                VkExternalMemoryImageCreateInfo externa = VkExternalMemoryImageCreateInfo.calloc(st).sType$Default()
                        .handleTypes(tipoHandle);
                VkImageCreateInfo ci = VkImageCreateInfo.calloc(st).sType$Default().pNext(externa.address())
                        .imageType(VK_IMAGE_TYPE_2D).format(formatoVk)
                        .extent(e -> e.width(ancho).height(alto).depth(1)).mipLevels(1).arrayLayers(1)
                        .samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL).usage(usos)
                        .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
                LongBuffer lp = st.mallocLong(1);
                revisar(vkCreateImage(dispositivo, ci, null, lp), "vkCreateImage");
                imagen = lp.get(0);

                VkMemoryRequirements req = VkMemoryRequirements.malloc(st);
                vkGetImageMemoryRequirements(dispositivo, imagen, req);
                tamano = req.size();
                VkMemoryDedicatedAllocateInfo dedicada = VkMemoryDedicatedAllocateInfo.calloc(st).sType$Default()
                        .image(imagen);
                VkExportMemoryAllocateInfo exportar = VkExportMemoryAllocateInfo.calloc(st).sType$Default()
                        .pNext(dedicada.address()).handleTypes(tipoHandle);
                revisar(vkAllocateMemory(dispositivo, VkMemoryAllocateInfo.calloc(st).sType$Default()
                        .pNext(exportar.address()).allocationSize(tamano)
                        .memoryTypeIndex(tipoDeMemoria(st, req.memoryTypeBits())), null, lp), "vkAllocateMemory");
                memoria = lp.get(0);
                revisar(vkBindImageMemory(dispositivo, imagen, memoria, 0), "vkBindImageMemory");

                revisar(vkCreateImageView(dispositivo, VkImageViewCreateInfo.calloc(st).sType$Default()
                        .image(imagen).viewType(VK_IMAGE_VIEW_TYPE_2D).format(formatoVk)
                        .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1)),
                        null, lp), "vkCreateImageView");
                vista = lp.get(0);

                memoriaGl = EXTMemoryObject.glCreateMemoryObjectsEXT();
                EXTMemoryObject.glMemoryObjectParameterivEXT(memoriaGl, GL_DEDICATED_MEMORY_OBJECT_EXT, st.ints(1));
                if (windows) {
                    PointerBuffer handle = st.mallocPointer(1);
                    revisar(vkGetMemoryWin32HandleKHR(dispositivo, VkMemoryGetWin32HandleInfoKHR.calloc(st)
                            .sType$Default().memory(memoria).handleType(tipoHandle), handle), "vkGetMemoryWin32HandleKHR");
                    EXTMemoryObjectWin32.glImportMemoryWin32HandleEXT(memoriaGl, tamano,
                            GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, handle.get(0));
                } else {
                    IntBuffer fd = st.mallocInt(1);
                    revisar(vkGetMemoryFdKHR(dispositivo, VkMemoryGetFdInfoKHR.calloc(st).sType$Default()
                            .memory(memoria).handleType(tipoHandle), fd), "vkGetMemoryFdKHR");
                    // OpenGL se queda con el descriptor: no se cierra acá.
                    EXTMemoryObjectFD.glImportMemoryFdEXT(memoriaGl, tamano, GL_HANDLE_TYPE_OPAQUE_FD_EXT, fd.get(0));
                }
            }
            texturaGl = GlStateManager._genTexture();
            GlStateManager._bindTexture(texturaGl);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL_TEXTURE_TILING_EXT, GL_OPTIMAL_TILING_EXT);
            EXTMemoryObject.glTexStorageMem2DEXT(GL11.GL_TEXTURE_2D, 1, formatoGl, ancho, alto, memoriaGl, 0);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, 33071);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, 33071);
            GlStateManager._bindTexture(0);
            framebufferGl = GlStateManager.glGenFramebuffers();
            GlStateManager._glBindFramebuffer(36160, framebufferGl);
            GlStateManager._glFramebufferTexture2D(36160, 36064, GL11.GL_TEXTURE_2D, texturaGl, 0);
            int estado = GlStateManager.glCheckFramebufferStatus(36160);
            GlStateManager._glBindFramebuffer(36160, 0);
            if (estado != 36053) {
                throw new IllegalStateException("framebuffer sobre memoria compartida incompleto: 0x"
                        + Integer.toHexString(estado));
            }
        }

        /** Para escribirle con OpenGL (viewport incluido). */
        public void atarParaEscribir() {
            GlStateManager._glBindFramebuffer(36160, framebufferGl);
            GlStateManager._viewport(0, 0, ancho, alto);
        }

        private void cerrar() {
            GlStateManager._glDeleteFramebuffers(framebufferGl);
            GlStateManager._deleteTexture(texturaGl);
            EXTMemoryObject.glDeleteMemoryObjectsEXT(memoriaGl);
            vkDestroyImageView(dispositivo, vista, null);
            vkDestroyImage(dispositivo, imagen, null);
            vkFreeMemory(dispositivo, memoria, null);
        }
    }

    /** UUID del dispositivo de OpenGL, o null si el driver no lo informa. */
    private static byte[] uuidOpenGl() {
        try (MemoryStack st = MemoryStack.stackPush()) {
            ByteBuffer uuid = st.calloc(16);
            EXTMemoryObject.glGetUnsignedBytei_vEXT(GL_DEVICE_UUID_EXT, 0, uuid);
            byte[] b = new byte[16];
            uuid.get(b);
            return Arrays.equals(b, new byte[16]) ? null : b;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** La GPU de Vulkan que es la misma que usa OpenGL; si no se puede saber, la primera dedicada. */
    private VkPhysicalDevice elegirGpu(byte[] uuidGl) {
        try (MemoryStack st = MemoryStack.stackPush()) {
            IntBuffer n = st.mallocInt(1);
            revisar(vkEnumeratePhysicalDevices(instancia, n, null), "vkEnumeratePhysicalDevices");
            if (n.get(0) == 0) {
                throw new IllegalStateException("no hay GPUs con Vulkan");
            }
            PointerBuffer lista = st.mallocPointer(n.get(0));
            revisar(vkEnumeratePhysicalDevices(instancia, n, lista), "vkEnumeratePhysicalDevices");
            VkPhysicalDevice primera = null, dedicada = null;
            for (int i = 0; i < n.get(0); i++) {
                VkPhysicalDevice gpu = new VkPhysicalDevice(lista.get(i), instancia);
                VkPhysicalDeviceIDProperties id = VkPhysicalDeviceIDProperties.calloc(st).sType$Default();
                VkPhysicalDeviceProperties2 props = VkPhysicalDeviceProperties2.calloc(st).sType$Default()
                        .pNext(id.address());
                vkGetPhysicalDeviceProperties2(gpu, props);
                byte[] uuid = new byte[16];
                id.deviceUUID().get(uuid);
                if (uuidGl != null && Arrays.equals(uuid, uuidGl)) {
                    return gpu;
                }
                primera = primera == null ? gpu : primera;
                if (dedicada == null && props.properties().deviceType() == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) {
                    dedicada = gpu;
                }
            }
            if (uuidGl != null) {
                LOG.warn("LOD: ninguna GPU de Vulkan coincide con la de OpenGL (UUID {}); se prueba igual",
                        HexFormat.of().formatHex(uuidGl));
            }
            return dedicada != null ? dedicada : primera;
        }
    }

    private int familiaDeCola(MemoryStack st) {
        IntBuffer n = st.mallocInt(1);
        vkGetPhysicalDeviceQueueFamilyProperties(fisico, n, null);
        VkQueueFamilyProperties.Buffer familias = VkQueueFamilyProperties.malloc(n.get(0), st);
        vkGetPhysicalDeviceQueueFamilyProperties(fisico, n, familias);
        for (int i = 0; i < familias.capacity(); i++) {
            int banderas = familias.get(i).queueFlags();
            if ((banderas & VK_QUEUE_GRAPHICS_BIT) != 0 && (banderas & VK_QUEUE_COMPUTE_BIT) != 0) {
                return i;
            }
        }
        throw new IllegalStateException("la GPU no tiene una cola de gráficos y cómputo");
    }

    private int colasDeFamilia(MemoryStack st, int indice) {
        IntBuffer n = st.mallocInt(1);
        vkGetPhysicalDeviceQueueFamilyProperties(fisico, n, null);
        VkQueueFamilyProperties.Buffer familias = VkQueueFamilyProperties.malloc(n.get(0), st);
        vkGetPhysicalDeviceQueueFamilyProperties(fisico, n, familias);
        return familias.get(indice).queueCount();
    }

    private int tipoDeMemoria(MemoryStack st, int bitsPermitidos) {
        VkPhysicalDeviceMemoryProperties props = VkPhysicalDeviceMemoryProperties.malloc(st);
        vkGetPhysicalDeviceMemoryProperties(fisico, props);
        int cualquiera = -1;
        for (int i = 0; i < props.memoryTypeCount(); i++) {
            if ((bitsPermitidos & (1 << i)) == 0) {
                continue;
            }
            if ((props.memoryTypes(i).propertyFlags() & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0) {
                return i;
            }
            cualquiera = cualquiera < 0 ? i : cualquiera;
        }
        if (cualquiera < 0) {
            throw new IllegalStateException("sin tipo de memoria para la imagen compartida");
        }
        return cualquiera;
    }

    private static PointerBuffer nombres(MemoryStack st, List<String> lista) {
        if (lista.isEmpty()) {
            return null;
        }
        PointerBuffer pb = st.mallocPointer(lista.size());
        for (String s : lista) {
            pb.put(st.UTF8(s));
        }
        return pb.flip();
    }

    static void revisar(int resultado, String que) {
        if (resultado != VK_SUCCESS) {
            throw new IllegalStateException(que + " falló (VkResult " + resultado + ")");
        }
    }
}
