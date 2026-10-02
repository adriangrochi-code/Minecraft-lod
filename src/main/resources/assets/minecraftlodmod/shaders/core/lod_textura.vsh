#version 150

// Terreno LOD texturizado, formato compacto de 12 bytes (render/RenderLod,
// GeometriaLod#escribirCompacto). Por vértice:
//   PosSprite = x, y, z en bloques relativos a la celda + índice de sprite (bits 0-13,
//               0 = sin textura) y luz de bloque cuantizada 0-3 (bits 14-15)
//   Color     = color plano de la cara (tinte, sombra de cara y luz horneada ya aplicados);
//               el alfa trae la cara (bits 0-2: 0 -X, 1 +X, 2 -Y, 3 +Y, 4 -Z, 5 +Z) y el
//               tamaño del vóxel (bits 3-7: log2 de los bloques por lado)
// El rectángulo del sprite en el atlas, su color promedio y el sprite "de
// abajo" (tierra bajo la franja de pasto) salen de la tabla (Sampler1,
// PaletaTexturas.TABLA_SPRITES): 4 texeles RGBA8 por sprite.
in ivec4 PosSprite;
in vec4 Color;

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
// Origen de la celda relativo a la cámara: uno por buffer, como el terreno vanilla.
uniform vec3 ChunkOffset;
// Curvatura del horizonte (core/HorizonteCurvo): x = 1 / 2R, y = distancia desde la que baja. x = 0: plano.
uniform vec2 Curvatura;
uniform vec4 ColorModulator;

out vec3 posLocal;
out vec4 vertexColor;
// Lo que es igual en los 4 vértices de la cara va flat: no se interpola por píxel.
flat out vec2 uvOrigen;
flat out vec2 uvTamano;
flat out vec3 promedio;
flat out vec2 uvOrigenAbajo;
flat out vec2 uvTamanoAbajo;
flat out float tamanoVoxel;
flat out vec3 luz;
flat out int cara;

const int SPRITES_POR_FILA = 256;

ivec4 texel(int columna, int fila) {
    return ivec4(texelFetch(Sampler1, ivec2(columna, fila), 0) * 255.0 + 0.5);
}

void main() {
    vec3 pos = vec3(PosSprite.xyz);
    vec3 relativa = pos + ChunkOffset;
    float lejos = max(0.0, length(relativa.xz) - Curvatura.y);
    relativa.y -= Curvatura.x * lejos * lejos;
    gl_Position = ProjMat * ModelViewMat * vec4(relativa, 1.0);
    posLocal = pos;
    vertexColor = vec4(Color.rgb, 1.0);
    int alfa = int(Color.a * 255.0 + 0.5);
    cara = alfa & 7;
    tamanoVoxel = float(1 << (alfa >> 3));
    int sprite = PosSprite.w & 0x3FFF;
    float luzBloque = float((PosSprite.w >> 14) & 3);
    // ColorModulator es la luz del cielo a esta hora (de noche oscurece todo); lo que tiene
    // luz de bloque (antorchas, lava) queda con ella, cálida como en el lightmap de vanilla.
    luz = max(ColorModulator.rgb, vec3(1.0, 0.86, 0.66) * pow(luzBloque / 3.0, 1.5));
    uvOrigen = vec2(0.0);
    uvTamano = vec2(0.0);
    uvOrigenAbajo = vec2(0.0);
    uvTamanoAbajo = vec2(0.0);
    promedio = vec3(1.0);
    if (sprite != 0) {
        int columna = (sprite % SPRITES_POR_FILA) * 4;
        int fila = sprite / SPRITES_POR_FILA;
        ivec4 origen = texel(columna, fila);
        ivec4 tamano = texel(columna + 1, fila);
        vec2 atlas = vec2(textureSize(Sampler0, 0));
        uvOrigen = vec2(origen.r | (origen.g << 8), origen.b | (origen.a << 8)) / atlas;
        uvTamano = vec2(tamano.r | (tamano.g << 8), tamano.b | (tamano.a << 8)) / atlas;
        promedio = texelFetch(Sampler1, ivec2(columna + 2, fila), 0).rgb;
        ivec4 abajo = texel(columna + 3, fila);
        int spriteAbajo = abajo.r | (abajo.g << 8);
        if (spriteAbajo != 0) {
            int columnaAbajo = (spriteAbajo % SPRITES_POR_FILA) * 4;
            int filaAbajo = spriteAbajo / SPRITES_POR_FILA;
            ivec4 origenAbajo = texel(columnaAbajo, filaAbajo);
            ivec4 tamanoAbajo = texel(columnaAbajo + 1, filaAbajo);
            uvOrigenAbajo = vec2(origenAbajo.r | (origenAbajo.g << 8), origenAbajo.b | (origenAbajo.a << 8)) / atlas;
            uvTamanoAbajo = vec2(tamanoAbajo.r | (tamanoAbajo.g << 8), tamanoAbajo.b | (tamanoAbajo.a << 8)) / atlas;
        }
    }
}
