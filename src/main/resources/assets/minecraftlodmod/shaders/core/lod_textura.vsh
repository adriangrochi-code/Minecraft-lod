#version 150

// Terreno LOD texturizado, formato compacto de 12 bytes (render/RenderLod,
// GeometriaLod#escribirCompacto). Por vértice:
//   PosSprite = x, y, z en bloques relativos a la celda + índice de sprite (0 = sin textura)
//   Color     = color plano de la cara (tinte, sombra de cara y luz horneada ya aplicados);
//               el alfa trae la cara: 0 -X, 1 +X, 2 -Y, 3 +Y, 4 -Z, 5 +Z
// El rectángulo del sprite en el atlas y su color promedio salen de la tabla
// (Sampler1, PaletaTexturas.TABLA_SPRITES): 3 texeles RGBA8 por sprite.
in ivec4 PosSprite;
in vec4 Color;

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
// Origen de la celda relativo a la cámara: uno por buffer, como el terreno vanilla.
uniform vec3 ChunkOffset;

out vec3 posLocal;
out vec4 vertexColor;
out vec2 uvOrigen;
out vec2 uvTamano;
out vec3 promedio;
flat out int cara;

const int SPRITES_POR_FILA = 256;

ivec4 texel(int columna, int fila) {
    return ivec4(texelFetch(Sampler1, ivec2(columna, fila), 0) * 255.0 + 0.5);
}

void main() {
    vec3 pos = vec3(PosSprite.xyz);
    gl_Position = ProjMat * ModelViewMat * vec4(pos + ChunkOffset, 1.0);
    posLocal = pos;
    vertexColor = vec4(Color.rgb, 1.0);
    cara = int(Color.a * 255.0 + 0.5);
    int sprite = PosSprite.w & 0xFFFF;
    uvOrigen = vec2(0.0);
    uvTamano = vec2(0.0);
    promedio = vec3(1.0);
    if (sprite != 0) {
        int columna = (sprite % SPRITES_POR_FILA) * 3;
        int fila = sprite / SPRITES_POR_FILA;
        ivec4 origen = texel(columna, fila);
        ivec4 tamano = texel(columna + 1, fila);
        vec2 atlas = vec2(textureSize(Sampler0, 0));
        uvOrigen = vec2(origen.r | (origen.g << 8), origen.b | (origen.a << 8)) / atlas;
        uvTamano = vec2(tamano.r | (tamano.g << 8), tamano.b | (tamano.a << 8)) / atlas;
        promedio = texelFetch(Sampler1, ivec2(columna + 2, fila), 0).rgb;
    }
}
