package com.example.minecraftlodmod.render;

import com.example.minecraftlodmod.lwjglvk.VkCommandBuffer;
import com.example.minecraftlodmod.lwjglvk.VkImageBlit;
import org.lwjgl.system.MemoryStack;

import static com.example.minecraftlodmod.lwjglvk.VK10.*;

/**
 * Escalador de prueba del puente Vulkan: un blit con filtro lineal. No
 * mejora nada; sirve para verificar el camino OpenGL → Vulkan → OpenGL sin
 * XeSS ni DLSS (se activa con -Dminecraftlodmod.pruebaVulkan=true).
 */
final class EscaladorBlit implements EscaladorVulkan {

    @Override
    public String nombre() {
        return "prueba (blit)";
    }

    @Override
    public InteropVulkan.Requisitos requisitos() {
        return new InteropVulkan.Requisitos() {
        };
    }

    @Override
    public void preparar(InteropVulkan vk, int anchoEntrada, int altoEntrada, int anchoSalida, int altoSalida) {
    }

    @Override
    public void grabar(VkCommandBuffer cmd, Entradas e, double jitterX, double jitterY, boolean reiniciar) {
        try (MemoryStack st = MemoryStack.stackPush()) {
            VkImageBlit.Buffer blit = VkImageBlit.calloc(1, st);
            blit.srcSubresource(s -> s.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1));
            blit.dstSubresource(s -> s.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1));
            blit.srcOffsets(1).set(e.color().ancho, e.color().alto, 1);
            blit.dstOffsets(1).set(e.salida().ancho, e.salida().alto, 1);
            vkCmdBlitImage(cmd, e.color().imagen, VK_IMAGE_LAYOUT_GENERAL, e.salida().imagen, VK_IMAGE_LAYOUT_GENERAL,
                    blit, VK_FILTER_LINEAR);
        }
    }

    @Override
    public void close() {
    }
}
