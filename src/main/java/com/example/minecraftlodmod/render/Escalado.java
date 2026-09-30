package com.example.minecraftlodmod.render;

import com.example.minecraftlodmod.MinecraftLodMod;
import com.example.minecraftlodmod.config.CompatibilidadEscalado;
import com.example.minecraftlodmod.config.ConfigLod;
import com.example.minecraftlodmod.config.ModoEscalado;
import com.mojang.blaze3d.platform.GlUtil;
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
import net.minecraft.Util;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/**
 * Escalado del mundo (experimental, apagado por defecto): el MUNDO se dibuja
 * a menor resolución y se lleva a la de pantalla; la interfaz, el contorno de
 * entidades y los efectos de pantalla se dibujan después, a resolución
 * completa. Modos ({@link ModoEscalado}):
 * <ul>
 *   <li>FSR1: AMD FSR 1, espacial (EASU + RCAS).</li>
 *   <li>TEMPORAL: propio. Cada cuadro se dibuja con un desplazamiento
 *       sub-píxel ({@link SecuenciaJitter}) y se junta con el historial
 *       reproyectado con vectores de movimiento ({@link MovimientoCamara}).</li>
 *   <li>XESS / DLSS: usan la misma base temporal (jitter, movimiento,
 *       profundidad); mientras no estén disponibles, caen al TEMPORAL.</li>
 * </ul>
 *
 * Se mete en el cuadro de Minecraft con mixins ({@code render.mixin}):
 * mientras se dibuja el mundo, {@code Minecraft.getMainRenderTarget()}
 * devuelve el framebuffer chico, y en modo temporal la proyección lleva el
 * jitter. Todo lo demás son abstracciones de Minecraft (RenderTarget,
 * ShaderInstance, BufferUploader).
 *
 * Se desactiva solo con gráficos Fabulous (sus framebuffers de
 * transparencia tienen el tamaño de la ventana), con Iris/Oculus (ya
 * reemplazan este tramo del pipeline) y con VulkanMod.
 */
public final class Escalado {

    private static final Logger LOG = LogUtils.getLogger();
    private static final int GL_RGBA = 6408, GL_RG = 33319, GL_RGBA16F = 34842, GL_RG16F = 33327;
    private static final int GL_LINEAR = 9729, GL_NEAREST = 9728, GL_RGBA8 = 32856, GL_R32F = 33326;
    // Formatos y usos de las imágenes compartidas con Vulkan (VkFormat / VkImageUsageFlags).
    private static final int VK_RGBA8 = 37, VK_RG16F = 83, VK_R32F = 100, VK_RGBA16F = 97;
    private static final int VK_USO_ENTRADA = 0x1 | 0x2 | 0x4 | 0x10, VK_USO_ALMACENAMIENTO = 0x8;
    /** Si la cámara salta más que esto entre cuadros (teletransporte), el historial no sirve. */
    static final double SALTO_CAMARA = 8;

    private static ShaderInstance easu, rcas, movimiento, temporal, profundidad;
    private static TextureTarget escalado, intermedio, profundidadEscena;
    private static ObjetivoFlotante velocidad;
    private static final ObjetivoFlotante[] historial = new ObjetivoFlotante[2];
    private static int historialActual;
    /** true mientras se dibuja el mundo en el framebuffer chico. Solo hilo de render. */
    private static boolean activo;
    private static ModoEscalado modo = ModoEscalado.APAGADO;
    private static boolean avisoIncompatible;
    private static volatile List<ModoEscalado> modosDisponibles;
    private static ModoEscalado avisadoNoDisponible;

    /**
     * Los modos que esta GPU y este sistema pueden usar ({@link CompatibilidadEscalado});
     * el menú muestra solo estos. Se calcula una vez, en el hilo de render.
     */
    public static List<ModoEscalado> modosDisponibles() {
        List<ModoEscalado> modos = modosDisponibles;
        if (modos == null) {
            String gpu = RenderLod.conVulkanMod() ? "VulkanMod" : GlUtil.getVendor() + " " + GlUtil.getRenderer();
            modos = CompatibilidadEscalado.disponibles(gpu, Util.getPlatform() == Util.OS.WINDOWS,
                    RenderLod.conVulkanMod());
            modosDisponibles = modos;
            LOG.info("LOD: escalado disponible en {}: {}", gpu, modos);
        }
        return modos;
    }
    private static ModoEscalado avisoSinBackend;
    private static long ultimoTamanoAvisado;

    // Base temporal: jitter del cuadro y matrices de este cuadro y el anterior.
    private static long cuadro;
    private static double jitterX, jitterY;
    private static boolean capturado;
    private static final Matrix4f proyeccionConJitter = new Matrix4f(), vista = new Matrix4f();
    private static final Matrix4f anteriorSinJitter = new Matrix4f();
    private static Vec3 camara = Vec3.ZERO, camaraAnterior = Vec3.ZERO;
    private static boolean hayAnterior;
    private static boolean reiniciarHistorial = true;

    // XeSS / DLSS: puente a Vulkan (se levanta la primera vez que se eligen).
    private static InteropVulkan vk;
    private static EscaladorVulkan escaladorVk;
    private static ModoEscalado modoVk, modoVkFallido;
    private static InteropVulkan.ImagenCompartida vkColor, vkVelocidad, vkProfundidad, vkSalida;

    private Escalado() {
    }

    /** Bus del mod, solo cliente. */
    public static void registrarShaders(RegisterShadersEvent evento) {
        if (RenderLod.conVulkanMod()) {
            return; // VulkanMod no convierte estos shaders: ver RenderLod.conVulkanMod
        }
        try {
            evento.registerShader(shader(evento, "fsr_easu"), s -> easu = s);
            evento.registerShader(shader(evento, "fsr_rcas"), s -> rcas = s);
            evento.registerShader(shader(evento, "escalado_movimiento"), s -> movimiento = s);
            evento.registerShader(shader(evento, "escalado_temporal"), s -> temporal = s);
            evento.registerShader(shader(evento, "escalado_profundidad"), s -> profundidad = s);
        } catch (IOException e) {
            LOG.error("LOD: no se pudieron cargar los shaders de escalado; el escalado queda desactivado", e);
            easu = rcas = movimiento = temporal = profundidad = null;
        }
    }

    private static ShaderInstance shader(RegisterShadersEvent evento, String nombre) throws IOException {
        return new ShaderInstance(evento.getResourceProvider(),
                ResourceLocation.fromNamespaceAndPath(MinecraftLodMod.MOD_ID, nombre), DefaultVertexFormat.POSITION);
    }

    /** Framebuffer que tiene que usar el mundo, o null si no se está escalando (mixin de Minecraft). */
    public static RenderTarget objetivoActivo() {
        return activo ? escalado : null;
    }

    /** Tamaño del framebuffer chico: al menos 1 píxel, y nunca más grande que la pantalla. */
    public static int tamanoEscalado(int pantalla, double escala) {
        return Math.max(1, Math.min(pantalla, (int) Math.round(pantalla * escala)));
    }

    /**
     * Mixin de GameRenderer#getProjectionMatrix: con un modo temporal, corre
     * la proyección el jitter de este cuadro (vale para el mundo, la mano y el
     * frustum, que así quedan coherentes).
     */
    public static Matrix4f conJitter(Matrix4f proyeccion) {
        if (!activo || !modo.temporal()) {
            return proyeccion;
        }
        return MovimientoCamara.conJitter(proyeccion, jitterX, jitterY, escalado.width, escalado.height);
    }

    /** Mixin de GameRenderer, justo antes de dibujar el mundo. */
    public static void antesDelMundo() {
        Minecraft mc = Minecraft.getInstance();
        ModoEscalado pedido = modoHabilitado(mc);
        if (pedido == ModoEscalado.APAGADO) {
            modo = ModoEscalado.APAGADO;
            return;
        }
        int ancho = mc.getWindow().getWidth(), alto = mc.getWindow().getHeight();
        // El auto-ajuste resta hasta 15 puntos cuando la GPU es el límite (nunca menos del 50%).
        BalanceCpuGpu balance = BalanceCpuGpu.actual();
        int porcentaje = ConfigLod.CLIENTE.fsrEscalaPorcentaje.get() - (balance == null ? 0 : balance.reduccionEscala());
        double escala = Math.max(50, porcentaje) / 100.0;
        int anchoChico = tamanoEscalado(ancho, escala), altoChico = tamanoEscalado(alto, escala);
        if (anchoChico >= ancho && altoChico >= alto) {
            modo = ModoEscalado.APAGADO;
            return; // 100%: nada que escalar
        }
        long tamano = ((long) anchoChico << 48) | ((long) altoChico << 32) | ((long) ancho << 16) | alto;
        if (tamano != ultimoTamanoAvisado || pedido != modo) {
            ultimoTamanoAvisado = tamano;
            reiniciarHistorial = true;
            LOG.info("LOD: escalado {}, mundo a {}x{} escalado a {}x{}", pedido, anchoChico, altoChico, ancho, alto);
        }
        modo = pedido;
        escalado = asegurar(escalado, anchoChico, altoChico, true);
        intermedio = asegurar(intermedio, ancho, alto, false);
        if (modo.temporal()) {
            escalado.setFilterMode(GL_LINEAR);
            profundidadEscena = asegurar(profundidadEscena, anchoChico, altoChico, true);
            velocidad = asegurarFlotante(velocidad, anchoChico, altoChico, GL_RG16F, GL_RG, GL_NEAREST);
            historial[0] = asegurarFlotante(historial[0], ancho, alto, GL_RGBA16F, GL_RGBA, GL_LINEAR);
            historial[1] = asegurarFlotante(historial[1], ancho, alto, GL_RGBA16F, GL_RGBA, GL_LINEAR);
            double[] j = SecuenciaJitter.desplazamiento(cuadro++, SecuenciaJitter.fases(anchoChico, ancho));
            jitterX = j[0];
            jitterY = j[1];
            capturado = false;
        } else {
            escalado.setFilterMode(GL_NEAREST);
        }
        activo = true;
        escalado.bindWrite(true);
    }

    /**
     * Bus de NeoForge: al terminar el mundo y antes de la mano (que limpia la
     * profundidad), se guardan la profundidad de la escena y las matrices del
     * cuadro para los vectores de movimiento.
     */
    public static void alEtapa(RenderLevelStageEvent evento) {
        if (!activo || !modo.temporal() || evento.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            return;
        }
        proyeccionConJitter.set(evento.getProjectionMatrix());
        vista.set(evento.getModelViewMatrix());
        camara = evento.getCamera().getPosition();
        profundidadEscena.copyDepthFrom(escalado);
        escalado.bindWrite(true);
        capturado = true;
    }

    /** Mixin de GameRenderer, justo después de dibujar el mundo: escala hacia el framebuffer real. */
    public static void despuesDelMundo() {
        if (!activo) {
            return;
        }
        activo = false;
        RenderTarget principal = Minecraft.getInstance().getMainRenderTarget();
        if (modo.temporal() && capturado) {
            if (modo != ModoEscalado.TEMPORAL && prepararVulkan() && resolverVulkan(principal)) {
                return;
            }
            if (modo != ModoEscalado.TEMPORAL && avisoSinBackend != modo) {
                avisoSinBackend = modo;
                LOG.warn("LOD: {} no está disponible; se usa el escalador temporal propio", modo);
            }
            resolverTemporal(principal);
            return;
        }
        pasada(easu, intermedio, s -> {
            RenderSystem.setShaderTexture(0, escalado.getColorTextureId());
            s.safeGetUniform("InSize").set((float) escalado.width, (float) escalado.height);
            s.safeGetUniform("OutSize").set((float) intermedio.width, (float) intermedio.height);
        });
        nitidez(intermedio.getColorTextureId(), intermedio.width, intermedio.height, principal);
    }

    /** Matrices y estado de movimiento de este cuadro (ver {@link MovimientoCamara}). */
    private record Movimiento(Matrix4f inversaConJitter, Matrix4f actualSinJitter, Matrix4f anteriorSinJitter,
                              Vec3 delta, boolean reiniciar) {
    }

    private static Movimiento movimientoDelCuadro() {
        Matrix4f sinJitter = MovimientoCamara.sinJitter(proyeccionConJitter, jitterX, jitterY,
                escalado.width, escalado.height);
        Matrix4f actualSinJitter = new Matrix4f(sinJitter).mul(vista);
        Matrix4f inversaConJitter = new Matrix4f(proyeccionConJitter).mul(vista).invert();
        Vec3 delta = camara.subtract(camaraAnterior);
        boolean reiniciar = reiniciarHistorial || !hayAnterior || delta.lengthSqr() > SALTO_CAMARA * SALTO_CAMARA;
        return new Movimiento(inversaConJitter, actualSinJitter, reiniciar ? actualSinJitter : anteriorSinJitter,
                delta, reiniciar);
    }

    /** Vectores de movimiento hacia {@code destino}; {@code escala} (1, 1) = coordenadas de textura. */
    private static void pasadaMovimiento(Movimiento m, Runnable destino, float escalaX, float escalaY) {
        pasada(movimiento, destino, s -> {
            RenderSystem.setShaderTexture(0, profundidadEscena.getDepthTextureId());
            RenderSystem.setShaderTexture(1, escalado.getDepthTextureId());
            s.safeGetUniform("InSize").set((float) escalado.width, (float) escalado.height);
            s.safeGetUniform("InversaConJitter").set(m.inversaConJitter());
            s.safeGetUniform("ActualSinJitter").set(m.actualSinJitter());
            s.safeGetUniform("AnteriorSinJitter").set(m.anteriorSinJitter());
            s.safeGetUniform("DeltaCamara").set((float) m.delta().x, (float) m.delta().y, (float) m.delta().z);
            s.safeGetUniform("EscalaVelocidad").set(escalaX, escalaY);
        });
    }

    private static void cerrarCuadro(Movimiento m) {
        anteriorSinJitter.set(m.actualSinJitter());
        camaraAnterior = camara;
        hayAnterior = true;
        reiniciarHistorial = false;
    }

    private static void resolverTemporal(RenderTarget principal) {
        Movimiento m = movimientoDelCuadro();
        pasadaMovimiento(m, () -> velocidad.bindWrite(true), 1, 1);
        ObjetivoFlotante previo = historial[historialActual];
        ObjetivoFlotante nuevo = historial[1 - historialActual];
        pasada(temporal, nuevo, s -> {
            RenderSystem.setShaderTexture(0, escalado.getColorTextureId());
            RenderSystem.setShaderTexture(1, velocidad.getColorTextureId());
            RenderSystem.setShaderTexture(2, previo.getColorTextureId());
            RenderSystem.setShaderTexture(3, profundidadEscena.getDepthTextureId());
            s.safeGetUniform("InSize").set((float) escalado.width, (float) escalado.height);
            s.safeGetUniform("OutSize").set((float) nuevo.width, (float) nuevo.height);
            s.safeGetUniform("Jitter").set((float) jitterX, (float) jitterY);
            s.safeGetUniform("Reiniciar").set(m.reiniciar() ? 1f : 0f);
        });
        historialActual = 1 - historialActual;
        nitidez(nuevo.getColorTextureId(), nuevo.width, nuevo.height, principal);
        cerrarCuadro(m);
    }

    /**
     * XeSS / DLSS: las entradas se copian a imágenes compartidas con Vulkan,
     * el escalador corre allá y su salida vuelve como textura de OpenGL.
     *
     * @return false si falló (se cae al temporal propio)
     */
    private static boolean resolverVulkan(RenderTarget principal) {
        try {
            Movimiento m = movimientoDelCuadro();
            // Vectores en píxeles de entrada, como los piden XeSS y DLSS.
            pasadaMovimiento(m, vkVelocidad::atarParaEscribir, escalado.width, escalado.height);
            GlStateManager._glBindFramebuffer(36008, escalado.frameBufferId);
            GlStateManager._glBindFramebuffer(36009, vkColor.framebufferGl);
            GlStateManager._glBlitFrameBuffer(0, 0, escalado.width, escalado.height, 0, 0, vkColor.ancho, vkColor.alto,
                    16384, GL_NEAREST);
            pasada(profundidad, vkProfundidad::atarParaEscribir,
                    s -> RenderSystem.setShaderTexture(0, profundidadEscena.getDepthTextureId()));
            Matrix4f proyeccionSinJitter = MovimientoCamara.sinJitter(proyeccionConJitter, jitterX, jitterY,
                    escalado.width, escalado.height);
            // clip actual → clip anterior: deshacer la vista y proyección actuales, moverse a la cámara
            // anterior y aplicar las del cuadro anterior.
            Matrix4f clipAAnterior = new Matrix4f(m.anteriorSinJitter())
                    .translate((float) m.delta().x, (float) m.delta().y, (float) m.delta().z)
                    .mul(new Matrix4f(m.actualSinJitter()).invert());
            EscaladorVulkan.Camara camaraCuadro = new EscaladorVulkan.Camara(proyeccionSinJitter, new Matrix4f(vista),
                    clipAAnterior, 0.05f, Minecraft.getInstance().gameRenderer.getDepthFar());
            EscaladorVulkan.Entradas entradas = new EscaladorVulkan.Entradas(vkColor, vkVelocidad, vkProfundidad,
                    vkSalida, camaraCuadro);
            double signo = ConfigLod.CLIENTE.invertirJitter.get() ? -1 : 1;
            vk.ejecutar(cmd -> escaladorVk.grabar(cmd, entradas, signo * jitterX, signo * jitterY, m.reiniciar()));
            nitidez(vkSalida.texturaGl, vkSalida.ancho, vkSalida.alto, principal);
            cerrarCuadro(m);
            return true;
        } catch (RuntimeException e) {
            LOG.error("LOD: {} falló; se usa el escalador temporal propio", escaladorVk.nombre(), e);
            modoVkFallido = modo;
            cerrarVulkan();
            return false;
        }
    }

    /** Levanta (o reusa) el puente y el escalador del modo actual, con imágenes del tamaño actual. */
    private static boolean prepararVulkan() {
        if (modoVkFallido == modo) {
            return false;
        }
        try {
            if (vk == null || modoVk != modo) {
                cerrarVulkan();
                escaladorVk = crearEscaladorVulkan(modo);
                if (escaladorVk == null) {
                    modoVkFallido = modo;
                    return false;
                }
                vk = InteropVulkan.crear(escaladorVk.requisitos());
                if (vk == null) {
                    modoVkFallido = modo;
                    cerrarVulkan();
                    return false;
                }
                modoVk = modo;
            }
            if (vkColor == null || vkColor.ancho != escalado.width || vkColor.alto != escalado.height
                    || vkSalida.ancho != intermedio.width || vkSalida.alto != intermedio.height) {
                liberarImagenesVulkan();
                int entrada = VK_USO_ENTRADA, salida = VK_USO_ENTRADA | VK_USO_ALMACENAMIENTO;
                vkColor = vk.crearImagen(escalado.width, escalado.height, VK_RGBA8, GL_RGBA8, entrada);
                vkVelocidad = vk.crearImagen(escalado.width, escalado.height, VK_RG16F, GL_RG16F, entrada);
                vkProfundidad = vk.crearImagen(escalado.width, escalado.height, VK_R32F, GL_R32F, entrada);
                vkSalida = vk.crearImagen(intermedio.width, intermedio.height, VK_RGBA16F, GL_RGBA16F, salida);
                escaladorVk.preparar(vk, escalado.width, escalado.height, intermedio.width, intermedio.height);
                reiniciarHistorial = true;
            }
            return true;
        } catch (RuntimeException | LinkageError e) {
            LOG.error("LOD: no se pudo preparar {}; se usa el escalador temporal propio", modo, e);
            modoVkFallido = modo;
            cerrarVulkan();
            return false;
        }
    }

    private static EscaladorVulkan crearEscaladorVulkan(ModoEscalado modo) {
        if (Boolean.getBoolean("minecraftlodmod.pruebaVulkan")) {
            return new EscaladorBlit();
        }
        return switch (modo) {
            case XESS -> EscaladorXess.cargar();
            case DLSS -> EscaladorDlss.cargar();
            default -> null;
        };
    }

    private static void liberarImagenesVulkan() {
        if (vk != null) {
            vk.liberar(vkColor);
            vk.liberar(vkVelocidad);
            vk.liberar(vkProfundidad);
            vk.liberar(vkSalida);
        }
        vkColor = vkVelocidad = vkProfundidad = vkSalida = null;
    }

    private static void cerrarVulkan() {
        try {
            liberarImagenesVulkan();
            if (escaladorVk != null) {
                escaladorVk.close();
            }
            if (vk != null) {
                vk.close();
            }
        } catch (RuntimeException e) {
            LOG.warn("LOD: error al cerrar Vulkan", e);
        }
        escaladorVk = null;
        vk = null;
        modoVk = null;
    }

    /** RCAS de la textura {@code origen} (tamaño de pantalla) al framebuffer real. */
    private static void nitidez(int origen, int ancho, int alto, RenderTarget principal) {
        pasada(rcas, principal, s -> {
            RenderSystem.setShaderTexture(0, origen);
            s.safeGetUniform("OutSize").set((float) ancho, (float) alto);
            s.safeGetUniform("Nitidez").set(ConfigLod.CLIENTE.fsrNitidez.get().floatValue());
        });
        principal.bindWrite(true);
    }

    /** Modo pedido en la config, o APAGADO si falta algo o choca con otra cosa. */
    private static ModoEscalado modoHabilitado(Minecraft mc) {
        ModoEscalado enConfig = ConfigLod.CLIENTE.escalado.get();
        ModoEscalado pedido = CompatibilidadEscalado.efectivo(enConfig, modosDisponibles());
        if (pedido != enConfig && avisadoNoDisponible != enConfig) {
            avisadoNoDisponible = enConfig;
            LOG.warn("LOD: {} no es compatible con esta GPU o sistema; se usa {}", enConfig, pedido);
        }
        if (pedido == ModoEscalado.APAGADO || easu == null || rcas == null) {
            return ModoEscalado.APAGADO;
        }
        if (pedido.temporal() && (movimiento == null || temporal == null || profundidad == null)) {
            return ModoEscalado.FSR1;
        }
        boolean fabulous = mc.options.graphicsMode().get() == GraphicsStatus.FABULOUS;
        boolean iris = ModList.get().isLoaded("iris") || ModList.get().isLoaded("oculus");
        if (fabulous || iris) {
            if (!avisoIncompatible) {
                avisoIncompatible = true;
                LOG.warn("LOD: escalado desactivado ({})", fabulous ? "gráficos Fabulous" : "Iris/Oculus instalado");
            }
            return ModoEscalado.APAGADO;
        }
        avisoIncompatible = false;
        return pedido;
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

    private static ObjetivoFlotante asegurarFlotante(ObjetivoFlotante t, int ancho, int alto, int formatoInterno,
                                                     int formato, int filtro) {
        if (t == null) {
            t = new ObjetivoFlotante(formatoInterno, formato);
            t.resize(ancho, alto, Minecraft.ON_OSX);
            t.setFilterMode(filtro);
            reiniciarHistorial = true;
        } else if (t.width != ancho || t.height != alto) {
            t.resize(ancho, alto, Minecraft.ON_OSX);
            t.setFilterMode(filtro);
            reiniciarHistorial = true;
        }
        return t;
    }

    /** Cuadrado de pantalla completa con {@code shader} hacia {@code destino}. */
    private static void pasada(ShaderInstance shader, RenderTarget destino, Consumer<ShaderInstance> uniforms) {
        pasada(shader, () -> destino.bindWrite(true), uniforms);
    }

    /** Idem, con el destino atado por {@code atarDestino} (framebuffer y viewport). */
    private static void pasada(ShaderInstance shader, Runnable atarDestino, Consumer<ShaderInstance> uniforms) {
        atarDestino.run();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableBlend();
        RenderSystem.setShader(() -> shader);
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

    /**
     * Framebuffer con color de 16 bits en punto flotante (historial y
     * vectores de movimiento): el de Minecraft es RGBA8, que no alcanza para
     * acumular sin bandas ni para velocidades sub-píxel. Se arma como el de
     * Minecraft y se cambia solo el formato de la textura de color.
     */
    static final class ObjetivoFlotante extends RenderTarget {
        private final int formatoInterno, formato;

        ObjetivoFlotante(int formatoInterno, int formato) {
            super(false);
            this.formatoInterno = formatoInterno;
            this.formato = formato;
        }

        @Override
        public void createBuffers(int ancho, int alto, boolean osx) {
            super.createBuffers(ancho, alto, osx);
            GlStateManager._bindTexture(getColorTextureId());
            GlStateManager._texImage2D(3553, 0, formatoInterno, ancho, alto, 0, formato, 5126, null);
            GlStateManager._bindTexture(0);
        }
    }
}
