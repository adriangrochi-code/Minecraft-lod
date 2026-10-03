package com.example.minecraftlodmod.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Qué modos de {@link ModoEscalado} tiene sentido ofrecer según la GPU (el
 * texto de GL_VENDOR + GL_RENDERER), el sistema operativo y si está VulkanMod.
 * El menú solo muestra estos, y si la config pide otro se usa el temporal.
 *
 * - FSR 1 y el temporal propio: cualquier GPU.
 * - XeSS: DLL solo Windows; Intel Arc/Xe, o GPUs con DP4a: NVIDIA desde Pascal
 *   (GT/GTX 10xx, GTX 16xx, RTX) y AMD RDNA (RX 5000 en adelante, Radeon
 *   680M/780M/880M/890M). Vega/Polaris y las iGPU viejas no.
 * - DLSS: Windows y NVIDIA RTX (núcleos tensor); una GTX no.
 * - Con VulkanMod el escalado no corre (ver {@code render.Escalado}): solo APAGADO.
 */
public final class CompatibilidadEscalado {

    private static final Pattern NVIDIA_DP4A = Pattern.compile(
            "\\b(rtx|gtx\\s*1[06-9]\\d{2}|gt\\s*10\\d{2}|titan\\s*(x|xp|v|rtx))\\b");
    private static final Pattern AMD_RDNA = Pattern.compile(
            "\\b(rx\\s*[5-9]\\d{3}|radeon\\s*(pro\\s*)?w[5-7]\\d{3}|radeon\\s*[6-8][89]0m)\\b");
    private static final Pattern INTEL_XE = Pattern.compile("\\b(arc|iris\\s*xe|xe\\s*graphics)\\b");

    private CompatibilidadEscalado() {
    }

    public static boolean soportaDlss(String gpu, boolean windows) {
        String g = normalizar(gpu);
        return windows && g.contains("nvidia") && g.matches(".*\\brtx\\b.*");
    }

    public static boolean soportaXess(String gpu, boolean windows) {
        if (!windows) {
            return false;
        }
        String g = normalizar(gpu);
        if (g.contains("intel")) {
            return INTEL_XE.matcher(g).find();
        }
        if (g.contains("nvidia")) {
            return NVIDIA_DP4A.matcher(g).find();
        }
        if (g.contains("amd") || g.contains("ati ") || g.contains("radeon")) {
            return AMD_RDNA.matcher(g).find();
        }
        return false;
    }

    /** Modos para el menú, en el orden del enum. */
    public static List<ModoEscalado> disponibles(String gpu, boolean windows, boolean vulkanMod) {
        List<ModoEscalado> modos = new ArrayList<>();
        modos.add(ModoEscalado.APAGADO);
        if (vulkanMod) {
            return modos;
        }
        modos.add(ModoEscalado.FSR1);
        modos.add(ModoEscalado.TEMPORAL);
        if (soportaXess(gpu, windows)) {
            modos.add(ModoEscalado.XESS);
        }
        if (soportaDlss(gpu, windows)) {
            modos.add(ModoEscalado.DLSS);
        }
        return modos;
    }

    /** El modo a usar: el pedido si está disponible; si no, el temporal (o apagado si tampoco). */
    public static ModoEscalado efectivo(ModoEscalado pedido, List<ModoEscalado> disponibles) {
        if (disponibles.contains(pedido)) {
            return pedido;
        }
        return disponibles.contains(ModoEscalado.TEMPORAL) ? ModoEscalado.TEMPORAL : ModoEscalado.APAGADO;
    }

    private static String normalizar(String gpu) {
        return gpu == null ? "" : gpu.toLowerCase(Locale.ROOT).replace('/', ' ');
    }
}
