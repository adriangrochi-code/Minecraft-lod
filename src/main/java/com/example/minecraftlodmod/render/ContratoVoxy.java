package com.example.minecraftlodmod.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * El contrato que los shaderpacks definen para mods de LOD al estilo Voxy
 * (sección 27, punto 2b): {@code voxy.json} + {@code voxy_opaque.glsl} /
 * {@code voxy_translucent.glsl}. Solo se leen los archivos del pack; nada del
 * código de Voxy.
 *
 * El pack aporta el código de fragmento ({@code voxy_emitFragment}) y la lista
 * de uniforms y samplers que usa; el mod pone la entrada de vértices, declara
 * esos uniforms con su tipo y llama al pack desde su propio {@code main}.
 * Lógica pura: arma el texto GLSL. Compilarlo y conectarlo a Iris es
 * {@link ShadersVoxy}.
 */
public final class ContratoVoxy {

    /** Los archivos del contrato en la carpeta de programas del pack. */
    public static final List<String> ARCHIVOS = List.of("voxy.json", "voxy_opaque.glsl", "voxy_translucent.glsl");

    /** Lo que el mod aporta como Voxy: los uniforms vx* (el pack los nombra en su lista). */
    public static final Map<String, String> UNIFORMS_PROPIOS;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("vxRenderDistance", "int");
        for (String n : new String[]{"vxModelView", "vxModelViewInv", "vxModelViewPrev",
                "vxProj", "vxProjInv", "vxProjPrev"}) {
            m.put(n, "mat4");
        }
        UNIFORMS_PROPIOS = Collections.unmodifiableMap(m);
    }

    /** Versión de GLSL si el pack no pide una mayor. */
    static final int VERSION_GLSL = 430;

    /**
     * {@code voxy.json} ya leído.
     *
     * @param buffersOpacos      a qué colortex escriben las salidas de voxy_opaque, en orden
     * @param mezcla             modo de mezcla por buffer (-1 = todos), texto tal cual del pack
     * @param taaOffset          cuerpo de la función de jitter del TAA ({@code { ... }}), o null
     * @param samplers           nombre → tipo GLSL
     */
    public record Programa(int version, boolean excluirDeProfundidadVanilla,
                           List<Integer> buffersOpacos, List<Integer> buffersTranslucidos,
                           Map<Integer, String> mezcla, String taaOffset,
                           List<String> uniforms, Map<String, String> samplers) {
    }

    private ContratoVoxy() {
    }

    /**
     * Lee el {@code voxy.json} ya preprocesado por Iris (opciones e includes
     * aplicados). Es JSON laxo: claves sin comillas ({@code -1:}), cadenas de
     * varias líneas y comentarios. El código incluido dentro de las cadenas
     * (Complementary mete ahí su common.glsl) puede traer comentarios con
     * comillas y barras invertidas que no son escapes de JSON: los comentarios
     * se quitan y las barras se toman literales.
     */
    public static Programa leer(String json) {
        JsonReader lector = new JsonReader(new StringReader(sanearEscapes(sinComentarios(json))));
        lector.setLenient(true);
        JsonElement raiz = JsonParser.parseReader(lector);
        if (!raiz.isJsonObject()) {
            throw new JsonParseException("voxy.json no es un objeto");
        }
        JsonObject o = raiz.getAsJsonObject();
        Map<Integer, String> mezcla = new LinkedHashMap<>();
        if (o.has("blending") && o.get("blending").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("blending").entrySet()) {
                mezcla.put(Integer.parseInt(e.getKey().trim()), e.getValue().getAsString().trim());
            }
        }
        List<String> uniforms = new ArrayList<>();
        if (o.has("uniforms")) {
            for (JsonElement e : o.getAsJsonArray("uniforms")) {
                uniforms.add(e.getAsString().trim());
            }
        }
        Map<String, String> samplers = new LinkedHashMap<>();
        if (o.has("samplers") && o.get("samplers").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("samplers").entrySet()) {
                samplers.put(e.getKey().trim(), e.getValue().getAsString().trim());
            }
        }
        String taa = o.has("taaOffset") ? o.get("taaOffset").getAsString().trim() : null;
        return new Programa(
                o.has("version") ? o.get("version").getAsInt() : 1,
                o.has("excludeLodsFromVanillaDepth") && o.get("excludeLodsFromVanillaDepth").getAsBoolean(),
                enteros(o, "opaqueDrawBuffers"), enteros(o, "translucentDrawBuffers"),
                Collections.unmodifiableMap(mezcla), taa == null || taa.isEmpty() ? null : taa,
                Collections.unmodifiableList(uniforms), Collections.unmodifiableMap(samplers));
    }

    /** Factores de mezcla de OpenGL por nombre (los que usan los packs en {@code blending}). */
    private static final Map<String, Integer> FACTORES = Map.ofEntries(
            Map.entry("ZERO", 0), Map.entry("ONE", 1),
            Map.entry("SRC_COLOR", 0x0300), Map.entry("ONE_MINUS_SRC_COLOR", 0x0301),
            Map.entry("SRC_ALPHA", 0x0302), Map.entry("ONE_MINUS_SRC_ALPHA", 0x0303),
            Map.entry("DST_ALPHA", 0x0304), Map.entry("ONE_MINUS_DST_ALPHA", 0x0305),
            Map.entry("DST_COLOR", 0x0306), Map.entry("ONE_MINUS_DST_COLOR", 0x0307),
            Map.entry("SRC_ALPHA_SATURATE", 0x0308));

    /**
     * Mezcla de la salida {@code salida} de voxy_translucent: la suya del json, si no la de
     * {@code -1}. Devuelve los factores de OpenGL (fuente y destino de color, fuente y
     * destino de alfa), o null = sin mezcla ({@code off}, o nada en el json).
     *
     * @throws IllegalArgumentException si el texto no son 2 o 4 factores conocidos
     */
    public static int[] mezcla(Map<Integer, String> mezcla, int salida) {
        String modo = mezcla.getOrDefault(salida, mezcla.get(-1));
        if (modo == null || modo.equalsIgnoreCase("off")) {
            return null;
        }
        String[] partes = modo.trim().split("\\s+");
        if (partes.length != 2 && partes.length != 4) {
            throw new IllegalArgumentException("mezcla rara: " + modo);
        }
        int[] factores = new int[4];
        for (int i = 0; i < 4; i++) {
            Integer f = FACTORES.get(partes[partes.length == 2 ? i % 2 : i].toUpperCase(java.util.Locale.ROOT));
            if (f == null) {
                throw new IllegalArgumentException("factor de mezcla desconocido en: " + modo);
            }
            factores[i] = f;
        }
        return factores;
    }

    /** Quita los comentarios {@code //} y {@code /* *}{@code /} (Iris los conserva al preprocesar). */
    static String sinComentarios(String texto) {
        StringBuilder s = new StringBuilder(texto.length());
        int i = 0;
        while (i < texto.length()) {
            if (texto.startsWith("//", i)) {
                int fin = texto.indexOf('\n', i);
                i = fin < 0 ? texto.length() : fin;
            } else if (texto.startsWith("/*", i)) {
                int fin = texto.indexOf("*/", i + 2);
                int hasta = fin < 0 ? texto.length() : fin + 2;
                for (int j = i; j < hasta; j++) {
                    if (texto.charAt(j) == '\n') {
                        s.append('\n');
                    }
                }
                i = hasta;
            } else {
                s.append(texto.charAt(i++));
            }
        }
        return s.toString();
    }

    /** Duplica cada {@code \\} que no empieza un escape válido de JSON. */
    static String sanearEscapes(String json) {
        StringBuilder s = new StringBuilder(json.length() + 16);
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c != '\\') {
                s.append(c);
            } else if (i + 1 < json.length() && "\"\\/bfnrtu".indexOf(json.charAt(i + 1)) >= 0) {
                s.append(c).append(json.charAt(++i));
            } else {
                s.append("\\\\");
            }
        }
        return s.toString();
    }

    private static List<Integer> enteros(JsonObject o, String clave) {
        List<Integer> r = new ArrayList<>();
        if (o.has(clave) && o.get(clave).isJsonArray()) {
            JsonArray a = o.getAsJsonArray(clave);
            for (JsonElement e : a) {
                r.add(e.getAsInt());
            }
        }
        return Collections.unmodifiableList(r);
    }

    private static final java.util.regex.Pattern DECLARACION_UNIFORM = java.util.regex.Pattern.compile(
            "\\buniform\\s+(?:(?:lowp|mediump|highp)\\s+)?(\\w+)\\s+(\\w+)\\s*(?:=[^;]*)?;");

    /**
     * Nombre → tipo de las declaraciones {@code uniform tipo nombre;} de un código
     * del pack (sus programas normales declaran los uniforms que en voxy_* omite).
     */
    public static Map<String, String> declaracionesUniform(String codigo) {
        Map<String, String> r = new LinkedHashMap<>();
        java.util.regex.Matcher m = DECLARACION_UNIFORM.matcher(codigo);
        while (m.find()) {
            if (!m.group(1).startsWith("sampler") && !m.group(1).startsWith("image")) {
                r.putIfAbsent(m.group(2), m.group(1));
            }
        }
        return r;
    }

    /** Los uniforms que pide el pack y que no se sabe declarar (ni Iris ni el mod los conocen). */
    public static List<String> sinTipo(Programa p, Map<String, String> tipos) {
        List<String> r = new ArrayList<>();
        for (String u : p.uniforms()) {
            if (tipo(u, tipos) == null) {
                r.add(u);
            }
        }
        return r;
    }

    private static String tipo(String nombre, Map<String, String> tipos) {
        String t = UNIFORMS_PROPIOS.get(nombre);
        return t != null ? t : tipos.get(nombre);
    }

    /**
     * Cara del LOD (0 -X, 1 +X, 2 -Y, 3 +Y, 4 -Z, 5 +Z) a la de Voxy: eje en
     * {@code cara >> 1} (0 Y, 1 Z, 2 X) y bit 0 = sentido positivo.
     */
    public static int caraVoxy(int cara) {
        int eje = cara >> 1;
        int ejeVoxy = eje == 1 ? 0 : eje == 2 ? 1 : 2;
        return ejeVoxy * 2 + (cara & 1);
    }

    // ------------------------------------------------------------------ GLSL

    /** Cabecera común: versión, extensiones del pack y uniforms/samplers declarados. */
    private static String cabecera(Programa p, Map<String, String> tipos, int version, List<String> extensiones) {
        StringBuilder s = new StringBuilder();
        s.append("#version ").append(version).append(" core\n");
        for (String e : extensiones) {
            s.append(e).append('\n');
        }
        Set<String> declarados = new LinkedHashSet<>();
        for (Map.Entry<String, String> e : UNIFORMS_PROPIOS.entrySet()) {
            declarar(s, declarados, e.getValue(), e.getKey());
        }
        for (String u : p.uniforms()) {
            String t = tipo(u, tipos);
            if (t != null) {
                declarar(s, declarados, t, u);
            }
        }
        for (Map.Entry<String, String> e : p.samplers().entrySet()) {
            declarar(s, declarados, e.getValue(), e.getKey());
        }
        s.append("uniform sampler2D lodvx_Atlas;\n");
        s.append("uniform sampler2D lodvx_Sprites;\n");
        s.append("uniform isampler2D lodvx_Ids;\n");
        return s.toString();
    }

    private static void declarar(StringBuilder s, Set<String> declarados, String tipo, String nombre) {
        if (declarados.add(nombre)) {
            s.append("uniform ").append(tipo).append(' ').append(nombre).append(";\n");
        }
    }

    /**
     * Vértice: el formato compacto de 12 B del LOD (mismo que {@code lod_textura.vsh}),
     * proyectado con vxProj/vxModelView y con el jitter de TAA del pack.
     */
    public static String vertice(Programa p, Map<String, String> tipos, int version, List<String> extensiones) {
        StringBuilder s = new StringBuilder(cabecera(p, tipos, version, extensiones));
        s.append("""
                in ivec4 PosSprite;
                in vec4 Color;
                in int Extra;
                uniform vec3 lodvx_ChunkOffset;
                uniform vec2 lodvx_Curvatura;
                out vec3 lodvx_posLocal;
                out vec4 lodvx_color;
                out vec2 lodvx_uvOrigen;
                out vec2 lodvx_uvTamano;
                out vec3 lodvx_promedio;
                out float lodvx_luzBloque;
                out float lodvx_luzCielo;
                flat out int lodvx_cara;
                flat out int lodvx_estado;
                flat out int lodvx_agua;
                """);
        s.append("vec2 lodvx_taaOffset() ");
        s.append(p.taaOffset() != null ? p.taaOffset() : "{ return vec2(0.0); }");
        s.append('\n');
        s.append("""
                ivec4 lodvx_texel(int columna, int fila) {
                    return ivec4(texelFetch(lodvx_Sprites, ivec2(columna, fila), 0) * 255.0 + 0.5);
                }
                void main() {
                    vec3 pos = vec3(PosSprite.xyz);
                    vec3 relativa = pos + lodvx_ChunkOffset;
                    float lejos = max(0.0, length(relativa.xz) - lodvx_Curvatura.y);
                    relativa.y -= lodvx_Curvatura.x * lejos * lejos;
                    gl_Position = vxProj * vxModelView * vec4(relativa, 1.0);
                    gl_Position.xy += lodvx_taaOffset() * 2.0 * gl_Position.w;
                    lodvx_posLocal = pos;
                    lodvx_color = vec4(Color.rgb, 1.0);
                    int alfa = int(Color.a * 255.0 + 0.5);
                    lodvx_cara = alfa & 7;
                    int sprite = PosSprite.w & 0x3FFF;
                    lodvx_luzBloque = float((PosSprite.w >> 14) & 3);
                    lodvx_estado = Extra & 0xFFFF;
                    lodvx_luzCielo = float((Extra >> 16) & 15);
                    lodvx_agua = (Extra >> 20) & 1;
                    lodvx_uvOrigen = vec2(0.0);
                    lodvx_uvTamano = vec2(0.0);
                    lodvx_promedio = vec3(1.0);
                    if (sprite != 0) {
                        int columna = (sprite % 256) * 4;
                        int fila = sprite / 256;
                        ivec4 origen = lodvx_texel(columna, fila);
                        ivec4 tamano = lodvx_texel(columna + 1, fila);
                        vec2 atlas = vec2(textureSize(lodvx_Atlas, 0));
                        lodvx_uvOrigen = vec2(origen.r | (origen.g << 8), origen.b | (origen.a << 8)) / atlas;
                        lodvx_uvTamano = vec2(tamano.r | (tamano.g << 8), tamano.b | (tamano.a << 8)) / atlas;
                        lodvx_promedio = texelFetch(lodvx_Sprites, ivec2(columna + 2, fila), 0).rgb;
                    }
                }
                """);
        return s.toString();
    }

    /**
     * Fragmento: declaraciones + la estructura de parámetros + el código del pack
     * (con {@code #line 1}, así los errores del compilador apuntan a sus líneas) +
     * un {@code main} que muestrea la textura del LOD y llama a {@code voxy_emitFragment}.
     */
    public static String fragmento(Programa p, Map<String, String> tipos, int version,
                                   List<String> extensiones, String fuentePack) {
        StringBuilder s = new StringBuilder(cabecera(p, tipos, version, extensiones));
        s.append("""
                struct VoxyFragmentParameters {
                    vec4 sampledColour;
                    vec2 tile;
                    vec2 uv;
                    uint face;
                    uint modelId;
                    vec2 lightMap;
                    vec4 tinting;
                    uint customId;
                };
                in vec3 lodvx_posLocal;
                in vec4 lodvx_color;
                in vec2 lodvx_uvOrigen;
                in vec2 lodvx_uvTamano;
                in vec3 lodvx_promedio;
                in float lodvx_luzBloque;
                in float lodvx_luzCielo;
                flat in int lodvx_cara;
                flat in int lodvx_estado;
                flat in int lodvx_agua;
                #line 1
                """);
        s.append(fuentePack);
        s.append("""

                #line 100000
                const uint LODVX_CARA[6] = uint[6](4u, 5u, 0u, 1u, 2u, 3u);
                const float LODVX_ALFA_AGUA = 0.75;
                void main() {
                    vec3 color = lodvx_color.rgb;
                    float alfa = lodvx_agua != 0 ? LODVX_ALFA_AGUA : 1.0;
                    vec2 repeticion = vec2(0.0);
                    if (lodvx_uvTamano.x > 0.0) {
                        int eje = lodvx_cara / 2;
                        if (eje == 1) {
                            repeticion = lodvx_posLocal.xz;
                        } else if (eje == 0) {
                            repeticion = vec2(lodvx_posLocal.z, -lodvx_posLocal.y);
                        } else {
                            repeticion = vec2(lodvx_posLocal.x, -lodvx_posLocal.y);
                        }
                        vec2 uv = lodvx_uvOrigen + fract(repeticion) * lodvx_uvTamano;
                        vec4 tex = textureGrad(lodvx_Atlas, uv, dFdx(repeticion) * lodvx_uvTamano,
                                               dFdy(repeticion) * lodvx_uvTamano);
                        color *= mix(vec3(1.0), tex.rgb / max(lodvx_promedio, vec3(1.0 / 255.0)), tex.a);
                        if (lodvx_agua != 0) {
                            alfa = tex.a;
                        }
                    }
                    VoxyFragmentParameters p;
                    p.sampledColour = vec4(color, alfa);
                    p.tile = vec2(0.0);
                    p.uv = fract(repeticion);
                    p.face = LODVX_CARA[clamp(lodvx_cara, 0, 5)];
                    p.modelId = 0u;
                    p.lightMap = vec2((lodvx_luzBloque * 5.0 + 0.5) / 16.0, (lodvx_luzCielo + 0.5) / 16.0);
                    p.tinting = vec4(1.0);
                    ivec2 tamIds = textureSize(lodvx_Ids, 0);
                    p.customId = uint(max(0, texelFetch(lodvx_Ids,
                            ivec2(lodvx_estado % tamIds.x, min(lodvx_estado / tamIds.x, tamIds.y - 1)), 0).r));
                    voxy_emitFragment(p);
                }
                """);
        return s.toString();
    }

    /**
     * Separa del código del pack las directivas que tienen que ir arriba de todo
     * ({@code #version}, {@code #extension}): devuelve la versión a usar (la mayor
     * entre la del pack y {@link #VERSION_GLSL}), las extensiones en {@code extensiones}
     * y el resto del código en {@code resto}.
     */
    public static int separarDirectivas(String fuente, List<String> extensiones, StringBuilder resto) {
        int version = VERSION_GLSL;
        for (String linea : fuente.split("\n", -1)) {
            String l = linea.trim();
            if (l.startsWith("#version")) {
                String[] partes = l.split("\\s+");
                if (partes.length > 1) {
                    try {
                        version = Math.max(version, Integer.parseInt(partes[1]));
                    } catch (NumberFormatException ignorada) {
                        // versión rara: queda la nuestra
                    }
                }
                resto.append('\n'); // mantiene la numeración de líneas
            } else if (l.startsWith("#extension")) {
                if (!extensiones.contains(l)) {
                    extensiones.add(l);
                }
                resto.append('\n');
            } else {
                resto.append(linea).append('\n');
            }
        }
        return version;
    }

    /** Las líneas alrededor de {@code linea} (1 = primera), para el log de un error de compilación. */
    public static String contexto(String fuente, int linea, int margen) {
        String[] lineas = fuente.split("\n", -1);
        StringBuilder s = new StringBuilder();
        for (int i = Math.max(1, linea - margen); i <= Math.min(lineas.length, linea + margen); i++) {
            s.append(i == linea ? ">" : " ").append(String.format("%5d| ", i)).append(lineas[i - 1]).append('\n');
        }
        return s.toString();
    }
}
