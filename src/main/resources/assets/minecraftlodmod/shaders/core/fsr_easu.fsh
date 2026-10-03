#version 150

// FSR 1 - EASU (Edge Adaptive Spatial Upsampling), escalado con detección de
// bordes. Reimplementación del algoritmo de AMD FidelityFX Super Resolution 1.0
// (Copyright (c) 2021 Advanced Micro Devices, Inc., licencia MIT:
// https://github.com/GPUOpen-Effects/FidelityFX-FSR). Versión en float, sin
// las optimizaciones de media precisión del original.
//
// Por píxel de salida: 12 muestras de la imagen chica alrededor de su posición,
// dirección y "largo" del borde a partir de la luma, y un filtro tipo Lanczos
// estirado a lo largo del borde; al final se limita a los 4 vecinos más
// cercanos para no generar halos.

uniform sampler2D Sampler0;
uniform vec2 InSize;
uniform vec2 OutSize;

out vec4 fragColor;

vec3 muestra(vec2 p) {
    ivec2 i = clamp(ivec2(p), ivec2(0), ivec2(InSize) - 1);
    return texelFetch(Sampler0, i, 0).rgb;
}

// Luma aproximada del original (x2): B/2 + R/2 + G.
float luma(vec3 c) {
    return c.b * 0.5 + (c.r * 0.5 + c.g);
}

void acumularBorde(inout vec2 dir, inout float largo, float w,
                   float lA, float lB, float lC, float lD, float lE) {
    // Horizontal: B izquierda, C centro, D derecha.
    float dc = lD - lC, cb = lC - lB;
    float largoX = max(abs(dc), abs(cb));
    largoX = largoX > 0.0 ? 1.0 / largoX : 0.0;
    float dirX = lD - lB;
    dir.x += dirX * w;
    largoX = clamp(abs(dirX) * largoX, 0.0, 1.0);
    largo += largoX * largoX * w;
    // Vertical: A arriba, C centro, E abajo.
    float ec = lE - lC, ca = lC - lA;
    float largoY = max(abs(ec), abs(ca));
    largoY = largoY > 0.0 ? 1.0 / largoY : 0.0;
    float dirY = lE - lA;
    dir.y += dirY * w;
    largoY = clamp(abs(dirY) * largoY, 0.0, 1.0);
    largo += largoY * largoY * w;
}

void tap(inout vec3 aC, inout float aW, vec2 off, vec2 dir, vec2 largo2, float lob, float clp, vec3 c) {
    vec2 v = vec2(off.x * dir.x + off.y * dir.y, off.x * (-dir.y) + off.y * dir.x);
    v *= largo2;
    float d2 = min(v.x * v.x + v.y * v.y, clp);
    float wB = 2.0 / 5.0 * d2 - 1.0;
    float wA = lob * d2 - 1.0;
    wB *= wB;
    wA *= wA;
    wB = 25.0 / 16.0 * wB - (25.0 / 16.0 - 1.0);
    float w = wB * wA;
    aC += c * w;
    aW += w;
}

void main() {
    // Centro del píxel de salida en coordenadas de la imagen chica.
    vec2 pp = (gl_FragCoord.xy) * (InSize / OutSize) - 0.5;
    vec2 fp = floor(pp);
    pp -= fp;

    //    b c
    //  e f g h
    //  i j k l
    //    n o
    vec3 b = muestra(fp + vec2(0.5, -0.5)), c = muestra(fp + vec2(1.5, -0.5));
    vec3 e = muestra(fp + vec2(-0.5, 0.5)), f = muestra(fp + vec2(0.5, 0.5));
    vec3 g = muestra(fp + vec2(1.5, 0.5)), h = muestra(fp + vec2(2.5, 0.5));
    vec3 i = muestra(fp + vec2(-0.5, 1.5)), j = muestra(fp + vec2(0.5, 1.5));
    vec3 k = muestra(fp + vec2(1.5, 1.5)), l = muestra(fp + vec2(2.5, 1.5));
    vec3 n = muestra(fp + vec2(0.5, 2.5)), o = muestra(fp + vec2(1.5, 2.5));

    float bL = luma(b), cL = luma(c), eL = luma(e), fL = luma(f), gL = luma(g), hL = luma(h);
    float iL = luma(i), jL = luma(j), kL = luma(k), lL = luma(l), nL = luma(n), oL = luma(o);

    vec2 dir = vec2(0.0);
    float largo = 0.0;
    acumularBorde(dir, largo, (1.0 - pp.x) * (1.0 - pp.y), bL, eL, fL, gL, jL);
    acumularBorde(dir, largo, pp.x * (1.0 - pp.y), cL, fL, gL, hL, kL);
    acumularBorde(dir, largo, (1.0 - pp.x) * pp.y, fL, iL, jL, kL, nL);
    acumularBorde(dir, largo, pp.x * pp.y, gL, jL, kL, lL, oL);

    vec2 dir2 = dir * dir;
    float dirR = dir2.x + dir2.y;
    bool cero = dirR < 1.0 / 32768.0;
    dirR = cero ? 1.0 : inversesqrt(dirR);
    dir.x = cero ? 1.0 : dir.x;
    dir *= dirR;

    largo = largo * 0.5;
    largo *= largo;
    float estiramiento = (dir.x * dir.x + dir.y * dir.y) / max(abs(dir.x), abs(dir.y));
    vec2 largo2 = vec2(1.0 + (estiramiento - 1.0) * largo, 1.0 - 0.5 * largo);
    float lob = 0.5 + ((1.0 / 4.0 - 0.04) - 0.5) * largo;
    float clp = 1.0 / lob;

    vec3 aC = vec3(0.0);
    float aW = 0.0;
    tap(aC, aW, vec2(0.0, -1.0) - pp, dir, largo2, lob, clp, b);
    tap(aC, aW, vec2(1.0, -1.0) - pp, dir, largo2, lob, clp, c);
    tap(aC, aW, vec2(-1.0, 1.0) - pp, dir, largo2, lob, clp, i);
    tap(aC, aW, vec2(0.0, 1.0) - pp, dir, largo2, lob, clp, j);
    tap(aC, aW, vec2(0.0, 0.0) - pp, dir, largo2, lob, clp, f);
    tap(aC, aW, vec2(-1.0, 0.0) - pp, dir, largo2, lob, clp, e);
    tap(aC, aW, vec2(1.0, 1.0) - pp, dir, largo2, lob, clp, k);
    tap(aC, aW, vec2(2.0, 1.0) - pp, dir, largo2, lob, clp, l);
    tap(aC, aW, vec2(2.0, 0.0) - pp, dir, largo2, lob, clp, h);
    tap(aC, aW, vec2(1.0, 0.0) - pp, dir, largo2, lob, clp, g);
    tap(aC, aW, vec2(1.0, 2.0) - pp, dir, largo2, lob, clp, o);
    tap(aC, aW, vec2(0.0, 2.0) - pp, dir, largo2, lob, clp, n);

    vec3 minimo = min(min(f, g), min(j, k));
    vec3 maximo = max(max(f, g), max(j, k));
    vec3 color = aW != 0.0 ? aC / aW : f;
    fragColor = vec4(clamp(color, minimo, maximo), 1.0);
}
