#version 150

// Oclusión ambiental en pantalla (SSAO) del LOD, primera pasada (render/AcabadoLod).
// Idea de Voxy (sección 25 del documento de arquitectura), sin su código: con la
// profundidad del LOD se reconstruye la posición y la normal de cada píxel y se
// mira si el relieve de alrededor tapa el hemisferio de arriba. El radio crece
// con la distancia, como los vóxeles: de lejos oscurece valles y pies de montaña,
// de cerca rincones y bajo los árboles. 8 muestras, giradas con un patrón de 4×4
// que la segunda pasada (lod_acabado) promedia.
uniform sampler2D Sampler0;
uniform vec2 InSize;
uniform mat4 ProjMat;
uniform mat4 ProjInv;
uniform float Fuerza;

out vec4 fragColor;

const int MUESTRAS = 8;

vec3 posVista(vec2 uv) {
    float z = texture(Sampler0, uv).r;
    vec4 p = ProjInv * vec4(vec3(uv, z) * 2.0 - 1.0, 1.0);
    return p.xyz / p.w;
}

void main() {
    vec2 uv = gl_FragCoord.xy / InSize;
    if (texture(Sampler0, uv).r >= 1.0) {
        fragColor = vec4(1.0);
        return;
    }
    vec2 px = 1.0 / InSize;
    vec3 p = posVista(uv);
    vec3 derecha = posVista(uv + vec2(px.x, 0.0)) - p;
    vec3 izquierda = p - posVista(uv - vec2(px.x, 0.0));
    vec3 arriba = posVista(uv + vec2(0.0, px.y)) - p;
    vec3 abajo = p - posVista(uv - vec2(0.0, px.y));
    // La diferencia más chica: en un borde la otra cruza a otra superficie.
    vec3 dx = abs(derecha.z) < abs(izquierda.z) ? derecha : izquierda;
    vec3 dy = abs(arriba.z) < abs(abajo.z) ? arriba : abajo;
    vec3 n = normalize(cross(dx, dy));
    if (dot(n, p) > 0.0) {
        n = -n;
    }
    float distancia = length(p);
    float radio = max(1.5, distancia * 0.025);

    int indice = (int(gl_FragCoord.x) & 3) * 4 + (int(gl_FragCoord.y) & 3);
    float giro = float(indice) * (6.2831853 / 16.0);
    vec3 t = normalize(cross(n, abs(n.y) < 0.99 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0)));
    t = t * cos(giro) + cross(n, t) * sin(giro);
    vec3 b = cross(n, t);

    float tapado = 0.0;
    for (int i = 0; i < MUESTRAS; i++) {
        float f = (float(i) + 0.5) / float(MUESTRAS);
        float angulo = float(i) * 2.3999632; // ángulo áureo
        float r = sqrt(f);
        vec3 dir = t * (cos(angulo) * r) + b * (sin(angulo) * r) + n * sqrt(max(0.0, 1.0 - r * r));
        vec3 s = p + dir * (radio * mix(0.25, 1.0, f));
        vec4 c = ProjMat * vec4(s, 1.0);
        vec2 suv = c.xy / c.w * 0.5 + 0.5;
        if (suv.x < 0.0 || suv.y < 0.0 || suv.x > 1.0 || suv.y > 1.0 || texture(Sampler0, suv).r >= 1.0) {
            continue;
        }
        vec3 q = posVista(suv);
        // Tapa si lo que se ve ahí está delante del punto de muestra; lo que está
        // mucho más cerca (otra montaña delante) no cuenta.
        float delante = q.z - s.z;
        if (delante > radio * 0.05) {
            tapado += clamp(radio * 2.0 / abs(q.z - p.z), 0.0, 1.0);
        }
    }
    float ao = 1.0 - Fuerza * tapado / float(MUESTRAS);
    fragColor = vec4(vec3(ao), 1.0);
}
