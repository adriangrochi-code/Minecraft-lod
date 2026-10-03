#version 150

// Vectores de movimiento para los escaladores temporales (render/Escalado,
// misma cuenta que render/MovimientoCamara). Minecraft no los genera: se
// reconstruyen desde la profundidad suponiendo que solo se mueve la cámara.
// Salida: del cuadro actual al anterior, sin jitter, en coordenadas de textura.

uniform sampler2D Sampler0; // profundidad de la escena (copiada antes de la mano)
uniform sampler2D Sampler1; // profundidad final (solo la mano)
uniform vec2 InSize;
uniform mat4 InversaConJitter;
uniform mat4 ActualSinJitter;
uniform mat4 AnteriorSinJitter;
uniform vec3 DeltaCamara;
uniform vec2 EscalaVelocidad; // (1, 1) = coordenadas de textura; tamaño de entrada = píxeles

out vec4 fragColor;

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    if (texelFetch(Sampler1, p, 0).r < 1.0) {
        fragColor = vec4(0.0); // la mano se mueve con la cámara: queda quieta en pantalla
        return;
    }
    vec2 uv = (vec2(p) + 0.5) / InSize;
    float prof = texelFetch(Sampler0, p, 0).r;
    vec4 pos = InversaConJitter * vec4(vec3(uv, prof) * 2.0 - 1.0, 1.0);
    pos /= pos.w;
    vec4 actual = ActualSinJitter * vec4(pos.xyz, 1.0);
    vec4 anterior = AnteriorSinJitter * vec4(pos.xyz + DeltaCamara, 1.0);
    fragColor = vec4((anterior.xy / anterior.w - actual.xy / actual.w) * 0.5 * EscalaVelocidad, 0.0, 1.0);
}
