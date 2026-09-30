package com.example.minecraftlodmod.render;

import com.example.minecraftlodmod.lwjglvk.VkCommandBuffer;

/**
 * Un escalador que corre en Vulkan sobre el {@link InteropVulkan} (XeSS,
 * DLSS, y uno de prueba). {@link Escalado} le prepara las entradas en
 * imágenes compartidas y le pide grabar su trabajo en un command buffer.
 */
interface EscaladorVulkan extends AutoCloseable {

    /**
     * Imágenes compartidas del cuadro (color, vectores de movimiento en píxeles
     * de entrada y profundidad, y la salida) y la cámara.
     */
    record Entradas(InteropVulkan.ImagenCompartida color, InteropVulkan.ImagenCompartida velocidad,
                    InteropVulkan.ImagenCompartida profundidad, InteropVulkan.ImagenCompartida salida,
                    Camara camara) {
    }

    /**
     * Cámara del cuadro, en el espacio relativo a la cámara de Minecraft:
     * proyección sin jitter, rotación de la vista, clip actual → clip del
     * cuadro anterior, y planos cercano y lejano.
     */
    record Camara(org.joml.Matrix4f proyeccion, org.joml.Matrix4f vista, org.joml.Matrix4f clipAAnterior,
                  float cerca, float lejos) {
    }

    String nombre();

    /** Extensiones y características de Vulkan que necesita, antes de crear el puente. */
    InteropVulkan.Requisitos requisitos();

    /** Se llama al crear el puente y cada vez que cambian los tamaños. */
    void preparar(InteropVulkan vk, int anchoEntrada, int altoEntrada, int anchoSalida, int altoSalida);

    /**
     * Graba el escalado de este cuadro.
     *
     * @param jitterX desplazamiento de este cuadro en píxeles de entrada (el mismo de la proyección)
     */
    void grabar(VkCommandBuffer cmd, Entradas e, double jitterX, double jitterY, boolean reiniciar);

    @Override
    void close();
}
