#version 150

// La textura de nubes de vanilla (un texel = 12 bloques) con el mismo
// desplazamiento que las nubes de vanilla, así el dibujo sigue igual donde
// ellas terminan. Adentro del rectángulo que dibuja vanilla no se pinta nada.
// Lejos, cada píxel cubre muchos texeles y la textura (sin mipmaps) titilaría:
// se promedian 4 muestras dentro del píxel y, cuando ni eso alcanza, se pasa a
// la cobertura media (un velo parejo, como se ven las nubes a lo lejos).
uniform sampler2D Sampler0;
uniform vec2 Desplazamiento;
uniform vec4 Hueco;
uniform float RadioHueco;
uniform vec4 ColorNube;
uniform vec3 ColorNiebla;
uniform vec2 Desvanecer;
uniform float Cobertura;

in vec2 posXZ;

out vec4 fragColor;

void main() {
    if (posXZ.x > Hueco.x && posXZ.x < Hueco.z && posXZ.y > Hueco.y && posXZ.y < Hueco.w
            && length(posXZ) < RadioHueco) {
        discard;
    }
    vec2 uv = (posXZ / 12.0 + Desplazamiento) / 256.0;
    vec2 dx = dFdx(uv) * 0.25;
    vec2 dy = dFdy(uv) * 0.25;
    float alfa = 0.25 * (texture(Sampler0, uv + dx + dy).a + texture(Sampler0, uv + dx - dy).a
            + texture(Sampler0, uv - dx + dy).a + texture(Sampler0, uv - dx - dy).a);
    float texelesPorPixel = max(length(dFdx(uv)), length(dFdy(uv))) * 256.0;
    alfa = mix(alfa, Cobertura, clamp(texelesPorPixel - 1.0, 0.0, 1.0));
    float distancia = length(posXZ);
    float niebla = smoothstep(Desvanecer.x, Desvanecer.y, distancia);
    alfa *= ColorNube.a * (1.0 - niebla * 0.6);
    if (alfa < 0.02) {
        discard;
    }
    fragColor = vec4(mix(ColorNube.rgb, ColorNiebla, niebla), alfa);
}
