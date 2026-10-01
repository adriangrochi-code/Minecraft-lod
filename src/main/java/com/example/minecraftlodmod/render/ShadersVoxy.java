package com.example.minecraftlodmod.render;

import com.example.minecraftlodmod.render.mixin.AccesoShaderPackIris;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.logging.LogUtils;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.state.ValueUpdateNotifier;
import net.irisshaders.iris.gl.uniform.DynamicLocationalUniformHolder;
import net.irisshaders.iris.gl.uniform.LocationalUniformHolder;
import net.irisshaders.iris.gl.uniform.Uniform;
import net.irisshaders.iris.gl.uniform.UniformHolder;
import net.irisshaders.iris.gl.uniform.UniformType;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;
import net.irisshaders.iris.shaderpack.include.IncludeProcessor;
import net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.uniforms.CommonUniforms;
import net.irisshaders.iris.uniforms.builtin.BuiltinReplacementUniforms;
import net.irisshaders.iris.gl.state.FogMode;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Contrato Voxy de los shaderpacks (sección 27, punto 2b), del lado de Iris.
 * Solo se carga con Iris instalado (lo llama {@link RenderLod} con un pack activo).
 *
 * Etapa A (esta): cuando cambia el pack o la dimensión, lee {@code voxy.json} y
 * {@code voxy_opaque/translucent.glsl} ya preprocesados por Iris (opciones del
 * pack aplicadas, ver {@code MixinShaderPackIris}), declara los uniforms que
 * pide con el tipo que Iris les da, compila y enlaza con el vértice del LOD
 * ({@link ContratoVoxy}) y deja el resultado en el log. Todavía no dibuja:
 * escribir en los buffers de Iris (drawbuffers del json, profundidad propia
 * vxDepthTex*, VOXY definido para deferred/composite) es la etapa B.
 */
public final class ShadersVoxy {

    private static final Logger LOG = LogUtils.getLogger();
    private static final Pattern LINEA_ERROR = Pattern.compile("^\\s*\\d+[:(](\\d+)");

    // Constantes de OpenGL 2.0 (GlStateManager no las trae).
    private static final int GL_FRAGMENT_SHADER = 0x8B30;
    private static final int GL_VERTEX_SHADER = 0x8B31;
    private static final int GL_COMPILE_STATUS = 0x8B81;
    private static final int GL_LINK_STATUS = 0x8B82;

    private static ShaderPack packVisto;
    private static NamespacedId dimensionVista;
    private static String estado = "";

    private ShadersVoxy() {
    }

    /** Resumen del último diagnóstico ("" si el pack no trae el contrato). */
    public static String estado() {
        return estado;
    }

    /** Hilo de render, con un shaderpack de Iris activo. Barato si nada cambió. */
    public static void revisar() {
        ShaderPack pack = Iris.getCurrentPack().orElse(null);
        NamespacedId dimension = Iris.getCurrentDimension();
        if (pack == packVisto && Objects.equals(dimension, dimensionVista)) {
            return;
        }
        WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
        if (pack != null && !(pipeline instanceof IrisRenderingPipeline)) {
            return; // el pipeline del pack todavía no está armado
        }
        packVisto = pack;
        dimensionVista = dimension;
        estado = "";
        if (pack == null) {
            return;
        }
        try {
            diagnosticar(pack, dimension, (IrisRenderingPipeline) pipeline);
        } catch (RuntimeException e) {
            estado = "error: " + e;
            LOG.error("LOD/Voxy: no se pudo revisar el contrato Voxy del pack {}", Iris.getCurrentPackName(), e);
        }
    }

    private static void diagnosticar(ShaderPack pack, NamespacedId dimension, IrisRenderingPipeline pipeline) {
        AccesoShaderPackIris acceso = (AccesoShaderPackIris) (Object) pack;
        Function<AbsolutePackPath, String> fuentes = acceso.minecraftlodmod$fuentes();
        String carpeta = carpeta(acceso, dimension);
        FuentesPackIris preproceso = (FuentesPackIris) (Object) pack;
        String json = fuenteVoxy(preproceso, carpeta, "voxy.json");
        String nombrePack = Iris.getCurrentPackName();
        if (json == null) {
            LOG.info("LOD/Voxy: el pack {} no trae voxy.json; el LOD sigue con gbuffers_terrain", nombrePack);
            return;
        }
        guardar("voxy.json", json);
        ContratoVoxy.Programa programa = ContratoVoxy.leer(json);
        // Primero como los declara el propio pack (es el tipo que su código espera: bool, int...);
        // lo que no declare en ningún programa, con el tipo que le da Iris.
        Map<String, String> tipos = tiposDelPack(fuentes, carpeta);
        tiposDeIris(pipeline).forEach(tipos::putIfAbsent);
        List<String> sinTipo = ContratoVoxy.sinTipo(programa, tipos);
        LOG.info("LOD/Voxy: pack {} (carpeta '{}'): voxy.json v{}, buffers opacos {} / translúcidos {}, mezcla {}, "
                        + "{} uniforms ({} sin tipo{}), samplers {}, jitter TAA {}",
                nombrePack, carpeta, programa.version(), programa.buffersOpacos(), programa.buffersTranslucidos(),
                programa.mezcla(), programa.uniforms().size(), sinTipo.size(), sinTipo.isEmpty() ? "" : ": " + sinTipo,
                programa.samplers().keySet(), programa.taaOffset() != null ? "sí" : "no");

        StringBuilder resumen = new StringBuilder();
        for (String archivo : new String[]{"voxy_opaque.glsl", "voxy_translucent.glsl"}) {
            String codigo = fuenteVoxy(preproceso, carpeta, archivo);
            if (codigo == null) {
                LOG.info("LOD/Voxy: {} no está en el pack", archivo);
                continue;
            }
            boolean ok = compilar(archivo, programa, tipos, codigo);
            resumen.append(archivo).append(ok ? " ok " : " con errores ");
        }
        estado = resumen.toString().trim();
    }

    /** La carpeta de programas para esta dimensión, como la elige Iris (ShaderPack#getProgramSet). */
    private static String carpeta(AccesoShaderPackIris acceso, NamespacedId dimension) {
        Map<NamespacedId, String> mapa = acceso.minecraftlodmod$carpetaPorDimension();
        String c = mapa.get(dimension);
        if (c != null && acceso.minecraftlodmod$carpetas().contains(c)) {
            return c;
        }
        return mapa.getOrDefault(new NamespacedId("*", "*"), "");
    }

    private static String fuente(Function<AbsolutePackPath, String> fuentes, String carpeta, String archivo) {
        String s = carpeta.isEmpty() ? null : fuentes.apply(AbsolutePackPath.fromAbsolutePath("/" + carpeta + "/" + archivo));
        return s != null ? s : fuentes.apply(AbsolutePackPath.fromAbsolutePath("/" + archivo));
    }

    /** Programas del pack de donde se toman las declaraciones de uniforms. */
    private static final String[] PROGRAMAS_REFERENCIA = {"gbuffers_terrain.fsh", "gbuffers_terrain.vsh",
            "gbuffers_water.fsh", "deferred1.fsh", "composite1.fsh", "final.fsh"};

    private static Map<String, String> tiposDelPack(Function<AbsolutePackPath, String> fuentes, String carpeta) {
        Map<String, String> tipos = new HashMap<>();
        for (String programa : PROGRAMAS_REFERENCIA) {
            String codigo = fuente(fuentes, carpeta, programa);
            if (codigo != null) {
                ContratoVoxy.declaracionesUniform(codigo).forEach(tipos::putIfAbsent);
            }
        }
        return tipos;
    }

    /**
     * Un archivo Voxy con sus includes, preprocesado como Iris preprocesa los
     * programas del pack pero con {@code VOXY} definido: los packs lo usan para
     * elegir el código de LOD (Complementary: {@code #ifndef VOXY} en sus librerías).
     */
    private static String fuenteVoxy(FuentesPackIris preproceso, String carpeta, String archivo) {
        IncludeProcessor includes = preproceso.minecraftlodmod$includes();
        List<StringPair> definiciones = preproceso.minecraftlodmod$definiciones();
        if (includes == null || definiciones == null) {
            return null;
        }
        List<String> lineas = carpeta.isEmpty() ? null
                : includes.getIncludedFile(AbsolutePackPath.fromAbsolutePath("/" + carpeta + "/" + archivo));
        if (lineas == null) {
            lineas = includes.getIncludedFile(AbsolutePackPath.fromAbsolutePath("/" + archivo));
        }
        if (lineas == null) {
            return null;
        }
        List<StringPair> conVoxy = new ArrayList<>(definiciones);
        conVoxy.add(new StringPair("VOXY", ""));
        return JcppProcessor.glslPreprocessSource(String.join("\n", lineas) + "\n", conVoxy);
    }

    /**
     * Nombre → tipo GLSL de todos los uniforms que Iris le daría a un programa
     * del LOD (los mismos registros que su programa de Distant Horizons), sin
     * crear nada: un registrador que anota cada consulta de ubicación.
     */
    private static Map<String, String> tiposDeIris(IrisRenderingPipeline pipeline) {
        Registrador r = new Registrador();
        CommonUniforms.addDynamicUniforms(r, FogMode.PER_VERTEX);
        pipeline.getCustomUniforms().assignTo(r);
        BuiltinReplacementUniforms.addBuiltinReplacementUniforms(r);
        return r.tipos;
    }

    private static boolean compilar(String archivo, ContratoVoxy.Programa programa, Map<String, String> tipos, String codigo) {
        List<String> extensiones = new ArrayList<>();
        StringBuilder resto = new StringBuilder();
        int version = ContratoVoxy.separarDirectivas(codigo, extensiones, resto);
        String fuentePack = resto.toString();
        String vertice = ContratoVoxy.vertice(programa, tipos, version, extensiones);
        String fragmento = ContratoVoxy.fragmento(programa, tipos, version, extensiones, fuentePack);
        String base = archivo.substring(0, archivo.lastIndexOf('.'));
        guardar(base + ".vsh", vertice);
        guardar(base + ".fsh", fragmento);

        int vs = shader(GL_VERTEX_SHADER, vertice);
        int fs = shader(GL_FRAGMENT_SHADER, fragmento);
        try {
            String errorVs = errorDeShader(vs);
            String errorFs = errorDeShader(fs);
            if (errorVs != null) {
                LOG.error("LOD/Voxy: {}: el vértice del LOD no compila:\n{}", archivo, errorVs);
            }
            if (errorFs != null) {
                LOG.error("LOD/Voxy: {}: el fragmento no compila:\n{}{}", archivo, errorFs,
                        contextoDelError(errorFs, fuentePack));
            }
            if (errorVs != null || errorFs != null) {
                return false;
            }
            int programaGl = GlStateManager.glCreateProgram();
            try {
                GlStateManager.glAttachShader(programaGl, vs);
                GlStateManager.glAttachShader(programaGl, fs);
                GlStateManager.glLinkProgram(programaGl);
                if (GlStateManager.glGetProgrami(programaGl, GL_LINK_STATUS) == 0) {
                    LOG.error("LOD/Voxy: {}: no enlaza:\n{}", archivo,
                            GlStateManager.glGetProgramInfoLog(programaGl, 32768));
                    return false;
                }
                LOG.info("LOD/Voxy: {} compiló y enlazó con el vértice del LOD (GLSL {}, {} extensiones)",
                        archivo, version, extensiones.size());
                return true;
            } finally {
                GlStateManager.glDeleteProgram(programaGl);
            }
        } finally {
            GlStateManager.glDeleteShader(vs);
            GlStateManager.glDeleteShader(fs);
        }
    }

    /**
     * Copia de lo que se compiló en {@code .minecraft/minecraftlodmod/voxy/}: con un
     * pack que falla, es lo que hace falta para saber qué línea rompió.
     */
    private static void guardar(String nombre, String texto) {
        try {
            Path carpeta = FMLPaths.GAMEDIR.get().resolve("minecraftlodmod").resolve("voxy");
            Files.createDirectories(carpeta);
            Files.writeString(carpeta.resolve(nombre), texto);
        } catch (IOException e) {
            LOG.debug("LOD/Voxy: no se pudo guardar {}", nombre, e);
        }
    }

    private static int shader(int tipo, String fuente) {
        int id = GlStateManager.glCreateShader(tipo);
        GlStateManager.glShaderSource(id, List.of(fuente));
        GlStateManager.glCompileShader(id);
        return id;
    }

    /** null si compiló; si no, el log del compilador. */
    private static String errorDeShader(int id) {
        if (GlStateManager.glGetShaderi(id, GL_COMPILE_STATUS) != 0) {
            return null;
        }
        return GlStateManager.glGetShaderInfoLog(id, 32768);
    }

    /** Las líneas del pack donde cae el primer error (los números salen del {@code #line 1}). */
    private static String contextoDelError(String log, String fuentePack) {
        for (String linea : log.split("\n")) {
            Matcher m = LINEA_ERROR.matcher(linea);
            if (linea.toLowerCase(java.util.Locale.ROOT).contains("error") && m.find()) {
                int n = Integer.parseInt(m.group(1));
                if (n >= 100000) {
                    return "(en el main del LOD, línea " + (n - 100000 + 1) + ")\n";
                }
                return "código del pack alrededor de la línea " + n + ":\n" + ContratoVoxy.contexto(fuentePack, n, 3);
            }
        }
        return "";
    }

    private static String glsl(UniformType tipo) {
        return switch (tipo) {
            case INT -> "int";
            case FLOAT -> "float";
            case MAT3 -> "mat3";
            case MAT4 -> "mat4";
            case VEC2 -> "vec2";
            case VEC2I -> "ivec2";
            case VEC3 -> "vec3";
            case VEC3I -> "ivec3";
            case VEC4 -> "vec4";
            case VEC4I -> "ivec4";
        };
    }

    /** Anota nombre y tipo de cada uniform que Iris registraría; no crea ninguno. */
    private static final class Registrador implements DynamicLocationalUniformHolder {
        final Map<String, String> tipos = new HashMap<>();

        @Override
        public OptionalInt location(String nombre, UniformType tipo) {
            tipos.putIfAbsent(nombre, glsl(tipo));
            return OptionalInt.empty();
        }

        @Override
        public LocationalUniformHolder uniform1b(UniformUpdateFrequency frecuencia, String nombre, BooleanSupplier valor) {
            // Iris los sube como int, pero los packs los declaran bool.
            tipos.put(nombre, "bool");
            return this;
        }

        @Override
        public UniformHolder externallyManagedUniform(String nombre, UniformType tipo) {
            location(nombre, tipo);
            return this;
        }

        @Override
        public LocationalUniformHolder addUniform(UniformUpdateFrequency frecuencia, Uniform uniform) {
            return this;
        }

        @Override
        public DynamicLocationalUniformHolder addDynamicUniform(Uniform uniform, ValueUpdateNotifier notificador) {
            return this;
        }
    }
}
