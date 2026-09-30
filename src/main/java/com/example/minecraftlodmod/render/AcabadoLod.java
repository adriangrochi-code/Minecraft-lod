package com.example.minecraftlodmod.render;

import com.example.minecraftlodmod.MinecraftLodMod;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import org.joml.Matrix4f;
import org.slf4j.Logger;

import java.io.IOException;

/**
 * Acabado del LOD sobre la imagen (sección 25, ideas de Voxy sin su código):
 * oclusión ambiental en pantalla (SSAO, shader {@code lod_ssao}) y niebla
 * ({@code lod_acabado}): neblina atmosférica hacia el horizonte y la niebla
 * del borde que vanilla corre hasta el alcance del LOD. Hasta la 0.22.0 el
 * LOD no tenía niebla: terminaba recortado contra el cielo.
 *
 * Corre después de dibujar el LOD y antes de que se limpie la profundidad
 * (la profundidad en ese momento es solo la del LOD), así el terreno vanilla
 * que se dibuja después no recibe nada. Solo con el backend de OpenGL: con
 * VulkanMod sus shaders no se convierten y con shaderpacks el pack ilumina.
 */
public final class AcabadoLod {

    private static final Logger LOG = LogUtils.getLogger();

    private static ShaderInstance ssao, acabado;
    private static TextureTarget profundidad, oclusion;

    private AcabadoLod() {
    }

    /** Bus del mod, junto con los shaders del LOD. */
    public static void registrarShaders(RegisterShadersEvent evento) {
        if (RenderLod.conVulkanMod()) {
            return;
        }
        try {
            evento.registerShader(shader(evento, "lod_ssao"), s -> ssao = s);
            evento.registerShader(shader(evento, "lod_acabado"), s -> acabado = s);
        } catch (IOException e) {
            LOG.error("LOD: no se pudieron cargar los shaders de acabado; el LOD queda sin niebla ni SSAO", e);
            ssao = acabado = null;
        }
    }

    private static ShaderInstance shader(RegisterShadersEvent evento, String nombre) throws IOException {
        return new ShaderInstance(evento.getResourceProvider(),
                ResourceLocation.fromNamespaceAndPath(MinecraftLodMod.MOD_ID, nombre), DefaultVertexFormat.POSITION);
    }

    /**
     * Hilo de render, con el LOD ya dibujado en el framebuffer principal.
     *
     * @param proyeccion        la proyección con que se dibujó el LOD
     * @param alcance           hasta dónde llega el LOD, en bloques (la niebla del borde termina ahí)
     * @param inicioNeblina     desde dónde empieza la neblina (el borde de vanilla), en bloques
     * @param neblina           intensidad de la neblina en el horizonte (0 = sin neblina, 1 = color del cielo)
     * @param fuerzaOclusion    intensidad del SSAO (0 = apagado)
     */
    public static void aplicar(Minecraft mc, Matrix4f proyeccion, float alcance, float inicioNeblina, float neblina,
                               float fuerzaOclusion) {
        if (ssao == null || acabado == null) {
            return;
        }
        RenderTarget principal = mc.getMainRenderTarget();
        int ancho = principal.width, alto = principal.height;
        profundidad = asegurar(profundidad, ancho, alto, true);
        profundidad.copyDepthFrom(principal);
        Matrix4f inversa = new Matrix4f(proyeccion).invert();
        boolean conOclusion = fuerzaOclusion > 0;

        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        if (conOclusion) {
            oclusion = asegurar(oclusion, ancho, alto, false);
            oclusion.bindWrite(true);
            RenderSystem.disableBlend();
            RenderSystem.setShader(() -> ssao);
            RenderSystem.setShaderTexture(0, profundidad.getDepthTextureId());
            ssao.safeGetUniform("InSize").set((float) ancho, (float) alto);
            ssao.safeGetUniform("ProjMat").set(proyeccion);
            ssao.safeGetUniform("ProjInv").set(inversa);
            ssao.safeGetUniform("Fuerza").set(fuerzaOclusion);
            cuadrado();
        }

        principal.bindWrite(true);
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.SRC_ALPHA,
                GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE);
        RenderSystem.setShader(() -> acabado);
        RenderSystem.setShaderTexture(0, profundidad.getDepthTextureId());
        if (conOclusion) {
            RenderSystem.setShaderTexture(1, oclusion.getColorTextureId());
        }
        float[] colorNiebla = RenderSystem.getShaderFogColor();
        acabado.safeGetUniform("InSize").set((float) ancho, (float) alto);
        acabado.safeGetUniform("ProjInv").set(inversa);
        acabado.safeGetUniform("ColorNiebla").set(colorNiebla[0], colorNiebla[1], colorNiebla[2]);
        // La misma que RenderLod#alCalcularNiebla le pone a vanilla.
        acabado.safeGetUniform("NieblaBorde").set(alcance * 0.8f, alcance);
        acabado.safeGetUniform("Neblina").set(inicioNeblina, alcance, neblina);
        acabado.safeGetUniform("ConOclusion").set(conOclusion ? 1f : 0f);
        cuadrado();

        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
    }

    private static void cuadrado() {
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        b.addVertex(-1, -1, 0);
        b.addVertex(1, -1, 0);
        b.addVertex(1, 1, 0);
        b.addVertex(-1, 1, 0);
        BufferUploader.drawWithShader(b.buildOrThrow());
    }

    private static TextureTarget asegurar(TextureTarget t, int ancho, int alto, boolean conProfundidad) {
        if (t == null) {
            t = new TextureTarget(ancho, alto, conProfundidad, Minecraft.ON_OSX);
            t.setClearColor(1, 1, 1, 1);
        } else if (t.width != ancho || t.height != alto) {
            t.resize(ancho, alto, Minecraft.ON_OSX);
        }
        return t;
    }
}
