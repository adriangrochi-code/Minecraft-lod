package com.example.minecraftlodmod.render;

import com.example.minecraftlodmod.lwjglvk.VkCommandBuffer;
import com.example.minecraftlodmod.lwjglvk.VkInstance;
import com.example.minecraftlodmod.lwjglvk.VkPhysicalDevice;
import com.example.minecraftlodmod.lwjglvk.VkPhysicalDeviceVulkan12Features;
import com.example.minecraftlodmod.lwjglvk.VkPhysicalDeviceVulkan13Features;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLPaths;
import org.joml.Matrix4f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.JNI;
import org.lwjgl.system.Library;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;
import org.lwjgl.system.libffi.FFICIF;
import org.lwjgl.system.libffi.FFIType;
import org.lwjgl.system.libffi.LibFFI;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.example.minecraftlodmod.lwjglvk.VK11.VK_API_VERSION_1_1;
import static com.example.minecraftlodmod.lwjglvk.VK12.VK_API_VERSION_1_2;
import static com.example.minecraftlodmod.lwjglvk.VK13.VK_API_VERSION_1_3;

/**
 * NVIDIA DLSS Super Resolution con Streamline, sobre el puente Vulkan
 * ({@link InteropVulkan}). Solo placas RTX.
 *
 * Streamline (SDK MIT de NVIDIA) no viene con el mod: sus DLL
 * ({@code sl.interposer.dll}, {@code sl.common.dll}, {@code sl.dlss.dll} y
 * {@code nvngx_dlss.dll}) van en {@code .minecraft/minecraftlodmod/streamline/}.
 * Se usa en modo de hookeo manual: el mod crea su dispositivo Vulkan con lo
 * que pide Streamline y se lo informa con slSetVulkanInfo. Structs en
 * {@link StreamlineParametros}; las funciones de 5 argumentos se llaman con
 * libffi (LWJGL no trae esas firmas).
 */
final class EscaladorDlss implements EscaladorVulkan {

    private static final Logger LOG = LogUtils.getLogger();
    static final String DLL = "sl.interposer.dll";
    /** Versión del SDK con la que se escribieron los structs (2.14.1). */
    static final long VERSION_SDK = (2L << 48) | (14L << 32) | (1L << 16) | 0xfedcL;
    /** Proyecto sin ID de aplicación de NVIDIA (modo desarrollo de Streamline). */
    private static final String ID_PROYECTO = "3f2b8c1e-6a7d-4e59-9c0b-2d4e6f8a1b3c";

    private final SharedLibrary biblioteca;
    private final long fnInit, fnShutdown, fnRequisitos, fnSoportada, fnInfoVulkan, fnFuncion, fnToken,
            fnConstantes, fnTags, fnEvaluar;
    private long fnOpciones;
    private final FFICIF cifTags = FFICIF.calloc(), cifEvaluar = FFICIF.calloc();
    private final PointerBuffer tiposTags = MemoryUtil.memAllocPointer(5), tiposEvaluar = MemoryUtil.memAllocPointer(5);
    private final ByteBuffer requisitos = MemoryUtil.memAlloc(StreamlineParametros.BYTES_REQUISITOS);
    private final ByteBuffer viewport = MemoryUtil.memAlloc(StreamlineParametros.BYTES_VIEWPORT);
    private final ByteBuffer constantes = MemoryUtil.memAlloc(StreamlineParametros.BYTES_CONSTANTES);
    private final ByteBuffer recursos = MemoryUtil.memAlloc(4 * StreamlineParametros.BYTES_RECURSO);
    private final ByteBuffer tags = MemoryUtil.memAlloc(4 * StreamlineParametros.BYTES_TAG);
    private VkPhysicalDeviceVulkan12Features caracteristicas12;
    private VkPhysicalDeviceVulkan13Features caracteristicas13;
    private boolean vulkanInformado;
    private int cuadro;
    private int anchoEntrada, altoEntrada;

    private EscaladorDlss(SharedLibrary biblioteca, Path carpeta) {
        this.biblioteca = biblioteca;
        fnInit = funcion("slInit");
        fnShutdown = funcion("slShutdown");
        fnRequisitos = funcion("slGetFeatureRequirements");
        fnSoportada = funcion("slIsFeatureSupported");
        fnInfoVulkan = funcion("slSetVulkanInfo");
        fnFuncion = funcion("slGetFeatureFunction");
        fnToken = funcion("slGetNewFrameToken");
        fnConstantes = funcion("slSetConstants");
        fnTags = funcion("slSetTagForFrame");
        fnEvaluar = funcion("slEvaluateFeature");
        // slSetTagForFrame(token*, viewport*, tags*, uint32, cmd*) y slEvaluateFeature(uint32, token*, inputs**, uint32, cmd*)
        FFIType p = LibFFI.ffi_type_pointer, u = LibFFI.ffi_type_uint32;
        tiposTags.put(p.address()).put(p.address()).put(p.address()).put(u.address()).put(p.address()).flip();
        tiposEvaluar.put(u.address()).put(p.address()).put(p.address()).put(u.address()).put(p.address()).flip();
        revisarFfi(LibFFI.ffi_prep_cif(cifTags, LibFFI.FFI_DEFAULT_ABI, LibFFI.ffi_type_sint32, tiposTags));
        revisarFfi(LibFFI.ffi_prep_cif(cifEvaluar, LibFFI.FFI_DEFAULT_ABI, LibFFI.ffi_type_sint32, tiposEvaluar));

        try (MemoryStack st = MemoryStack.stackPush()) {
            ByteBuffer rutaW = st.UTF16(carpeta.toAbsolutePath().toString());
            PointerBuffer rutas = st.pointers(rutaW);
            ByteBuffer funciones = st.malloc(4).putInt(0, StreamlineParametros.FEATURE_DLSS);
            ByteBuffer pref = StreamlineParametros.preferencias(st.malloc(StreamlineParametros.BYTES_PREFERENCIAS),
                    rutas.address(), 1, MemoryUtil.memAddress(rutaW), MemoryUtil.memAddress(funciones), 1,
                    MemoryUtil.memAddress(st.UTF8("1.21.1")), MemoryUtil.memAddress(st.UTF8(ID_PROYECTO)));
            resultado(JNI.callPJI(MemoryUtil.memAddress(pref), VERSION_SDK, fnInit), "slInit");
        }
        StreamlineParametros.requisitos(requisitos);
        resultado(JNI.callJPI(StreamlineParametros.FEATURE_DLSS, MemoryUtil.memAddress(requisitos), fnRequisitos),
                "slGetFeatureRequirements(DLSS)");
        StreamlineParametros.viewport(viewport, 0);
    }

    /** Carga Streamline, o null (con el motivo en el log) si no está o no sirve. */
    static EscaladorDlss cargar() {
        Path carpeta = FMLPaths.GAMEDIR.get().resolve("minecraftlodmod").resolve("streamline");
        Path dll = carpeta.resolve(DLL);
        if (!Files.isRegularFile(dll)) {
            LOG.warn("LOD: DLSS no disponible: falta {} (con sl.common.dll, sl.dlss.dll y nvngx_dlss.dll del SDK "
                    + "de NVIDIA Streamline) en {}", DLL, carpeta);
            return null;
        }
        try {
            SharedLibrary lib = Library.loadNative(EscaladorDlss.class, "minecraftlodmod", dll.toString());
            EscaladorDlss dlss = new EscaladorDlss(lib, carpeta);
            LOG.info("LOD: Streamline cargado de {}", lib.getPath());
            return dlss;
        } catch (Throwable e) {
            LOG.warn("LOD: DLSS no disponible ({})", e.getMessage());
            return null;
        }
    }

    private long funcion(String nombre) {
        long f = biblioteca.getFunctionAddress(nombre);
        if (f == 0) {
            throw new IllegalStateException(DLL + " no tiene " + nombre + " (¿versión vieja de Streamline?)");
        }
        return f;
    }

    @Override
    public String nombre() {
        return "NVIDIA DLSS";
    }

    @Override
    public InteropVulkan.Requisitos requisitos() {
        List<String> instancia = cadenas(StreamlineParametros.listaExtensionesInstancia(requisitos),
                StreamlineParametros.extensionesInstancia(requisitos));
        List<String> dispositivo = cadenas(StreamlineParametros.listaExtensionesDispositivo(requisitos),
                StreamlineParametros.extensionesDispositivo(requisitos));
        List<String> v12 = cadenas(StreamlineParametros.listaCaracteristicas12(requisitos),
                StreamlineParametros.caracteristicas12(requisitos));
        List<String> v13 = cadenas(StreamlineParametros.listaCaracteristicas13(requisitos),
                StreamlineParametros.caracteristicas13(requisitos));
        int colas = Math.max(StreamlineParametros.colasComputoPedidas(requisitos),
                StreamlineParametros.colasGraficosPedidas(requisitos));
        LOG.info("LOD: Streamline pide {} extensiones de instancia, {} de dispositivo, {}+{} características, {} colas",
                instancia.size(), dispositivo.size(), v12.size(), v13.size(), colas);
        return new InteropVulkan.Requisitos() {
            @Override
            public int versionApi() {
                return !v13.isEmpty() ? VK_API_VERSION_1_3 : !v12.isEmpty() ? VK_API_VERSION_1_2 : VK_API_VERSION_1_1;
            }

            @Override
            public List<String> extensionesInstancia() {
                return instancia;
            }

            @Override
            public List<String> extensionesDispositivo(VkInstance inst, VkPhysicalDevice fisico) {
                return dispositivo;
            }

            @Override
            public long caracteristicas(VkInstance inst, VkPhysicalDevice fisico) {
                return cadenaCaracteristicas(v12, v13);
            }

            @Override
            public int colasExtra() {
                return colas;
            }
        };
    }

    /** VkPhysicalDeviceVulkan12Features → 13 con las que pidió Streamline (por nombre de campo). */
    private long cadenaCaracteristicas(List<String> v12, List<String> v13) {
        long cadena = 0;
        if (!v13.isEmpty()) {
            caracteristicas13 = VkPhysicalDeviceVulkan13Features.calloc().sType$Default();
            activar(caracteristicas13, v13);
            cadena = caracteristicas13.address();
        }
        if (!v12.isEmpty()) {
            caracteristicas12 = VkPhysicalDeviceVulkan12Features.calloc().sType$Default().pNext(cadena);
            activar(caracteristicas12, v12);
            cadena = caracteristicas12.address();
        }
        return cadena;
    }

    private static void activar(Object struct, List<String> nombres) {
        for (String n : nombres) {
            try {
                struct.getClass().getMethod(n, boolean.class).invoke(struct, true);
            } catch (ReflectiveOperationException e) {
                LOG.warn("LOD: característica de Vulkan desconocida pedida por Streamline: {}", n);
            }
        }
    }

    @Override
    public void preparar(InteropVulkan vk, int anchoEntrada, int altoEntrada, int anchoSalida, int altoSalida) {
        this.anchoEntrada = anchoEntrada;
        this.altoEntrada = altoEntrada;
        try (MemoryStack st = MemoryStack.stackPush()) {
            if (!vulkanInformado) {
                ByteBuffer info = StreamlineParametros.infoVulkan(st.malloc(StreamlineParametros.BYTES_INFO_VULKAN),
                        vk.dispositivo().address(), vk.instancia().address(), vk.fisico().address(), vk.familiaCola(),
                        Math.min(1, vk.colasCreadas() - 1));
                resultado(JNI.callPI(MemoryUtil.memAddress(info), fnInfoVulkan), "slSetVulkanInfo");
                ByteBuffer adaptador = StreamlineParametros.adaptador(st.malloc(StreamlineParametros.BYTES_ADAPTADOR),
                        vk.fisico().address());
                int r = JNI.callJPI(StreamlineParametros.FEATURE_DLSS, MemoryUtil.memAddress(adaptador), fnSoportada);
                if (r != 0) {
                    throw new IllegalStateException("esta GPU no soporta DLSS (sl::Result " + r
                            + "; hace falta una NVIDIA RTX y driver actualizado)");
                }
                PointerBuffer fn = st.callocPointer(1);
                resultado(JNI.callJPPI(StreamlineParametros.FEATURE_DLSS, MemoryUtil.memAddress(st.UTF8("slDLSSSetOptions")),
                        fn.address(), fnFuncion), "slGetFeatureFunction(slDLSSSetOptions)");
                fnOpciones = fn.get(0);
                vulkanInformado = true;
            }
            int modo = StreamlineParametros.modoPara((double) anchoEntrada / anchoSalida);
            ByteBuffer opciones = StreamlineParametros.opcionesDlss(st.malloc(StreamlineParametros.BYTES_OPCIONES_DLSS),
                    modo, anchoSalida, altoSalida);
            resultado(JNI.callPPI(MemoryUtil.memAddress(viewport), MemoryUtil.memAddress(opciones), fnOpciones),
                    "slDLSSSetOptions");
            LOG.info("LOD: DLSS listo en {}: {}x{} → {}x{} (modo {})", vk.nombreGpu(), anchoEntrada, altoEntrada,
                    anchoSalida, altoSalida, modo);
        }
    }

    @Override
    public void grabar(VkCommandBuffer cmd, Entradas e, double jitterX, double jitterY, boolean reiniciar) {
        try (MemoryStack st = MemoryStack.stackPush()) {
            PointerBuffer token = st.callocPointer(1);
            ByteBuffer indice = st.malloc(4).putInt(0, cuadro++);
            resultado(JNI.callPPI(token.address(), MemoryUtil.memAddress(indice), fnToken), "slGetNewFrameToken");

            Camara c = e.camara();
            Matrix4f p = c.proyeccion();
            Matrix4f v = c.vista();
            float[] vistaAClip = p.get(new float[16]);
            float[] clipAVista = new Matrix4f(p).invert().get(new float[16]);
            float[] clipAAnterior = c.clipAAnterior().get(new float[16]);
            float[] anteriorAClip = new Matrix4f(c.clipAAnterior()).invert().get(new float[16]);
            // Filas de la rotación de la vista: derecha, arriba y (menos) adelante.
            float[] derecha = {v.m00(), v.m10(), v.m20()};
            float[] arriba = {v.m01(), v.m11(), v.m21()};
            float[] adelante = {-v.m02(), -v.m12(), -v.m22()};
            float fov = (float) (2 * Math.atan(1 / p.m11()));
            StreamlineParametros.constantes(constantes, vistaAClip, clipAVista, clipAAnterior, anteriorAClip,
                    (float) jitterX, (float) jitterY, 1f / anchoEntrada, 1f / altoEntrada, arriba, derecha, adelante,
                    c.cerca(), c.lejos(), fov, p.m11() / p.m00(), reiniciar);
            resultado(JNI.callPPPI(MemoryUtil.memAddress(constantes), token.get(0), MemoryUtil.memAddress(viewport),
                    fnConstantes), "slSetConstants");

            InteropVulkan.ImagenCompartida[] imagenes = {e.profundidad(), e.velocidad(), e.color(), e.salida()};
            int[] tipos = {StreamlineParametros.BUFFER_PROFUNDIDAD, StreamlineParametros.BUFFER_MOVIMIENTO,
                    StreamlineParametros.BUFFER_ENTRADA, StreamlineParametros.BUFFER_SALIDA};
            for (int i = 0; i < 4; i++) {
                InteropVulkan.ImagenCompartida im = imagenes[i];
                ByteBuffer r = MemoryUtil.memSlice(recursos, i * StreamlineParametros.BYTES_RECURSO,
                        StreamlineParametros.BYTES_RECURSO);
                StreamlineParametros.recurso(r, new StreamlineParametros.Imagen(im.imagen, im.memoria, im.vista,
                        im.formatoVk, im.ancho, im.alto, 0x1f));
                StreamlineParametros.tag(MemoryUtil.memSlice(tags, i * StreamlineParametros.BYTES_TAG,
                        StreamlineParametros.BYTES_TAG), MemoryUtil.memAddress(r), tipos[i], im.ancho, im.alto);
            }
            resultado(llamar(cifTags, fnTags, st, token.get(0), MemoryUtil.memAddress(viewport),
                    MemoryUtil.memAddress(tags), 4L, cmd.address()), "slSetTagForFrame");
            PointerBuffer entradas = st.pointers(MemoryUtil.memAddress(viewport));
            resultado(llamar(cifEvaluar, fnEvaluar, st, (long) StreamlineParametros.FEATURE_DLSS, token.get(0),
                    entradas.address(), 1L, cmd.address()), "slEvaluateFeature(DLSS)");
        }
    }

    /** Llamada con libffi: cada argumento es un valor de 8 bytes (puntero o uint32 extendido). */
    private static int llamar(FFICIF cif, long funcion, MemoryStack st, long... args) {
        ByteBuffer valores = st.malloc(8 * args.length);
        PointerBuffer punteros = st.mallocPointer(args.length);
        for (int i = 0; i < args.length; i++) {
            valores.putLong(8 * i, args[i]);
            punteros.put(i, MemoryUtil.memAddress(valores) + 8L * i);
        }
        ByteBuffer retorno = st.calloc(8);
        LibFFI.ffi_call(cif, funcion, retorno, punteros);
        return retorno.getInt(0);
    }

    @Override
    public void close() {
        try {
            JNI.callI(fnShutdown);
        } catch (RuntimeException e) {
            LOG.warn("LOD: slShutdown falló", e);
        }
        if (caracteristicas12 != null) {
            caracteristicas12.free();
        }
        if (caracteristicas13 != null) {
            caracteristicas13.free();
        }
        cifTags.free();
        cifEvaluar.free();
        MemoryUtil.memFree(tiposTags);
        MemoryUtil.memFree(tiposEvaluar);
        MemoryUtil.memFree(requisitos);
        MemoryUtil.memFree(viewport);
        MemoryUtil.memFree(constantes);
        MemoryUtil.memFree(recursos);
        MemoryUtil.memFree(tags);
        biblioteca.free();
    }

    private static void resultado(int r, String que) {
        if (r != 0) {
            throw new IllegalStateException(que + " falló (sl::Result " + r + ")");
        }
    }

    private static void revisarFfi(int r) {
        if (r != LibFFI.FFI_OK) {
            throw new IllegalStateException("libffi: ffi_prep_cif falló (" + r + ")");
        }
    }

    private static List<String> cadenas(long arreglo, int n) {
        List<String> lista = new ArrayList<>(n);
        for (int i = 0; arreglo != 0 && i < n; i++) {
            lista.add(MemoryUtil.memUTF8(MemoryUtil.memGetAddress(arreglo + (long) i * 8)));
        }
        return lista;
    }
}
