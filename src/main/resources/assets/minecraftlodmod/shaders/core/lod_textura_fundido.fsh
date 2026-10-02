#version 150

// Terreno LOD texturizado de las mallas en fundido entre niveles (render/FundidoNiveles,
// sección 6 de la arquitectura). Programa aparte porque un shader con discard apaga la
// prueba de profundidad temprana en la GPU: el resto del LOD usa lod_textura, sin él.
#moj_import <minecraftlodmod:lod_textura_color.glsl>

// x = fracción visible (1 = entera), y = 1 la malla que entra, 0 la que sale. Las dos usan
// el mismo tramado Bayer 4×4 complementario: cada píxel lo dibuja exactamente una de ellas.
uniform vec2 Fundido;

const float BAYER[16] = float[16](0.0, 8.0, 2.0, 10.0, 12.0, 4.0, 14.0, 6.0,
                                  3.0, 11.0, 1.0, 9.0, 15.0, 7.0, 13.0, 5.0);

out vec4 fragColor;

void main() {
    if (Fundido.x < 1.0) {
        ivec2 p = ivec2(gl_FragCoord.xy) & 3;
        float umbral = (BAYER[p.y * 4 + p.x] + 0.5) / 16.0;
        if (Fundido.y > 0.5 ? umbral >= Fundido.x : umbral < 1.0 - Fundido.x) {
            discard;
        }
    }
    fragColor = colorLod();
}
