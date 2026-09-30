package com.example.minecraftlodmod.render;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Structs de la API Vulkan de Intel XeSS ({@code xess_vk.h}, empaquetados a
 * 8 bytes) escritos a mano en un ByteBuffer: se llaman desde Java con LWJGL,
 * sin código nativo propio. Lógica pura (tamaños y offsets testeados).
 */
final class XessParametros {

    /** xess_vk_image_view_info: imageView, image, VkImageSubresourceRange (5 × u32), format, width, height. */
    static final int BYTES_VISTA = 48;
    /** xess_vk_execute_params_t: 6 vistas, 4 × f32/u32, inputWidth/Height, 6 × xess_coord_t. */
    static final int BYTES_EJECUCION = 360;
    /** xess_vk_init_params_t. */
    static final int BYTES_INICIO = 64;

    static final int CALIDAD_ULTRA_RENDIMIENTO = 100, CALIDAD_RENDIMIENTO = 101, CALIDAD_EQUILIBRADO = 102,
            CALIDAD = 103, CALIDAD_ULTRA = 104, CALIDAD_ULTRA_PLUS = 105;
    static final int FLAG_ENTRADA_LDR = 1 << 6;

    /** Una imagen en xess_vk_image_view_info; vista 0 = no se usa. */
    record Vista(long vista, long imagen, int formato, int ancho, int alto) {
        static final Vista NINGUNA = new Vista(0, 0, 0, 0, 0);
    }

    private XessParametros() {
    }

    /**
     * El preset de XeSS cuya escala (salida / entrada) queda más cerca de la
     * pedida: 1.3 Ultra Calidad Plus, 1.5 Ultra Calidad, 1.7 Calidad, 2.0
     * Equilibrado, 2.3 Rendimiento, 3.0 Ultra Rendimiento.
     */
    static int calidadPara(double escalaSalidaSobreEntrada) {
        double[] escalas = {1.3, 1.5, 1.7, 2.0, 2.3, 3.0};
        int[] presets = {CALIDAD_ULTRA_PLUS, CALIDAD_ULTRA, CALIDAD, CALIDAD_EQUILIBRADO, CALIDAD_RENDIMIENTO,
                CALIDAD_ULTRA_RENDIMIENTO};
        int mejor = 0;
        for (int i = 1; i < escalas.length; i++) {
            if (Math.abs(escalas[i] - escalaSalidaSobreEntrada) < Math.abs(escalas[mejor] - escalaSalidaSobreEntrada)) {
                mejor = i;
            }
        }
        return presets[mejor];
    }

    static ByteBuffer inicio(ByteBuffer b, int anchoSalida, int altoSalida, int calidad, int banderas) {
        b.order(ByteOrder.nativeOrder());
        for (int i = 0; i < BYTES_INICIO; i++) {
            b.put(i, (byte) 0);
        }
        b.putInt(0, anchoSalida).putInt(4, altoSalida);
        b.putInt(8, calidad).putInt(12, banderas);
        // creationNodeMask, visibleNodeMask, heaps y pipeline cache en 0: XeSS reserva lo suyo.
        return b;
    }

    static ByteBuffer ejecucion(ByteBuffer b, Vista color, Vista velocidad, Vista profundidad, Vista salida,
                                float jitterX, float jitterY, boolean reiniciar, int anchoEntrada, int altoEntrada) {
        b.order(ByteOrder.nativeOrder());
        for (int i = 0; i < BYTES_EJECUCION; i++) {
            b.put(i, (byte) 0);
        }
        vista(b, 0, color);
        vista(b, BYTES_VISTA, velocidad);
        vista(b, 2 * BYTES_VISTA, profundidad);
        vista(b, 3 * BYTES_VISTA, Vista.NINGUNA); // exposición
        vista(b, 4 * BYTES_VISTA, Vista.NINGUNA); // máscara de píxeles reactivos
        vista(b, 5 * BYTES_VISTA, salida);
        int o = 6 * BYTES_VISTA;
        b.putFloat(o, jitterX).putFloat(o + 4, jitterY).putFloat(o + 8, 1f).putInt(o + 12, reiniciar ? 1 : 0);
        b.putInt(o + 16, anchoEntrada).putInt(o + 20, altoEntrada);
        // Las 6 coordenadas base (inputColorBase ... outputColorBase) quedan en 0.
        return b;
    }

    private static void vista(ByteBuffer b, int o, Vista v) {
        b.putLong(o, v.vista()).putLong(o + 8, v.imagen());
        if (v.vista() != 0) {
            // VkImageSubresourceRange: aspectMask COLOR, baseMipLevel 0, levelCount 1, baseArrayLayer 0, layerCount 1
            b.putInt(o + 16, 1).putInt(o + 20, 0).putInt(o + 24, 1).putInt(o + 28, 0).putInt(o + 32, 1);
        }
        b.putInt(o + 36, v.formato()).putInt(o + 40, v.ancho()).putInt(o + 44, v.alto());
    }
}
