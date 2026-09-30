#version 150

// Variante de lod_textura para VulkanMod, que convierte este GLSL a Vulkan
// con un conversor línea por línea (net.vulkanmod...GlslConverter). Reglas:
//  - in, out y uniform: una declaración por línea, sin calificadores (nada de flat).
//  - Los samplers se declaran solo acá: el conversor los numera en orden de
//    aparición entre las dos etapas y se los agrega a ambas.
//  - Sin el operador de resto (lo cambia por mod(), que es de floats).
//  - Sin ChunkOffset: VertexBuffer.draw() de VulkanMod no vuelve a subir
//    uniforms, así que cada celda va con drawWithShader y su matriz corrida.
// Los 4 shorts de posición y sprite llegan como dos R16G16_SINT (VulkanMod no
// respeta la cantidad de componentes, ver RenderLod.FORMATO_TEXTURA). El
// resto, igual que lod_textura.vsh.
in ivec2 PosXY;
in ivec2 PosZSprite;
in vec4 Color;

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec3 posLocal;
out vec4 vertexColor;
out vec2 uvOrigen;
out vec2 uvTamano;
out vec3 promedio;
out vec2 uvOrigenAbajo;
out vec2 uvTamanoAbajo;
out float tamanoVoxel;
out float cara;

const int SPRITES_POR_FILA = 256;

ivec4 texel(int columna, int fila) {
    return ivec4(texelFetch(Sampler1, ivec2(columna, fila), 0) * 255.0 + 0.5);
}

void main() {
    vec3 pos = vec3(PosXY, PosZSprite.x);
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);
    posLocal = pos;
    vertexColor = vec4(Color.rgb, 1.0);
    float alfa = floor(Color.a * 255.0 + 0.5);
    float nivel = floor(alfa / 8.0);
    cara = alfa - nivel * 8.0;
    tamanoVoxel = exp2(nivel);
    int sprite = PosZSprite.y & 0xFFFF;
    uvOrigen = vec2(0.0);
    uvTamano = vec2(0.0);
    uvOrigenAbajo = vec2(0.0);
    uvTamanoAbajo = vec2(0.0);
    promedio = vec3(1.0);
    if (sprite != 0) {
        int columna = (sprite & (SPRITES_POR_FILA - 1)) * 4;
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
            int columnaAbajo = (spriteAbajo & (SPRITES_POR_FILA - 1)) * 4;
            int filaAbajo = spriteAbajo / SPRITES_POR_FILA;
            ivec4 origenAbajo = texel(columnaAbajo, filaAbajo);
            ivec4 tamanoAbajo = texel(columnaAbajo + 1, filaAbajo);
            uvOrigenAbajo = vec2(origenAbajo.r | (origenAbajo.g << 8), origenAbajo.b | (origenAbajo.a << 8)) / atlas;
            uvTamanoAbajo = vec2(tamanoAbajo.r | (tamanoAbajo.g << 8), tamanoAbajo.b | (tamanoAbajo.a << 8)) / atlas;
        }
    }
}
