#version 150

// Nubes lejanas del LOD (render/NubesLejanas): una grilla plana a la altura
// de las nubes, centrada en la cámara (posiciones relativas a ella, en bloques).
in vec3 Position;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
// Igual que lod_textura: x = 1 / 2R, y = distancia desde la que baja. x = 0: plano.
uniform vec2 Curvatura;

out vec2 posXZ;

void main() {
    vec3 p = Position;
    float lejos = max(0.0, length(p.xz) - Curvatura.y);
    p.y -= Curvatura.x * lejos * lejos;
    posXZ = Position.xz;
    gl_Position = ProjMat * ModelViewMat * vec4(p, 1.0);
}
