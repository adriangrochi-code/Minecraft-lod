package com.example.minecraftlodmod.render;

import com.example.minecraftlodmod.config.ConmutadorVulkan;

/**
 * Memoria de video para el HUD, desde el hilo de render, una vez por segundo:
 * <ul>
 * <li>OpenGL con NVIDIA ({@code GL_NVX_gpu_memory_info}): usada y total.</li>
 * <li>OpenGL con AMD ({@code GL_ATI_meminfo}): el driver solo da la libre.</li>
 * <li>Vulkan integrado: lo que reservó el juego y el tamaño de la memoria de la GPU.</li>
 * </ul>
 * Intel y lo demás: sin dato.
 */
final class MedidorVram {

    /** En MB; -1 = sin dato. */
    record Vram(long usadaMb, long totalMb, long libreMb) {
        static final Vram SIN_DATO = new Vram(-1, -1, -1);

        /** % usado, o -1 si no se sabe. */
        double porcentaje() {
            return usadaMb >= 0 && totalMb > 0 ? 100.0 * usadaMb / totalMb : -1;
        }
    }

    private static final int GL_GPU_MEMORY_INFO_TOTAL_AVAILABLE_MEMORY_NVX = 0x9048;
    private static final int GL_GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX = 0x9049;
    private static final int GL_TEXTURE_FREE_MEMORY_ATI = 0x87FC;

    /** Más que esto (128 GB) es un valor roto del driver, no memoria de video. */
    static final long MAXIMO_CREIBLE_MB = 128L * 1024;

    private boolean sinSoporte;

    private MedidorVram() {
    }

    static final MedidorVram INSTANCIA = new MedidorVram();

    Vram medir() {
        if (sinSoporte) {
            return Vram.SIN_DATO;
        }
        try {
            if (ConmutadorVulkan.activoEnEstaSesion()) {
                var memoria = com.example.minecraftlodmod.vulkanmod.vulkan.memory.MemoryManager.getInstance();
                return new Vram(memoria.getAllocatedDeviceMemoryMB(), memoria.getDeviceMemoryMB(), -1);
            }
            if (RenderLod.conVulkanMod()) {
                sinSoporte = true; // VulkanMod suelto: sus clases no son las nuestras
                return Vram.SIN_DATO;
            }
            var capacidades = org.lwjgl.opengl.GL.getCapabilities();
            if (capacidades.GL_NVX_gpu_memory_info) {
                long total = org.lwjgl.opengl.GL11.glGetInteger(GL_GPU_MEMORY_INFO_TOTAL_AVAILABLE_MEMORY_NVX) / 1024L;
                long libre = org.lwjgl.opengl.GL11.glGetInteger(GL_GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX) / 1024L;
                // Algunos drivers (Mesa por software) la anuncian con valores sin sentido.
                if (total > 0 && total <= MAXIMO_CREIBLE_MB && libre > 0 && libre <= total) {
                    return new Vram(total - libre, total, libre);
                }
                return Vram.SIN_DATO;
            }
            if (capacidades.GL_ATI_meminfo) {
                int[] datos = new int[4];
                org.lwjgl.opengl.GL11.glGetIntegerv(GL_TEXTURE_FREE_MEMORY_ATI, datos);
                long libre = datos[0] / 1024L;
                return libre > 0 && libre <= MAXIMO_CREIBLE_MB ? new Vram(-1, -1, libre) : Vram.SIN_DATO;
            }
        } catch (Throwable e) {
            // Sin contexto o sin la extensión que dice tener: no se vuelve a intentar.
        }
        sinSoporte = true;
        return Vram.SIN_DATO;
    }
}
