package com.example.minecraftlodmod.render;

import com.example.minecraftlodmod.config.ConfigLod;
import com.example.minecraftlodmod.render.mixin.AccesoPipelineIris;
import com.google.common.primitives.Ints;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.gl.program.ProgramImages;
import net.irisshaders.iris.gl.program.ProgramSamplers;
import net.irisshaders.iris.gl.program.ProgramUniforms;
import net.irisshaders.iris.gl.state.FogMode;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.samplers.IrisSamplers;
import net.irisshaders.iris.targets.RenderTargets;
import net.irisshaders.iris.uniforms.CommonUniforms;
import net.irisshaders.iris.uniforms.builtin.BuiltinReplacementUniforms;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL20;
import org.slf4j.Logger;

import java.util.List;

/**
 * Contrato Voxy de los shaderpacks, etapa B (sección 27, punto 2b): el LOD se
 * dibuja con el {@code voxy_opaque.glsl} del pack, como lo haría Voxy, y el
 * pack lo integra en su deferred/composite igual que al terreno de ese mod.
 * Solo se carga con Iris (lo llaman {@link ShadersVoxy} y {@link RenderLod}).
 *
 * <ul>
 * <li>Color: framebuffer sobre los colortex del pack que pide {@code opaqueDrawBuffers}
 *     (los que escribe la etapa de gbuffers), sin tocar la profundidad de vanilla
 *     ({@code excludeLodsFromVanillaDepth}).</li>
 * <li>Profundidad propia del LOD: {@code vxDepthTexOpaque}, copiada a
 *     {@code vxDepthTexTrans} (el LOD todavía no tiene pasada translúcida);
 *     ambas llegan a todos los programas del pack ({@code MixinSamplersIris}).</li>
 * <li>Matrices {@code vxProj}/{@code vxModelView} (con inversas y previas) y
 *     {@code vxRenderDistance} en chunks ({@code MixinUniformesIris}).</li>
 * <li>{@code VOXY} definido para todo el pack ({@code MixinShaderPackIris}).</li>
 * <li>Programa armado con los mismos builders que el de Distant Horizons de Iris
 *     ({@code IrisLodRenderProgram}): uniforms del pack, samplers de gbuffers y,
 *     aparte, el atlas y la tabla de sprites del LOD.</li>
 * </ul>
 * Se dibuja en {@code AFTER_SKY}: el terreno vanilla viene después y tapa al
 * LOD donde lo cubre, sin compartir profundidad.
 *
 * Opt-in ({@code contratoVoxy}): verificado en Mesa por software; falta verlo
 * en GPUs reales (NOTES.md, Pista B).
 */
public final class DibujoVoxy {

    private static final Logger LOG = LogUtils.getLogger();

    private static final int GL_TEXTURE_2D = 3553;
    private static final int GL_DEPTH_COMPONENT32F = 0x8CAC;
    private static final int GL_DEPTH_COMPONENT = 0x1902;
    private static final int GL_FLOAT = 5126;
    private static final int GL_NEAREST = 9728;
    private static final int GL_TEXTURE_MIN_FILTER = 10241;
    private static final int GL_TEXTURE_MAG_FILTER = 10240;
    private static final int GL_TEXTURE_WRAP_S = 10242;
    private static final int GL_TEXTURE_WRAP_T = 10243;
    private static final int GL_CLAMP_TO_EDGE = 33071;
    private static final int GL_FRAMEBUFFER = 36160;
    private static final int GL_READ_FRAMEBUFFER = 36008;
    private static final int GL_DRAW_FRAMEBUFFER = 36009;
    private static final int GL_DEPTH_BUFFER_BIT = 256;
    private static final int GL_LEQUAL = 515;
    private static final int GL_LINK_STATUS = 0x8B82;
    private static final int GL_FRAGMENT_SHADER = 0x8B30;
    private static final int GL_VERTEX_SHADER = 0x8B31;

    private DibujoVoxy() {
    }

    // ------------------------------------------------------------------ estado por cuadro (uniforms vx*)

    private static final Matrix4f PROYECCION = new Matrix4f();
    private static final Matrix4f PROYECCION_INV = new Matrix4f();
    private static final Matrix4f PROYECCION_PREVIA = new Matrix4f();
    private static final Matrix4f VISTA = new Matrix4f();
    private static final Matrix4f VISTA_INV = new Matrix4f();
    private static final Matrix4f VISTA_PREVIA = new Matrix4f();
    private static int distanciaChunks;

    /**
     * Iris carga el pack al iniciar el renderizador, antes que la config del mod
     * (y una excepción ahí hace fallar la carga de cualquier pack): hasta que esté
     * cargada, el valor se lee del archivo. Ante cualquier problema, apagado.
     */
    public static boolean activoEnConfig() {
        try {
            if (ConfigLod.SPEC_CLIENTE.isLoaded()) {
                return ConfigLod.CLIENTE.contratoVoxy.get();
            }
            java.nio.file.Path archivo = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get()
                    .resolve("minecraftlodmod-client.toml");
            if (!java.nio.file.Files.isRegularFile(archivo)) {
                return false;
            }
            for (String linea : java.nio.file.Files.readAllLines(archivo)) {
                String l = linea.strip();
                if (l.startsWith("contratoVoxy")) {
                    return l.substring(l.indexOf('=') + 1).strip().startsWith("true");
                }
            }
            return false;
        } catch (RuntimeException | java.io.IOException e) {
            return false;
        }
    }

    public static int distanciaChunks() {
        return distanciaChunks;
    }

    public static Matrix4fc proyeccion() {
        return PROYECCION;
    }

    public static Matrix4fc proyeccionInversa() {
        return PROYECCION_INV;
    }

    public static Matrix4fc proyeccionPrevia() {
        return PROYECCION_PREVIA;
    }

    public static Matrix4fc vista() {
        return VISTA;
    }

    public static Matrix4fc vistaInversa() {
        return VISTA_INV;
    }

    public static Matrix4fc vistaPrevia() {
        return VISTA_PREVIA;
    }

    // ------------------------------------------------------------------ profundidad propia

    private static int profundidadOpaca;
    private static int profundidadTranslucida;
    private static int ancho;
    private static int alto;
    private static GlFramebuffer marcoTranslucido;

    /**
     * Cuadro (contador de Iris) del último {@link #empezar}: si el LOD deja de dibujar,
     * su profundidad se vacía para no mostrar uno viejo. Por cuadros y no por tiempo:
     * con poco FPS el dibujo del LOD solo puede tardar más que cualquier tope fijo.
     */
    private static int ultimoCuadro = -1;
    private static boolean vacia = true;

    /** Para los samplers del pack: siempre una textura válida (borrada a 1 = "sin LOD") aunque no se dibuje. */
    public static int profundidadOpaca() {
        revisarVigencia();
        return profundidadOpaca;
    }

    public static int profundidadTranslucida() {
        revisarVigencia();
        return profundidadTranslucida;
    }

    private static void revisarVigencia() {
        if (asegurarProfundidades(Math.max(ancho, 1), Math.max(alto, 1))) {
            vacia = false; // recién creadas: contenido indefinido
        }
        if (!vacia && net.irisshaders.iris.uniforms.SystemTimeUniforms.COUNTER.getAsInt() - ultimoCuadro > 1) {
            vaciar();
            vacia = true;
        }
    }

    private static boolean asegurarProfundidades(int w, int h) {
        if (profundidadOpaca != 0 && w == ancho && h == alto) {
            return false;
        }
        borrarProfundidades();
        ancho = w;
        alto = h;
        profundidadOpaca = textura(w, h);
        profundidadTranslucida = textura(w, h);
        marcoTranslucido = new GlFramebuffer();
        marcoTranslucido.addDepthAttachment(profundidadTranslucida);
        marcoTranslucido.noDrawBuffers();
        return true;
    }

    private static int textura(int w, int h) {
        int id = GlStateManager._genTexture();
        GlStateManager._bindTexture(id);
        GlStateManager._texParameter(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        GlStateManager._texParameter(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        GlStateManager._texParameter(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        GlStateManager._texImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT32F, w, h, 0, GL_DEPTH_COMPONENT, GL_FLOAT, null);
        GlStateManager._bindTexture(0);
        return id;
    }

    private static void borrarProfundidades() {
        if (marcoTranslucido != null) {
            marcoTranslucido.destroy();
            marcoTranslucido = null;
        }
        if (profundidadOpaca != 0) {
            GlStateManager._deleteTexture(profundidadOpaca);
            GlStateManager._deleteTexture(profundidadTranslucida);
            profundidadOpaca = profundidadTranslucida = 0;
        }
    }

    // ------------------------------------------------------------------ programa

    private static Programa programa;

    /** El pack o la dimensión cambiaron: lo armado ya no sirve. */
    static void liberar() {
        if (programa != null) {
            programa.liberar();
            programa = null;
        }
    }

    /** Desde {@link ShadersVoxy}, con el voxy_opaque del pack ya compilado sin errores. */
    static void preparar(IrisRenderingPipeline pipeline, String vertice, String fragmento, List<Integer> buffers) {
        liberar();
        if (!activoEnConfig()) {
            return;
        }
        try {
            programa = new Programa(pipeline, vertice, fragmento, Ints.toArray(buffers));
            LOG.info("LOD/Voxy: el LOD se dibuja con voxy_opaque del pack (colortex {})", buffers);
        } catch (RuntimeException e) {
            programa = null;
            LOG.error("LOD/Voxy: no se pudo armar el programa del contrato; el LOD sigue con gbuffers_terrain", e);
        }
    }

    /** Hay programa del pack para dibujar el LOD (opción prendida, pack con contrato, compiló). */
    public static boolean listo() {
        return programa != null;
    }

    /**
     * Prepara el cuadro: profundidad propia borrada, matrices vx*, framebuffer y
     * programa puestos. Devuelve con qué fijar el desplazamiento de cada celda;
     * después de dibujar, {@link #terminar()}.
     */
    static DesplazamientoCelda empezar(Matrix4f vista, Matrix4f proyeccion, float alcanceBloques,
                                  float coeficienteCurva, float inicioCurva) {
        Programa p = programa;
        RenderTargets targets = ((AccesoPipelineIris) p.pipeline).minecraftlodmod$renderTargets();
        boolean nuevo = asegurarProfundidades(targets.getCurrentWidth(), targets.getCurrentHeight());
        if (nuevo || p.marco == null) {
            p.armarMarco(targets);
        }
        PROYECCION_PREVIA.set(PROYECCION);
        VISTA_PREVIA.set(VISTA);
        PROYECCION.set(proyeccion);
        PROYECCION.invert(PROYECCION_INV);
        VISTA.set(vista);
        VISTA.invert(VISTA_INV);
        distanciaChunks = (int) Math.ceil(alcanceBloques / 16f);
        ultimoCuadro = net.irisshaders.iris.uniforms.SystemTimeUniforms.COUNTER.getAsInt();
        vacia = false;

        p.marcoAnterior = GlStateManager.getBoundFramebuffer();
        p.marco.bind();
        RenderSystem.depthMask(true);
        GlStateManager._clearDepth(1.0);
        GlStateManager._clear(GL_DEPTH_BUFFER_BIT, false);
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL_LEQUAL);
        RenderSystem.disableBlend();
        RenderSystem.enableCull();
        GlStateManager._glUseProgram(p.id);
        GL20.glUniform2f(p.curvatura, coeficienteCurva, inicioCurva);
        p.samplers.update();
        p.uniforms.update();
        p.custom.push(p);
        p.imagenes.update();
        // Las vx* de este cuadro directo al programa propio: las de Iris se actualizan una vez por cuadro,
        // antes de que el LOD fije las suyas (para los programas del pack alcanzan, llegan con un cuadro).
        p.matriz(p.vxProj, PROYECCION);
        p.matriz(p.vxProjInv, PROYECCION_INV);
        p.matriz(p.vxProjPrev, PROYECCION_PREVIA);
        p.matriz(p.vxModelView, VISTA);
        p.matriz(p.vxModelViewInv, VISTA_INV);
        p.matriz(p.vxModelViewPrev, VISTA_PREVIA);
        if (p.vxRenderDistance >= 0) {
            GL20.glUniform1i(p.vxRenderDistance, distanciaChunks);
        }
        return (x, y, z) -> GL20.glUniform3f(p.desplazamiento, x, y, z);
    }

    /** Cuadros dibujados con este programa (para el diagnóstico de los primeros). */
    private static int cuadros;

    /** Saca el programa, copia la profundidad a la de translúcidos y vuelve al framebuffer de antes. */
    static void terminar() {
        Programa p = programa;
        if (++cuadros == 30) {
            diagnosticar(p);
        }
        GlStateManager._glUseProgram(0);
        ProgramUniforms.clearActiveUniforms();
        ProgramSamplers.clearActiveSamplers();
        GlStateManager._glBindFramebuffer(GL_READ_FRAMEBUFFER, p.marco.getId());
        GlStateManager._glBindFramebuffer(GL_DRAW_FRAMEBUFFER, marcoTranslucido.getId());
        GlStateManager._glBlitFrameBuffer(0, 0, ancho, alto, 0, 0, ancho, alto, GL_DEPTH_BUFFER_BIT, GL_NEAREST);
        GlStateManager._glBindFramebuffer(GL_FRAMEBUFFER, p.marcoAnterior);
        RenderSystem.depthFunc(GL_LEQUAL);
    }

    /** Una vez: estado del framebuffer, error de GL y un píxel de profundidad y color del LOD (al log). */
    private static void diagnosticar(Programa p) {
        int error = org.lwjgl.opengl.GL11.glGetError();
        int estado = p.marco.getStatus();
        GlStateManager._glBindFramebuffer(GL_READ_FRAMEBUFFER, p.marco.getId());
        float[] profundidad = new float[1];
        float[] color = new float[4];
        int x = ancho / 2;
        int y = alto / 3;
        org.lwjgl.opengl.GL11.glReadPixels(x, y, 1, 1, GL_DEPTH_COMPONENT, GL_FLOAT, profundidad);
        org.lwjgl.opengl.GL11.glReadBuffer(org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0);
        org.lwjgl.opengl.GL11.glReadPixels(x, y, 1, 1, org.lwjgl.opengl.GL11.GL_RGBA, GL_FLOAT, color);
        LOG.info("LOD/Voxy: cuadro {}: framebuffer 0x{} ({}x{}, buffers {}), error GL 0x{}, píxel ({},{}): profundidad {}, color {}",
                cuadros, Integer.toHexString(estado), ancho, alto, java.util.Arrays.toString(p.buffers),
                Integer.toHexString(error), x, y, profundidad[0], java.util.Arrays.toString(color));
    }

    /** Sin dibujar el LOD en este cuadro: la profundidad propia queda en "nada" (1.0). */
    static void vaciar() {
        if (profundidadOpaca == 0 || marcoTranslucido == null) {
            return;
        }
        int anterior = GlStateManager.getBoundFramebuffer();
        RenderSystem.depthMask(true);
        GlStateManager._clearDepth(1.0);
        GlStateManager._glBindFramebuffer(GL_FRAMEBUFFER, marcoTranslucido.getId());
        GlStateManager._clear(GL_DEPTH_BUFFER_BIT, false);
        if (programa != null && programa.marco != null) {
            programa.marco.bind();
            GlStateManager._clear(GL_DEPTH_BUFFER_BIT, false);
        }
        GlStateManager._glBindFramebuffer(GL_FRAMEBUFFER, anterior);
    }

    /** Programa GL con los uniforms y samplers de Iris (como su IrisLodRenderProgram). */
    private static final class Programa {
        final IrisRenderingPipeline pipeline;
        final int id;
        final int[] buffers;
        final ProgramUniforms uniforms;
        final ProgramSamplers samplers;
        final ProgramImages imagenes;
        final CustomUniforms custom;
        final int desplazamiento;
        final int curvatura;
        final int vxProj, vxProjInv, vxProjPrev, vxModelView, vxModelViewInv, vxModelViewPrev, vxRenderDistance;
        private final float[] matriz = new float[16];
        GlFramebuffer marco;
        int marcoAnterior;

        Programa(IrisRenderingPipeline pipeline, String vertice, String fragmento, int[] buffers) {
            this.pipeline = pipeline;
            this.buffers = buffers;
            this.custom = pipeline.getCustomUniforms();
            id = GlStateManager.glCreateProgram();
            // Mismo orden de atributos que el formato compacto (RenderLod.FORMATO_TEXTURA).
            GL20.glBindAttribLocation(id, 0, "PosSprite");
            GL20.glBindAttribLocation(id, 1, "Color");
            int vs = shader(GL_VERTEX_SHADER, vertice);
            int fs = shader(GL_FRAGMENT_SHADER, fragmento);
            GlStateManager.glAttachShader(id, vs);
            GlStateManager.glAttachShader(id, fs);
            GlStateManager.glLinkProgram(id);
            GlStateManager.glDeleteShader(vs);
            GlStateManager.glDeleteShader(fs);
            if (GlStateManager.glGetProgrami(id, GL_LINK_STATUS) == 0) {
                String log = GlStateManager.glGetProgramInfoLog(id, 32768);
                GlStateManager.glDeleteProgram(id);
                throw new IllegalStateException("no enlaza: " + log);
            }
            GlStateManager._glUseProgram(id);
            ProgramUniforms.Builder u = ProgramUniforms.builder("minecraftlodmod_voxy", id);
            ProgramSamplers.Builder s = ProgramSamplers.builder(id, IrisSamplers.WORLD_RESERVED_TEXTURE_UNITS);
            ProgramImages.Builder i = ProgramImages.builder(id);
            CommonUniforms.addDynamicUniforms(u, FogMode.PER_VERTEX);
            custom.assignTo(u);
            BuiltinReplacementUniforms.addBuiltinReplacementUniforms(u);
            pipeline.addGbufferOrShadowSamplers(s, i, pipeline::getFlippedAfterPrepare, false, false, true, false);
            s.addDynamicSampler(() -> idTextura(PaletaTexturas.ATLAS), "lodvx_Atlas");
            s.addDynamicSampler(() -> idTextura(PaletaTexturas.TABLA_SPRITES), "lodvx_Sprites");
            custom.mapholderToPass(u, this);
            uniforms = u.buildUniforms();
            samplers = s.build();
            imagenes = i.build();
            desplazamiento = GL20.glGetUniformLocation(id, "lodvx_ChunkOffset");
            curvatura = GL20.glGetUniformLocation(id, "lodvx_Curvatura");
            vxProj = GL20.glGetUniformLocation(id, "vxProj");
            vxProjInv = GL20.glGetUniformLocation(id, "vxProjInv");
            vxProjPrev = GL20.glGetUniformLocation(id, "vxProjPrev");
            vxModelView = GL20.glGetUniformLocation(id, "vxModelView");
            vxModelViewInv = GL20.glGetUniformLocation(id, "vxModelViewInv");
            vxModelViewPrev = GL20.glGetUniformLocation(id, "vxModelViewPrev");
            vxRenderDistance = GL20.glGetUniformLocation(id, "vxRenderDistance");
            GlStateManager._glUseProgram(0);
        }

        void matriz(int ubicacion, Matrix4f m) {
            if (ubicacion >= 0) {
                GL20.glUniformMatrix4fv(ubicacion, false, m.get(matriz));
            }
        }

        /** Colortex del json (lo que escribe gbuffers, según el estado de alternancia del pack) + profundidad propia. */
        void armarMarco(RenderTargets targets) {
            if (marco != null) {
                marco.destroy();
            }
            marco = targets.createDHFramebuffer(pipeline.getFlippedAfterPrepare(), buffers);
            marco.addDepthAttachment(profundidadOpaca);
        }

        void liberar() {
            if (marco != null) {
                marco.destroy();
                marco = null;
            }
            GlStateManager.glDeleteProgram(id);
        }

        private static int shader(int tipo, String fuente) {
            int id = GlStateManager.glCreateShader(tipo);
            GlStateManager.glShaderSource(id, List.of(fuente));
            GlStateManager.glCompileShader(id);
            return id;
        }

        private static int idTextura(ResourceLocation recurso) {
            AbstractTexture t = Minecraft.getInstance().getTextureManager().getTexture(recurso);
            return t == null ? 0 : t.getId();
        }
    }
}
