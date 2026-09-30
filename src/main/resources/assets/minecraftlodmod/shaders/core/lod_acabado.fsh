#version 150

// Acabado del LOD (render/AcabadoLod), sobre la imagen ya dibujada y antes de que
// vanilla dibuje encima su terreno cercano:
//  - oclusión ambiental de lod_ssao, promediada en 4×4 sin cruzar bordes de profundidad;
//  - neblina atmosférica (curva exp2 de AtmosphericPerspective): desde donde termina
//    vanilla hasta el alcance del LOD, lo lejano se acerca al color del cielo;
//  - niebla del borde, la misma que vanilla corre hasta el alcance del LOD: el LOD
//    termina dentro de la niebla y no contra el cielo.
// Mezcla ONE, SRC_ALPHA: resultado = niebla * f + fondo * ao * (1 - f). En el cielo
// (sin LOD) sale (0, 0, 0, 1) y el fondo queda igual.
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform vec2 InSize;
uniform mat4 ProjInv;
uniform vec3 ColorNiebla;
uniform vec2 NieblaBorde;
uniform vec3 Neblina;
uniform float ConOclusion;

out vec4 fragColor;

vec3 posVista(vec2 uv) {
    float z = texture(Sampler0, uv).r;
    vec4 p = ProjInv * vec4(vec3(uv, z) * 2.0 - 1.0, 1.0);
    return p.xyz / p.w;
}

void main() {
    vec2 uv = gl_FragCoord.xy / InSize;
    if (texture(Sampler0, uv).r >= 1.0) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    vec3 p = posVista(uv);
    float ao = 1.0;
    if (ConOclusion > 0.5) {
        float suma = 0.0;
        float peso = 0.0;
        float tolerancia = 0.5 + 0.03 * -p.z;
        for (int x = -2; x < 2; x++) {
            for (int y = -2; y < 2; y++) {
                vec2 o = uv + vec2(float(x), float(y)) / InSize;
                float w = exp(-abs(posVista(o).z - p.z) / tolerancia);
                suma += texture(Sampler1, o).r * w;
                peso += w;
            }
        }
        ao = suma / max(peso, 1e-4);
    }
    float d = length(p);
    float borde = clamp((d - NieblaBorde.x) / max(1.0, NieblaBorde.y - NieblaBorde.x), 0.0, 1.0);
    float t = clamp((d - Neblina.x) / max(1.0, Neblina.y - Neblina.x), 0.0, 1.0);
    float neblina = (1.0 - exp(-4.0 * t * t)) * Neblina.z;
    float f = max(borde, neblina);
    fragColor = vec4(ColorNiebla * f, ao * (1.0 - f));
}
