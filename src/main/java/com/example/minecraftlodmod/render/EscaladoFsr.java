package com.example.minecraftlodmod.render;

import com.example.minecraftlodmod.MinecraftLodMod;
import com.example.minecraftlodmod.config.ConfigLod;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import org.slf4j.Logger;

import java.io.IOException;

/**
 * Escalado AMD FSR 1 (experimental, apagado por defecto): el MUNDO se dibuja
 * a menor resolución y se lleva a la de pantalla con EASU (escalado con
 * bordes) + RCAS (nitidez); la interfaz, el contorno de entidades y los
 * efectos de pantalla se dibujan después, a resolución completa.
 *
 * Es lo único del mod que se mete en el cuadro de Minecraft con mixins
 * ({@code render.mixin}): mientras se dibuja el mundo,
 * {@code Minecraft.getMainRenderTarget()} devuelve el framebuffer chico.
 * Todo lo demás son abstracciones de Minecraft (RenderTarget, ShaderInstance,
 * BufferUploader), sin GL crudo.
 *
 * Se desactiva solo con gráficos Fabulous (sus framebuffers de
 * transparencia tienen el tamaño de la ventana) y con Iris/Oculus (ya
 * reemplazan este tramo del pipeline). Ayuda cuando la GPU es el límite;
 * si el límite es el CPU, casi no cambia nada.
 */
public final class EscaladoFsr {

    private static final Logger LOG = LogUtils.getLogger();

    private static ShaderInstance easu, rcas;
    private static TextureTarget escalado, intermedio;
    /** true mientras se dibuja el mundo en el framebuffer chico. Solo hilo de render. */
    private static boolean activo;
    private static boolean avisoIncompatible;
    private static long ultimoTamanoAvisado;

    private EscaladoFsr() {
    }

    /** Bus del mod, solo cliente. */
    public static void registrarShaders(RegisterShadersEvent evento) {
        try {
            evento.registerShader(new ShaderInstance(evento.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(MinecraftLodMod.MOD_ID, "fsr_easu"),
                    DefaultVertexFormat.POSITION), s -> easu = s);
            evento.registerShader(new ShaderInstance(evento.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(MinecraftLodMod.MOD_ID, "fsr_rcas"),
                    DefaultVertexFormat.POSITION), s -> rcas = s);
        } catch (IOException e) {
            LOG.error("LOD: no se pudieron cargar los shaders de FSR; el escalado queda desactivado", e);
            easu = rcas = null;
        }
    }

    /** Framebuffer que tiene que usar el mundo, o null si FSR no está activo (mixin de Minecraft). */
    public static RenderTarget objetivoActivo() {
        return activo ? escalado : null;
    }

    /** Tamaño del framebuffer chico: al menos 1 píxel, y nunca más grande que la pantalla. */
    public static int tamanoEscalado(int pantalla, double escala) {
        return Math.max(1, Math.min(pantalla, (int) Math.round(pantalla * escala)));
    }

    /** Mixin de GameRenderer, justo antes de dibujar el mundo. */
    public static void antesDelMundo() {
        Minecraft mc = Minecraft.getInstance();
        if (!habilitado(mc)) {
            return;
        }
        int ancho = mc.getWindow().getWidth(), alto = mc.getWindow().getHeight();
        double escala = ConfigLod.CLIENTE.fsrEscalaPorcentaje.get() / 100.0;
        int anchoChico = tamanoEscalado(ancho, escala), altoChico = tamanoEscalado(alto, escala);
        if (anchoChico >= ancho && altoChico >= alto) {
            return; // 100%: nada que escalar
        }
        long tamano = ((long) anchoChico << 48) | ((long) altoChico << 32) | ((long) ancho << 16) | alto;
        if (tamano != ultimoTamanoAvisado) {
            ultimoTamanoAvisado = tamano;
            LOG.info("LOD: FSR activo, mundo a {}x{} escalado a {}x{}", anchoChico, altoChico, ancho, alto);
        }
        escalado = asegurar(escalado, anchoChico, altoChico, true);
        intermedio = asegurar(intermedio, ancho, alto, false);
        activo = true;
        escalado.bindWrite(true);
    }

    /** Mixin de GameRenderer, justo después de dibujar el mundo: EASU + RCAS hacia el framebuffer real. */
    public static void despuesDelMundo() {
        if (!activo) {
            return;
        }
        activo = false;
        Minecraft mc = Minecraft.getInstance();
        RenderTarget principal = mc.getMainRenderTarget();
        pasada(easu, escalado, intermedio, s -> {
            s.safeGetUniform("InSize").set((float) escalado.width, (float) escalado.height);
            s.safeGetUniform("OutSize").set((float) intermedio.width, (float) intermedio.height);
        });
        pasada(rcas, intermedio, principal, s -> {
            s.safeGetUniform("OutSize").set((float) intermedio.width, (float) intermedio.height);
            s.safeGetUniform("Nitidez").set(ConfigLod.CLIENTE.fsrNitidez.get().floatValue());
        });
        principal.bindWrite(true);
    }

    private static boolean habilitado(Minecraft mc) {
        if (!ConfigLod.CLIENTE.fsrActivo.get() || easu == null || rcas == null) {
            return false;
        }
        boolean fabulous = mc.options.graphicsMode().get() == GraphicsStatus.FABULOUS;
        boolean iris = ModList.get().isLoaded("iris") || ModList.get().isLoaded("oculus");
        if (fabulous || iris) {
            if (!avisoIncompatible) {
                avisoIncompatible = true;
                LOG.warn("LOD: FSR desactivado ({})", fabulous ? "gráficos Fabulous" : "Iris/Oculus instalado");
            }
            return false;
        }
        avisoIncompatible = false;
        return true;
    }

    private static TextureTarget asegurar(TextureTarget t, int ancho, int alto, boolean profundidad) {
        if (t == null) {
            t = new TextureTarget(ancho, alto, profundidad, Minecraft.ON_OSX);
            t.setClearColor(0, 0, 0, 0);
        } else if (t.width != ancho || t.height != alto) {
            t.resize(ancho, alto, Minecraft.ON_OSX);
        }
        return t;
    }

    private static void pasada(ShaderInstance shader, RenderTarget origen, RenderTarget destino,
                               java.util.function.Consumer<ShaderInstance> uniforms) {
        destino.bindWrite(true);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableBlend();
        RenderSystem.setShader(() -> shader);
        RenderSystem.setShaderTexture(0, origen.getColorTextureId());
        uniforms.accept(shader);
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        b.addVertex(-1, -1, 0);
        b.addVertex(1, -1, 0);
        b.addVertex(1, 1, 0);
        b.addVertex(-1, 1, 0);
        BufferUploader.drawWithShader(b.buildOrThrow());
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
    }
}
