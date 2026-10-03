package com.example.minecraftlodmod.render;

import com.example.minecraftlodmod.MinecraftLodMod;
import com.example.minecraftlodmod.core.HorizonteCurvo;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import org.joml.Matrix4f;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;

/**
 * Nubes hasta el alcance del LOD. Vanilla arma sus nubes en un cuadrado de
 * unos 700 bloques alrededor de la cámara y las corta con su plano lejano
 * (4 veces la distancia de render): con el LOD dibujando kilómetros, el cielo
 * lejano quedaba vacío. Esta capa plana sigue la misma textura, altura y
 * movimiento que las de vanilla (el mismo desplazamiento, así el dibujo
 * continúa donde ellas terminan), deja un hueco donde vanilla las dibuja,
 * baja con la curvatura del horizonte y se funde con el color de la niebla
 * hacia el borde. Se dibuja en la pasada del LOD, con su proyección: el
 * terreno LOD la tapa donde corresponde. Sin VulkanMod ni shaderpack (esos
 * dibujan sus propias nubes). Solo cliente.
 */
public final class NubesLejanas {

    private static final Logger LOG = LogUtils.getLogger();
    private static final ResourceLocation TEXTURA = ResourceLocation.withDefaultNamespace("textures/environment/clouds.png");
    /** Cuadros por lado de la grilla: la curvatura se aplica por vértice. */
    private static final int DIVISIONES = 64;

    private static volatile ShaderInstance shader;
    private static float cobertura = -1;
    private static VertexBuffer grilla;
    private static float ladoGrilla;

    private NubesLejanas() {
    }

    /** Bus del mod, junto con los shaders del LOD. */
    public static void registrarShader(RegisterShadersEvent evento) {
        if (RenderLod.conVulkanMod()) {
            return;
        }
        try {
            evento.registerShader(new ShaderInstance(evento.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(MinecraftLodMod.MOD_ID, "lod_nubes"),
                            DefaultVertexFormat.POSITION),
                    cargado -> shader = cargado);
        } catch (IOException e) {
            LOG.error("LOD: no se pudo cargar el shader de nubes lejanas; quedan solo las de vanilla", e);
            shader = null;
        }
    }

    /**
     * @param alcance      hasta dónde llega el LOD, en bloques
     * @param radioPlaneta radio de la curvatura en bloques (0 = plano)
     * @param inicioCurva  distancia desde la que baja (borde de vanilla)
     */
    static void dibujar(Minecraft mc, Matrix4f vista, Matrix4f proyeccion, Vec3 camara, float parcial,
                        float alcance, double radioPlaneta, double inicioCurva) {
        ShaderInstance s = shader;
        CloudStatus tipo = mc.options.getCloudsType();
        if (s == null || mc.level == null || tipo == CloudStatus.OFF) {
            return;
        }
        float alturaNubes = mc.level.effects().getCloudHeight();
        if (Float.isNaN(alturaNubes)) {
            return;
        }
        // Mismo cálculo que LevelRenderer#renderClouds: desplazamiento en texeles (12 bloques c/u).
        double viento = (mc.levelRenderer.ticks + parcial) * 0.03F;
        double texX = (camara.x + viento) / 12.0;
        double alto = alturaNubes - (float) camara.y + 0.33F;
        double texZ = camara.z / 12.0 + 0.33F;
        texX -= Mth.floor(texX / 2048.0) * 2048;
        texZ -= Mth.floor(texZ / 2048.0) * 2048;
        float fraccionX = (float) (texX - Mth.floor(texX));
        float fraccionZ = (float) (texZ - Mth.floor(texZ));
        // Rectángulo que arma vanilla, en bloques relativos a la cámara (texeles -24..40 con
        // nubes detalladas, -32..32 con rápidas); más allá de su plano lejano no dibuja nada.
        int desde = tipo == CloudStatus.FANCY ? -24 : -32, hasta = tipo == CloudStatus.FANCY ? 40 : 32;
        float lado = Math.max(alcance, mc.gameRenderer.getDepthFar()) * 1.05f;
        if (lado < 1) {
            return;
        }
        asegurarGrilla(lado);
        asegurarCobertura(mc);

        Vec3 color = mc.level.getCloudColor(parcial);
        float sombra = alto > 0 ? 0.8f : 1.0f; // desde abajo: entre la cara de abajo (0.7) y los costados (0.9) de vanilla
        float[] niebla = RenderSystem.getShaderFogColor();
        s.safeGetUniform("Curvatura").set(radioPlaneta > 0 ? HorizonteCurvo.coeficiente(radioPlaneta) : 0f,
                (float) inicioCurva);
        s.safeGetUniform("Desplazamiento").set((float) texX, (float) texZ);
        s.safeGetUniform("Hueco").set((desde - fraccionX) * 12f, (desde - fraccionZ) * 12f,
                (hasta - fraccionX) * 12f, (hasta - fraccionZ) * 12f);
        s.safeGetUniform("RadioHueco").set(mc.gameRenderer.getDepthFar());
        s.safeGetUniform("ColorNube").set((float) color.x * sombra, (float) color.y * sombra, (float) color.z * sombra, 0.8f);
        s.safeGetUniform("ColorNiebla").set(niebla[0], niebla[1], niebla[2]);
        s.safeGetUniform("Desvanecer").set(alcance * 0.5f, alcance);
        s.safeGetUniform("Cobertura").set(cobertura);

        RenderSystem.setShaderTexture(0, TEXTURA);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        grilla.bind();
        grilla.drawWithShader(new Matrix4f(vista).translate(0, (float) alto, 0), proyeccion, s);
        VertexBuffer.unbind();
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
    }

    /** Grilla plana de ±lado bloques en y = 0; se rearma solo si el alcance cambia bastante. */
    private static void asegurarGrilla(float lado) {
        if (grilla != null && lado <= ladoGrilla && lado > ladoGrilla * 0.8f) {
            return;
        }
        ladoGrilla = lado * 1.1f;
        if (grilla != null) {
            grilla.close();
        }
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        float paso = 2 * ladoGrilla / DIVISIONES;
        for (int i = 0; i < DIVISIONES; i++) {
            for (int j = 0; j < DIVISIONES; j++) {
                float x0 = -ladoGrilla + i * paso, z0 = -ladoGrilla + j * paso;
                b.addVertex(x0, 0, z0);
                b.addVertex(x0, 0, z0 + paso);
                b.addVertex(x0 + paso, 0, z0 + paso);
                b.addVertex(x0 + paso, 0, z0);
            }
        }
        grilla = new VertexBuffer(VertexBuffer.Usage.STATIC);
        grilla.bind();
        grilla.upload(b.buildOrThrow());
        VertexBuffer.unbind();
    }

    /** Fracción de la textura cubierta por nubes: lo que se ve de lejos cuando ya no se distinguen. */
    private static void asegurarCobertura(Minecraft mc) {
        if (cobertura >= 0) {
            return;
        }
        cobertura = 0.3f;
        try (InputStream in = mc.getResourceManager().open(TEXTURA); NativeImage imagen = NativeImage.read(in)) {
            long suma = 0;
            for (int y = 0; y < imagen.getHeight(); y++) {
                for (int x = 0; x < imagen.getWidth(); x++) {
                    suma += (imagen.getPixelRGBA(x, y) >>> 24) & 0xFF;
                }
            }
            cobertura = suma / (255f * imagen.getWidth() * imagen.getHeight());
        } catch (IOException | RuntimeException e) {
            LOG.warn("LOD: no se pudo leer la textura de nubes; cobertura estimada", e);
        }
    }
}
