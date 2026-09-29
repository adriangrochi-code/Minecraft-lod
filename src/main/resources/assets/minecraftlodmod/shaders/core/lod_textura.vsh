#version 150

// Terreno LOD texturizado (render/RenderLod). Por vértice:
//   Color = color plano de la cara (tinte de bioma, sombra de cara y luz horneada ya aplicados)
//   UV0   = esquina de la textura en el atlas de bloques
//   UV1   = color promedio de esa textura, empaquetado (R<<8|G, B)
//   UV2   = tamaño de la textura en el atlas × 32768 (0 = cara sin textura)
in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec3 posLocal;
out vec4 vertexColor;
out vec2 uvOrigen;
out vec2 uvTamano;
out vec3 promedio;
out vec3 normal;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    posLocal = Position;
    vertexColor = Color;
    uvOrigen = UV0;
    uvTamano = vec2(UV2) / 32768.0;
    int rg = UV1.x & 0xFFFF;
    int b = UV1.y & 0xFFFF;
    promedio = vec3(float(rg >> 8), float(rg & 0xFF), float(b & 0xFF)) / 255.0;
    normal = Normal;
}
