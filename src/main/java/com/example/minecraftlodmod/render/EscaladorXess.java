package com.example.minecraftlodmod.render;

import com.example.minecraftlodmod.lwjglvk.VkCommandBuffer;
import com.example.minecraftlodmod.lwjglvk.VkInstance;
import com.example.minecraftlodmod.lwjglvk.VkPhysicalDevice;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLPaths;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.JNI;
import org.lwjgl.system.Library;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Intel XeSS Super Resolution sobre el puente Vulkan ({@link InteropVulkan}).
 *
 * La biblioteca de Intel ({@code libxess.dll}, solo Windows) no viene con el
 * mod: se busca en {@code .minecraft/minecraftlodmod/}, en la carpeta del
 * juego y en el PATH. Se llama con LWJGL a la API C de {@code xess_vk.h}
 * (structs en {@link XessParametros}); ningún código nativo propio.
 *
 * En GPUs que no son Intel, XeSS necesita instrucciones DP4a (GTX 10xx o
 * más nuevas, RX 5000 o más nuevas). Si no puede, avisa en el log y el
 * escalado cae al temporal propio.
 */
final class EscaladorXess implements EscaladorVulkan {

    private static final Logger LOG = LogUtils.getLogger();
    static final String NOMBRE_DLL = "libxess.dll";

    private final SharedLibrary biblioteca;
    private final long fnVersion, fnExtInstancia, fnExtDispositivo, fnCaracteristicas, fnCrear, fnIniciar,
            fnEjecutar, fnDestruir;
    private final ByteBuffer parametros = MemoryUtil.memAlloc(XessParametros.BYTES_EJECUCION);
    private long contexto;
    private int anchoEntrada, altoEntrada;

    private EscaladorXess(SharedLibrary biblioteca) {
        this.biblioteca = biblioteca;
        fnVersion = funcion("xessGetVersion");
        fnExtInstancia = funcion("xessVKGetRequiredInstanceExtensions");
        fnExtDispositivo = funcion("xessVKGetRequiredDeviceExtensions");
        fnCaracteristicas = funcion("xessVKGetRequiredDeviceFeatures");
        fnCrear = funcion("xessVKCreateContext");
        fnIniciar = funcion("xessVKInit");
        fnEjecutar = funcion("xessVKExecute");
        fnDestruir = funcion("xessDestroyContext");
    }

    /** Carga libxess.dll, o null (con el motivo en el log) si no está o no es válida. */
    static EscaladorXess cargar() {
        List<Path> candidatas = List.of(FMLPaths.GAMEDIR.get().resolve("minecraftlodmod").resolve(NOMBRE_DLL),
                FMLPaths.GAMEDIR.get().resolve(NOMBRE_DLL));
        String donde = null;
        for (Path p : candidatas) {
            if (Files.isRegularFile(p)) {
                donde = p.toAbsolutePath().toString();
                break;
            }
        }
        try {
            SharedLibrary lib = Library.loadNative(EscaladorXess.class, "minecraftlodmod",
                    donde != null ? donde : NOMBRE_DLL);
            EscaladorXess xess = new EscaladorXess(lib);
            LOG.info("LOD: XeSS {} cargado de {}", xess.version(), lib.getPath());
            return xess;
        } catch (Throwable e) {
            LOG.warn("LOD: XeSS no disponible ({}). Copiá {} (del SDK de Intel XeSS) en {}", e.getMessage(),
                    NOMBRE_DLL, candidatas.get(0));
            return null;
        }
    }

    private long funcion(String nombre) {
        long f = biblioteca.getFunctionAddress(nombre);
        if (f == 0) {
            throw new IllegalStateException("a " + biblioteca.getName() + " le falta " + nombre
                    + " (¿versión de XeSS sin Vulkan?)");
        }
        return f;
    }

    private String version() {
        try (MemoryStack st = MemoryStack.stackPush()) {
            ByteBuffer v = st.calloc(8);
            if (JNI.callPI(MemoryUtil.memAddress(v), fnVersion) != 0) {
                return "?";
            }
            return (v.getShort(0) & 0xFFFF) + "." + (v.getShort(2) & 0xFFFF) + "." + (v.getShort(4) & 0xFFFF);
        }
    }

    @Override
    public String nombre() {
        return "Intel XeSS";
    }

    @Override
    public InteropVulkan.Requisitos requisitos() {
        return new InteropVulkan.Requisitos() {
            private int version = com.example.minecraftlodmod.lwjglvk.VK11.VK_API_VERSION_1_1;
            private final List<String> instancia = extensionesInstancia0();

            private List<String> extensionesInstancia0() {
                try (MemoryStack st = MemoryStack.stackPush()) {
                    IntBuffer n = st.mallocInt(1), api = st.mallocInt(1);
                    PointerBuffer lista = st.mallocPointer(1);
                    resultado(JNI.callPPPI(MemoryUtil.memAddress(n), MemoryUtil.memAddress(lista),
                            MemoryUtil.memAddress(api), fnExtInstancia), "xessVKGetRequiredInstanceExtensions");
                    version = api.get(0);
                    return cadenas(lista.get(0), n.get(0));
                }
            }

            @Override
            public int versionApi() {
                return version;
            }

            @Override
            public List<String> extensionesInstancia() {
                return instancia;
            }

            @Override
            public List<String> extensionesDispositivo(VkInstance inst, VkPhysicalDevice fisico) {
                try (MemoryStack st = MemoryStack.stackPush()) {
                    IntBuffer n = st.mallocInt(1);
                    PointerBuffer lista = st.mallocPointer(1);
                    resultado(JNI.callPPPPI(inst.address(), fisico.address(), MemoryUtil.memAddress(n),
                            MemoryUtil.memAddress(lista), fnExtDispositivo), "xessVKGetRequiredDeviceExtensions");
                    return cadenas(lista.get(0), n.get(0));
                }
            }

            @Override
            public long caracteristicas(VkInstance inst, VkPhysicalDevice fisico) {
                try (MemoryStack st = MemoryStack.stackPush()) {
                    PointerBuffer cadena = st.callocPointer(1);
                    resultado(JNI.callPPPI(inst.address(), fisico.address(), MemoryUtil.memAddress(cadena),
                            fnCaracteristicas), "xessVKGetRequiredDeviceFeatures");
                    return cadena.get(0);
                }
            }
        };
    }

    @Override
    public void preparar(InteropVulkan vk, int anchoEntrada, int altoEntrada, int anchoSalida, int altoSalida) {
        this.anchoEntrada = anchoEntrada;
        this.altoEntrada = altoEntrada;
        try (MemoryStack st = MemoryStack.stackPush()) {
            if (contexto == 0) {
                PointerBuffer ctx = st.callocPointer(1);
                resultado(JNI.callPPPPI(vk.instancia().address(), vk.fisico().address(), vk.dispositivo().address(),
                        MemoryUtil.memAddress(ctx), fnCrear), "xessVKCreateContext");
                contexto = ctx.get(0);
            }
            int calidad = XessParametros.calidadPara((double) anchoSalida / anchoEntrada);
            ByteBuffer inicio = XessParametros.inicio(st.calloc(XessParametros.BYTES_INICIO), anchoSalida, altoSalida,
                    calidad, XessParametros.FLAG_ENTRADA_LDR);
            resultado(JNI.callPPI(contexto, MemoryUtil.memAddress(inicio), fnIniciar), "xessVKInit");
            LOG.info("LOD: XeSS listo: {}x{} → {}x{} (preset {})", anchoEntrada, altoEntrada, anchoSalida, altoSalida,
                    calidad);
        }
    }

    @Override
    public void grabar(VkCommandBuffer cmd, Entradas e, double jitterX, double jitterY, boolean reiniciar) {
        XessParametros.ejecucion(parametros, vista(e.color()), vista(e.velocidad()), vista(e.profundidad()),
                vista(e.salida()), (float) jitterX, (float) jitterY, reiniciar, anchoEntrada, altoEntrada);
        resultado(JNI.callPPPI(contexto, cmd.address(), MemoryUtil.memAddress(parametros), fnEjecutar), "xessVKExecute");
    }

    private static XessParametros.Vista vista(InteropVulkan.ImagenCompartida i) {
        return new XessParametros.Vista(i.vista, i.imagen, i.formatoVk, i.ancho, i.alto);
    }

    @Override
    public void close() {
        if (contexto != 0) {
            JNI.callPI(contexto, fnDestruir);
            contexto = 0;
        }
        MemoryUtil.memFree(parametros);
        biblioteca.free();
    }

    /** xess_result_t: negativo = error; positivo = advertencia (se sigue). */
    private static void resultado(int r, String que) {
        if (r < 0) {
            throw new IllegalStateException(que + " falló (xess_result_t " + r + ")");
        }
        if (r > 0) {
            LOG.warn("LOD: {} devolvió la advertencia {}", que, r);
        }
    }

    private static List<String> cadenas(long arreglo, int n) {
        List<String> lista = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            lista.add(MemoryUtil.memUTF8(MemoryUtil.memGetAddress(arreglo + (long) i * 8)));
        }
        return lista;
    }
}
