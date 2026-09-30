#version 150

// Escalador temporal propio (render/Escalado, modo TEMPORAL): cada cuadro se
// dibuja a menor resolución con un desplazamiento sub-píxel distinto, y acá
// se junta con el historial reproyectado (vectores de movimiento) para
// reconstruir la resolución de salida. El historial se limita a la
// variación de colores del vecindario actual (en YCoCg) para no dejar
// estelas cuando algo aparece o desaparece.

uniform sampler2D Sampler0; // color actual (entrada, filtro lineal)
uniform sampler2D Sampler1; // vectores de movimiento (entrada)
uniform sampler2D Sampler2; // historial (salida, filtro lineal)
uniform sampler2D Sampler3; // profundidad de la escena (entrada)
uniform vec2 InSize;
uniform vec2 OutSize;
uniform vec2 Jitter;       // desplazamiento de este cuadro, en píxeles de entrada
uniform float Reiniciar;   // 1 = sin historial válido

out vec4 fragColor;

vec3 aYCoCg(vec3 c) {
    return vec3(0.25 * c.r + 0.5 * c.g + 0.25 * c.b, 0.5 * c.r - 0.5 * c.b, -0.25 * c.r + 0.5 * c.g - 0.25 * c.b);
}

vec3 deYCoCg(vec3 c) {
    float t = c.x - c.z;
    return vec3(t + c.y, c.x + c.z, t - c.y);
}

void main() {
    vec2 uv = gl_FragCoord.xy / OutSize;
    // Dónde cae este punto (sin jitter) en la imagen de entrada, que está corrida Jitter píxeles.
    vec2 pEntrada = uv * InSize + Jitter;
    ivec2 centro = ivec2(floor(pEntrada));
    ivec2 limite = ivec2(InSize) - 1;

    vec3 m1 = vec3(0.0), m2 = vec3(0.0);
    float profMin = 2.0;
    ivec2 masCercano = clamp(centro, ivec2(0), limite);
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            ivec2 q = clamp(centro + ivec2(x, y), ivec2(0), limite);
            vec3 c = aYCoCg(texelFetch(Sampler0, q, 0).rgb);
            m1 += c;
            m2 += c * c;
            // La velocidad del vecino más cercano a la cámara: los bordes de lo que está
            // adelante arrastran su propio movimiento, no el del fondo.
            float d = texelFetch(Sampler3, q, 0).r;
            if (d < profMin) {
                profMin = d;
                masCercano = q;
            }
        }
    }
    vec3 media = m1 / 9.0;
    vec3 desvio = sqrt(max(m2 / 9.0 - media * media, 0.0));
    vec3 minimo = media - 1.25 * desvio;
    vec3 maximo = media + 1.25 * desvio;

    vec3 actual = texture(Sampler0, pEntrada / InSize).rgb;
    vec2 velocidad = texelFetch(Sampler1, masCercano, 0).xy;
    vec2 uvAnterior = uv + velocidad;
    bool fuera = any(lessThan(uvAnterior, vec2(0.0))) || any(greaterThan(uvAnterior, vec2(1.0)));
    vec3 historial = deYCoCg(clamp(aYCoCg(texture(Sampler2, uvAnterior).rgb), minimo, maximo));

    // Cuánto aporta la muestra nueva: más cerca del centro de un píxel de entrada, más.
    vec2 d = fract(pEntrada) - 0.5;
    float confianza = exp(-4.0 * dot(d, d));
    float alfa = (Reiniciar > 0.5 || fuera) ? 1.0 : mix(0.04, 0.2, confianza);
    fragColor = vec4(max(mix(historial, actual, alfa), 0.0), 1.0);
}
