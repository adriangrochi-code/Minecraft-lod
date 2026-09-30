#version 150

// Variante de lod_textura.fsh para VulkanMod (reglas en lod_textura_vk.vsh).
// Sampler0 no se declara acá: lo agrega el conversor.
uniform vec4 ColorModulator;

in vec3 posLocal;
in vec4 vertexColor;
in vec2 uvOrigen;
in vec2 uvTamano;
in vec3 promedio;
in float cara;

out vec4 fragColor;

void main() {
    vec3 color = vertexColor.rgb;
    if (uvTamano.x > 0.0) {
        int eje = int(cara + 0.5) / 2;
        vec2 repeticion;
        if (eje == 1) {
            repeticion = posLocal.xz;
        } else if (eje == 0) {
            repeticion = vec2(posLocal.z, -posLocal.y);
        } else {
            repeticion = vec2(posLocal.x, -posLocal.y);
        }
        vec2 uv = uvOrigen + fract(repeticion) * uvTamano;
        vec4 tex = textureGrad(Sampler0, uv, dFdx(repeticion) * uvTamano, dFdy(repeticion) * uvTamano);
        vec3 detalle = tex.rgb / max(promedio, vec3(1.0 / 255.0));
        color *= mix(vec3(1.0), detalle, tex.a);
    }
    fragColor = vec4(color, 1.0) * ColorModulator;
}
