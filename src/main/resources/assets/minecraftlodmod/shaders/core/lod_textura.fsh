#version 150

// La textura aporta solo DETALLE (textura / su promedio): el color medio de
// la cara no cambia, así el pasto queda teñido por su bioma, los niveles
// reducidos conservan el color medio de lo que representan y cualquier
// paquete de texturas funciona igual (atlas propio del LOD: PaletaTexturas.ATLAS,
// con los modelos que no son cubos horneados). Se repite una vez por bloque (misma
// escala que el terreno vanilla); textureGrad con las derivadas de la
// coordenada sin envolver evita costuras en los bordes de repetición, y los
// mipmaps simplifican la textura sola a medida que se aleja.
uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
// Fundido entre niveles de detalle (render/FundidoNiveles, sección 6 de la arquitectura):
// x = fracción visible (1 = entera), y = 1 la malla que entra, 0 la que sale. Las dos usan
// el mismo tramado Bayer 4×4 complementario: cada píxel lo dibuja exactamente una de ellas.
uniform vec2 Fundido;

const float BAYER[16] = float[16](0.0, 8.0, 2.0, 10.0, 12.0, 4.0, 14.0, 6.0,
                                  3.0, 11.0, 1.0, 9.0, 15.0, 7.0, 13.0, 5.0);

in vec3 posLocal;
in vec4 vertexColor;
in vec2 uvOrigen;
in vec2 uvTamano;
in vec3 promedio;
in vec2 uvOrigenAbajo;
in vec2 uvTamanoAbajo;
in float tamanoVoxel;
in float luzBloque;
flat in int cara;

out vec4 fragColor;

void main() {
    if (Fundido.x < 1.0) {
        ivec2 p = ivec2(gl_FragCoord.xy) & 3;
        float umbral = (BAYER[p.y * 4 + p.x] + 0.5) / 16.0;
        if (Fundido.y > 0.5 ? umbral >= Fundido.x : umbral < 1.0 - Fundido.x) {
            discard;
        }
    }
    vec3 color = vertexColor.rgb;
    if (uvTamano.x > 0.0) {
        int eje = cara / 2; // 0 X, 1 Y, 2 Z
        vec2 repeticion;
        if (eje == 1) {
            repeticion = posLocal.xz;
        } else if (eje == 0) {
            repeticion = vec2(posLocal.z, -posLocal.y);
        } else {
            repeticion = vec2(posLocal.x, -posLocal.y);
        }
        vec2 origen = uvOrigen;
        vec2 tamano = uvTamano;
        if (eje != 1 && uvTamanoAbajo.x > 0.0 && tamanoVoxel > 1.0) {
            // Vóxel grande de pasto, nieve, micelio...: la franja solo en su fila de arriba y
            // la textura de abajo (tierra) en el resto, como el corte de un terreno de verdad.
            float desdeArriba = tamanoVoxel - (posLocal.y - floor(posLocal.y / tamanoVoxel) * tamanoVoxel);
            if (desdeArriba > 1.0) {
                origen = uvOrigenAbajo;
                tamano = uvTamanoAbajo;
            }
        }
        vec2 uv = origen + fract(repeticion) * tamano;
        vec4 tex = textureGrad(Sampler0, uv, dFdx(repeticion) * tamano, dFdy(repeticion) * tamano);
        vec3 detalle = tex.rgb / max(promedio, vec3(1.0 / 255.0));
        // El atlas del LOD es opaco (AtlasLod: huecos rellenos, oscuros en el follaje); el alfa queda por las dudas.
        color *= mix(vec3(1.0), detalle, tex.a);
    }
    // ColorModulator es la luz del cielo a esta hora (de noche oscurece todo); lo que tiene
    // luz de bloque (antorchas, lava) queda con ella, cálida como en el lightmap de vanilla.
    vec3 luz = max(ColorModulator.rgb, vec3(1.0, 0.86, 0.66) * pow(luzBloque / 3.0, 1.5));
    fragColor = vec4(color * luz, ColorModulator.a);
}
