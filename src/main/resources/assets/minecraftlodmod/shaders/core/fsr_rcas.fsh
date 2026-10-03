#version 150

// FSR 1 - RCAS (Robust Contrast Adaptive Sharpening), nitidez después de EASU.
// Reimplementación del algoritmo de AMD FidelityFX Super Resolution 1.0
// (Copyright (c) 2021 Advanced Micro Devices, Inc., licencia MIT:
// https://github.com/GPUOpen-Effects/FidelityFX-FSR).
//
// Cruz de 5 muestras; el "lóbulo" negativo se limita para no saturar (no
// generar halos) usando el mínimo y el máximo locales.
//    b
//  d e f
//    h

uniform sampler2D Sampler0;
uniform vec2 OutSize;
// 0 = nitidez máxima; cada unidad es un "stop" menos (la intensidad se divide por 2).
uniform float Nitidez;

out vec4 fragColor;

const float LIMITE = 0.25 - (1.0 / 16.0);

vec3 muestra(ivec2 p) {
    return texelFetch(Sampler0, clamp(p, ivec2(0), ivec2(OutSize) - 1), 0).rgb;
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec3 b = muestra(p + ivec2(0, 1));
    vec3 d = muestra(p + ivec2(-1, 0));
    vec3 e = muestra(p);
    vec3 f = muestra(p + ivec2(1, 0));
    vec3 h = muestra(p + ivec2(0, -1));

    vec3 mn4 = min(min(b, d), min(f, h));
    vec3 mx4 = max(max(b, d), max(f, h));
    vec2 pico = vec2(1.0, -4.0);
    vec3 golpeMin = min(mn4, e) / (4.0 * mx4 + 1e-5);
    vec3 golpeMax = (pico.x - max(mx4, e)) / (4.0 * mn4 + pico.y);
    vec3 lobuloRGB = max(-golpeMin, golpeMax);
    float lobulo = max(-LIMITE, min(max(lobuloRGB.r, max(lobuloRGB.g, lobuloRGB.b)), 0.0)) * exp2(-Nitidez);

    vec3 color = (lobulo * (b + d + f + h) + e) / (4.0 * lobulo + 1.0);
    fragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
