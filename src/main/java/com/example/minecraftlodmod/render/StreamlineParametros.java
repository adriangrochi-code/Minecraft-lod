package com.example.minecraftlodmod.render;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Structs de NVIDIA Streamline 2.x (SDK MIT; headers {@code sl_core_types.h},
 * {@code sl_consts.h}, {@code sl_dlss.h}, {@code sl_helpers_vk.h}) escritos
 * a mano en ByteBuffers para llamar a {@code sl.interposer.dll} desde Java
 * (Windows x64). Lógica pura: tamaños y offsets testeados.
 *
 * Todos empiezan con {@code sl::BaseStructure}: next (8), structType (GUID de
 * 16 bytes) y structVersion (size_t) = 32 bytes.
 */
final class StreamlineParametros {

    static final int BYTES_BASE = 32;
    static final int BYTES_PREFERENCIAS = 144, BYTES_REQUISITOS = 184, BYTES_RECURSO = 112, BYTES_TAG = 64,
            BYTES_VIEWPORT = 40, BYTES_CONSTANTES = 456, BYTES_OPCIONES_DLSS = 88, BYTES_OPTIMOS_DLSS = 64,
            BYTES_INFO_VULKAN = 96, BYTES_ADAPTADOR = 56;

    static final int FEATURE_DLSS = 0;
    static final int BUFFER_PROFUNDIDAD = 0, BUFFER_MOVIMIENTO = 1, BUFFER_ENTRADA = 3, BUFFER_SALIDA = 4;
    static final int RENDER_API_VULKAN = 2;
    /** PreferenceFlags: eDisableCLStateTracking | eUseManualHooking. */
    static final long FLAGS = 1L | (1L << 2);
    static final int VALIDO_HASTA_EVALUAR = 2;
    static final int DLSS_APAGADO = 0, DLSS_RENDIMIENTO = 1, DLSS_EQUILIBRADO = 2, DLSS_CALIDAD = 3,
            DLSS_ULTRA_RENDIMIENTO = 4, DLSS_ULTRA_CALIDAD = 5, DLSS_DLAA = 6;
    static final byte FALSO = 0, VERDADERO = 1;
    static final float FLOAT_INVALIDO = Float.MAX_VALUE;

    // GUIDs (data1, data2, data3, data4[8]) y versión de cada struct.
    static final Guid PREFERENCIAS = new Guid(0x1ca10965, 0xbf8e, 0x432b, 0x8d, 0xa1, 0x67, 0x16, 0xd8, 0x79, 0xfb, 0x14);
    static final Guid REQUISITOS = new Guid(0x66714097, 0xac6d, 0x4bc6, 0x89, 0x15, 0x1e, 0x0f, 0x55, 0xa6, 0xb6, 0x1f);
    static final Guid RECURSO = new Guid(0x3a9d70cf, 0x2418, 0x4b72, 0x83, 0x91, 0x13, 0xf8, 0x72, 0x1c, 0x72, 0x61);
    static final Guid TAG = new Guid(0x4c6a5aad, 0xb445, 0x496c, 0x87, 0xff, 0x1a, 0xf3, 0x84, 0x5b, 0xe6, 0x53);
    static final Guid VIEWPORT = new Guid(0x171b6435, 0x9b3c, 0x4fc8, 0x99, 0x94, 0xfb, 0xe5, 0x25, 0x69, 0xaa, 0xa4);
    static final Guid CONSTANTES = new Guid(0xdcd35ad7, 0x4e4a, 0x4bad, 0xa9, 0x0c, 0xe0, 0xc4, 0x9e, 0xb2, 0x3a, 0xfe);
    static final Guid OPCIONES_DLSS = new Guid(0x6ac826e4, 0x4c61, 0x4101, 0xa9, 0x2d, 0x63, 0x8d, 0x42, 0x10, 0x57, 0xb8);
    static final Guid OPTIMOS_DLSS = new Guid(0xef1d0957, 0xfd58, 0x4df7, 0xb5, 0x04, 0x8b, 0x69, 0xd8, 0xaa, 0x6b, 0x76);
    static final Guid INFO_VULKAN = new Guid(0x0eed6fd5, 0x82cd, 0x43a9, 0xbd, 0xb5, 0x47, 0xa5, 0xba, 0x2f, 0x45, 0xd6);
    static final Guid ADAPTADOR = new Guid(0x0677315f, 0xa746, 0x4492, 0x9f, 0x42, 0xcb, 0x61, 0x42, 0xc9, 0xc3, 0xd4);

    record Guid(int d1, int d2, int d3, int... d4) {
    }

    /** Una imagen de Vulkan para sl::Resource. */
    record Imagen(long imagen, long memoria, long vista, int formato, int ancho, int alto, int usos) {
    }

    private StreamlineParametros() {
    }

    /** Cabecera BaseStructure; limpia el resto del struct. */
    static ByteBuffer base(ByteBuffer b, int bytes, Guid guid, int version) {
        b.order(ByteOrder.nativeOrder());
        for (int i = 0; i < bytes; i++) {
            b.put(i, (byte) 0);
        }
        b.putLong(0, 0);
        b.putInt(8, guid.d1()).putShort(12, (short) guid.d2()).putShort(14, (short) guid.d3());
        for (int i = 0; i < 8; i++) {
            b.put(16 + i, (byte) guid.d4()[i]);
        }
        b.putLong(24, version);
        return b;
    }

    /**
     * sl::Preferences: logs en {@code carpetaLogs}, plugins en {@code rutasPlugins}
     * (wchar_t** en UTF-16), hookeo manual y Vulkan.
     */
    static ByteBuffer preferencias(ByteBuffer b, long rutasPlugins, int cantidadRutas, long carpetaLogs,
                                   long funciones, int cantidadFunciones, long versionMotor, long idProyecto) {
        base(b, BYTES_PREFERENCIAS, PREFERENCIAS, 1);
        b.put(32, FALSO);             // showConsole
        b.putInt(36, 1);              // logLevel eDefault
        b.putLong(40, rutasPlugins).putInt(48, cantidadRutas);
        b.putLong(56, carpetaLogs);
        // allocateCallback, releaseCallback, logMessageCallback (64..88) en 0
        b.putLong(88, FLAGS);
        b.putLong(96, funciones).putInt(104, cantidadFunciones);
        b.putInt(108, 0);             // applicationId
        b.putInt(112, 0);             // engine eCustom
        b.putLong(120, versionMotor).putLong(128, idProyecto);
        b.putInt(136, RENDER_API_VULKAN);
        return b;
    }

    static ByteBuffer requisitos(ByteBuffer b) {
        return base(b, BYTES_REQUISITOS, REQUISITOS, 2);
    }

    // Lectura de sl::FeatureRequirements
    static int colasComputoPedidas(ByteBuffer b) {
        return b.getInt(104);
    }

    static int colasGraficosPedidas(ByteBuffer b) {
        return b.getInt(108);
    }

    static int extensionesDispositivo(ByteBuffer b) {
        return b.getInt(112);
    }

    static long listaExtensionesDispositivo(ByteBuffer b) {
        return b.getLong(120);
    }

    static int extensionesInstancia(ByteBuffer b) {
        return b.getInt(128);
    }

    static long listaExtensionesInstancia(ByteBuffer b) {
        return b.getLong(136);
    }

    static int caracteristicas12(ByteBuffer b) {
        return b.getInt(144);
    }

    static long listaCaracteristicas12(ByteBuffer b) {
        return b.getLong(152);
    }

    static int caracteristicas13(ByteBuffer b) {
        return b.getInt(160);
    }

    static long listaCaracteristicas13(ByteBuffer b) {
        return b.getLong(168);
    }

    static ByteBuffer adaptador(ByteBuffer b, long fisico) {
        base(b, BYTES_ADAPTADOR, ADAPTADOR, 1);
        b.putLong(48, fisico);
        return b;
    }

    static ByteBuffer infoVulkan(ByteBuffer b, long dispositivo, long instancia, long fisico, int familia,
                                 int primeraColaLibre) {
        base(b, BYTES_INFO_VULKAN, INFO_VULKAN, 3);
        b.putLong(32, dispositivo).putLong(40, instancia).putLong(48, fisico);
        b.putInt(56, primeraColaLibre).putInt(60, familia);   // cómputo
        b.putInt(64, primeraColaLibre).putInt(68, familia);   // gráficos
        return b;
    }

    static ByteBuffer viewport(ByteBuffer b, int valor) {
        base(b, BYTES_VIEWPORT, VIEWPORT, 1);
        b.putInt(32, valor);
        return b;
    }

    /** sl::Resource de una imagen 2D de Vulkan en layout GENERAL. */
    static ByteBuffer recurso(ByteBuffer b, Imagen i) {
        base(b, BYTES_RECURSO, RECURSO, 1);
        b.put(32, (byte) 0);          // ResourceType::eTex2d
        b.putLong(40, i.imagen()).putLong(48, i.memoria()).putLong(56, i.vista());
        b.putInt(64, 1);              // VK_IMAGE_LAYOUT_GENERAL
        b.putInt(68, i.ancho()).putInt(72, i.alto()).putInt(76, i.formato());
        b.putInt(80, 1).putInt(84, 1); // mipLevels, arrayLayers
        b.putInt(100, i.usos());
        return b;
    }

    static ByteBuffer tag(ByteBuffer b, long recurso, int tipo, int ancho, int alto) {
        base(b, BYTES_TAG, TAG, 1);
        b.putLong(32, recurso).putInt(40, tipo).putInt(44, VALIDO_HASTA_EVALUAR);
        b.putInt(48, 0).putInt(52, 0).putInt(56, ancho).putInt(60, alto); // extent: top, left, width, height
        return b;
    }

    /**
     * sl::Constants. Las matrices van como las guarda JOML (por columnas),
     * que es la convención de Streamline (por filas, vectores fila).
     */
    static ByteBuffer constantes(ByteBuffer b, float[] vistaAClip, float[] clipAVista, float[] clipAAnterior,
                                 float[] anteriorAClip, float jitterX, float jitterY, float escalaMovX,
                                 float escalaMovY, float[] arriba, float[] derecha, float[] adelante, float cerca,
                                 float lejos, float fov, float aspecto, boolean reiniciar) {
        base(b, BYTES_CONSTANTES, CONSTANTES, 2);
        matriz(b, 32, vistaAClip);
        matriz(b, 96, clipAVista);
        matriz(b, 160, new float[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1}); // clipToLensClip
        matriz(b, 224, clipAAnterior);
        matriz(b, 288, anteriorAClip);
        b.putFloat(352, jitterX).putFloat(356, jitterY);
        b.putFloat(360, escalaMovX).putFloat(364, escalaMovY);
        b.putFloat(368, 0).putFloat(372, 0);                   // cameraPinholeOffset
        vector(b, 376, new float[] {0, 0, 0});                 // cameraPos (todo relativo a la cámara)
        vector(b, 388, arriba);
        vector(b, 400, derecha);
        vector(b, 412, adelante);
        b.putFloat(424, cerca).putFloat(428, lejos).putFloat(432, fov).putFloat(436, aspecto);
        b.putFloat(440, FLOAT_INVALIDO);                        // motionVectorsInvalidValue
        b.put(444, FALSO);                                      // depthInverted
        b.put(445, VERDADERO);                                  // cameraMotionIncluded
        b.put(446, FALSO);                                      // motionVectors3D
        b.put(447, reiniciar ? VERDADERO : FALSO);              // reset
        b.put(448, FALSO).put(449, FALSO).put(450, FALSO);      // ortographic, dilated, jittered
        b.putFloat(452, 40f);                                   // minRelativeLinearDepthObjectSeparation
        return b;
    }

    static ByteBuffer opcionesDlss(ByteBuffer b, int modo, int anchoSalida, int altoSalida) {
        base(b, BYTES_OPCIONES_DLSS, OPCIONES_DLSS, 3);
        b.putInt(32, modo).putInt(36, anchoSalida).putInt(40, altoSalida);
        b.putFloat(44, 0).putFloat(48, 1).putFloat(52, 1);     // sharpness, preExposure, exposureScale
        b.put(56, FALSO);                                       // colorBuffersHDR: entrada LDR
        // presets (60..84) en eDefault, sin autoexposición ni alfa
        return b;
    }

    static ByteBuffer optimosDlss(ByteBuffer b) {
        return base(b, BYTES_OPTIMOS_DLSS, OPTIMOS_DLSS, 1);
    }

    /** El modo de DLSS según la escala (entrada / salida). */
    static int modoPara(double entradaSobreSalida) {
        if (entradaSobreSalida >= 0.95) {
            return DLSS_DLAA;
        }
        double[] escalas = {0.77, 0.667, 0.58, 0.5, 0.333};
        int[] modos = {DLSS_ULTRA_CALIDAD, DLSS_CALIDAD, DLSS_EQUILIBRADO, DLSS_RENDIMIENTO, DLSS_ULTRA_RENDIMIENTO};
        int mejor = 0;
        for (int i = 1; i < escalas.length; i++) {
            if (Math.abs(escalas[i] - entradaSobreSalida) < Math.abs(escalas[mejor] - entradaSobreSalida)) {
                mejor = i;
            }
        }
        return modos[mejor];
    }

    private static void matriz(ByteBuffer b, int o, float[] m) {
        FloatBuffer f = b.position(o).slice().order(b.order()).asFloatBuffer();
        f.put(m, 0, 16);
        b.position(0);
    }

    private static void vector(ByteBuffer b, int o, float[] v) {
        b.putFloat(o, v[0]).putFloat(o + 4, v[1]).putFloat(o + 8, v[2]);
    }
}
