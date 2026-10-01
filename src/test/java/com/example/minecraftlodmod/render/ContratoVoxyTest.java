package com.example.minecraftlodmod.render;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContratoVoxyTest {

    // Como queda un voxy.json de Complementary después del preprocesado de Iris:
    // claves sin comillas, cadenas de varias líneas con código y comentarios.
    private static final String JSON = """
            {
                "unusedString": "
                    //Common//
                    float algo(float x) { return x * 2.0; } // iris returns "nan" if there are no clouds
                    /* otro "comentario"
                       de dos líneas */
                    #define LARGO(a) \\
                        (a + 1.0)
                ",
                "version": 1,
                "excludeLodsFromVanillaDepth": true,
                "opaqueDrawBuffers": [ 0, 6 ],
                "translucentDrawBuffers": [ 0 ],
                "blending": {
                    -1: "off",
                    0: "SRC_ALPHA ONE_MINUS_SRC_ALPHA ONE ONE_MINUS_SRC_ALPHA"
                },
                "taaOffset": "{
                    return vec2(0.5) / vec2(viewWidth, viewHeight);
                }",
                "uniforms": [
                    "viewWidth", "viewHeight",
                    "noExiste",
                    "vxModelView", "vxProj"
                ],
                "samplers": {
                    "gaux4": "sampler2D",
                    "shadowtex0": "sampler2DShadow"
                }
            }
            """;

    @Test
    void leeElJsonLaxoDeUnPack() {
        ContratoVoxy.Programa p = ContratoVoxy.leer(JSON);
        assertEquals(1, p.version());
        assertTrue(p.excluirDeProfundidadVanilla());
        assertEquals(List.of(0, 6), p.buffersOpacos());
        assertEquals(List.of(0), p.buffersTranslucidos());
        assertEquals("off", p.mezcla().get(-1));
        assertTrue(p.taaOffset().startsWith("{") && p.taaOffset().endsWith("}"));
        assertEquals(List.of("viewWidth", "viewHeight", "noExiste", "vxModelView", "vxProj"), p.uniforms());
        assertEquals("sampler2DShadow", p.samplers().get("shadowtex0"));
    }

    @Test
    void barrasInvertidasDelCodigoIncluidoQuedanLiterales() {
        assertEquals("\"a\\\\xb\\n\"", ContratoVoxy.sanearEscapes("\"a\\xb\\n\""));
    }

    @Test
    void sinTipoSoloLosQueNadieConoce() {
        ContratoVoxy.Programa p = ContratoVoxy.leer(JSON);
        Map<String, String> iris = Map.of("viewWidth", "float", "viewHeight", "float");
        assertEquals(List.of("noExiste"), ContratoVoxy.sinTipo(p, iris));
    }

    @Test
    void tiposDeLasDeclaracionesDelPack() {
        Map<String, String> d = ContratoVoxy.declaracionesUniform("""
                uniform bool heavyFog = false;
                uniform  vec3 previousCameraPositionFract;
                uniform highp float inNetherWastes;
                uniform sampler2D colortex0;
                float noEsUniform;
                """);
        assertEquals(Map.of("heavyFog", "bool", "previousCameraPositionFract", "vec3", "inNetherWastes", "float"), d);
    }

    @Test
    void carasAlOrdenDeVoxy() {
        // LOD: -X +X -Y +Y -Z +Z. Voxy: eje (0 Y, 1 Z, 2 X) * 2 + positivo.
        int[] esperado = {4, 5, 0, 1, 2, 3};
        for (int c = 0; c < 6; c++) {
            assertEquals(esperado[c], ContratoVoxy.caraVoxy(c));
        }
    }

    @Test
    void directivasArribaYNumeracionIntacta() {
        String fuente = "#version 450 compatibility\n#extension GL_ARB_x : enable\nfloat a;\nfloat b;\n";
        List<String> ext = new ArrayList<>();
        StringBuilder resto = new StringBuilder();
        assertEquals(450, ContratoVoxy.separarDirectivas(fuente, ext, resto));
        assertEquals(List.of("#extension GL_ARB_x : enable"), ext);
        String[] lineas = resto.toString().split("\n", -1);
        assertEquals("float a;", lineas[2]);
        assertEquals(ContratoVoxy.VERSION_GLSL,
                ContratoVoxy.separarDirectivas("float a;\n", new ArrayList<>(), new StringBuilder()));
    }

    @Test
    void fragmentoDeclaraYLlamaAlPack() {
        ContratoVoxy.Programa p = ContratoVoxy.leer(JSON);
        Map<String, String> iris = Map.of("viewWidth", "float", "viewHeight", "float", "vxProj", "float");
        String f = ContratoVoxy.fragmento(p, iris, 430, List.of(), "void voxy_emitFragment(VoxyFragmentParameters p) {}\n");
        assertTrue(f.startsWith("#version 430 core\n"));
        assertTrue(f.contains("uniform float viewWidth;"));
        // Los vx* son del mod: su tipo manda aunque Iris diga otra cosa, y se declaran una vez.
        assertTrue(f.contains("uniform mat4 vxProj;"));
        assertEquals(f.indexOf("uniform mat4 vxProj;"), f.lastIndexOf("uniform mat4 vxProj;"));
        assertTrue(f.contains("uniform sampler2DShadow shadowtex0;"));
        assertTrue(!f.contains("noExiste"));
        assertTrue(f.indexOf("struct VoxyFragmentParameters") < f.indexOf("#line 1\n"));
        assertTrue(f.indexOf("#line 1\n") < f.indexOf("voxy_emitFragment(p);"));
        String v = ContratoVoxy.vertice(p, iris, 430, List.of());
        assertTrue(v.contains("vec2 lodvx_taaOffset() {"));
    }

    @Test
    void sinJitterDevuelveCero() {
        ContratoVoxy.Programa p = ContratoVoxy.leer("{ \"uniforms\": [] }");
        assertNull(p.taaOffset());
        assertTrue(ContratoVoxy.vertice(p, Map.of(), 430, List.of()).contains("{ return vec2(0.0); }"));
    }
}
