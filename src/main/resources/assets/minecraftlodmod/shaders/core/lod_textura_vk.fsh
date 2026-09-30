#version 150

// Variante de lod_textura.fsh para VulkanMod (reglas en lod_textura_vk.vsh).
// Sampler0 no se declara acá: lo agrega el conversor.
uniform vec4 ColorModulator;

in vec3 posLocal;
in vec4 vertexColor;
in vec2 uvOrigen;
in vec2 uvTamano;
in vec3 promedio;
in vec2 uvOrigenAbajo;
in vec2 uvTamanoAbajo;
in float tamanoVoxel;
in float cara;
in float luzBloque;

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
        vec2 origen = uvOrigen;
        vec2 tamano = uvTamano;
        if (eje != 1 && uvTamanoAbajo.x > 0.0 && tamanoVoxel > 1.0) {
            // Vóxel grande de pasto, nieve, micelio...: la franja solo en su fila de arriba y
            // la textura de abajo (tierra) en el resto, como el corte de un terreno de verdad.
            float desdeArriba = tamanoVoxel - (posLocal.y - floor(posLocal.y / tamanoVoxel) * tamanoVoxel);
            if (desdeArriba > 1.0) {
                origen = uvOrigenAbajo;
                tamano = uvTamanoAbajo;
            }
        }
        vec2 uv = origen + fract(repeticion) * tamano;
        vec4 tex = textureGrad(Sampler0, uv, dFdx(repeticion) * tamano, dFdy(repeticion) * tamano);
        vec3 detalle = tex.rgb / max(promedio, vec3(1.0 / 255.0));
        color *= mix(vec3(1.0), detalle, tex.a);
    }
    vec3 luz = max(ColorModulator.rgb, vec3(1.0, 0.86, 0.66) * pow(luzBloque / 3.0, 1.5));
    fragColor = vec4(color * luz, ColorModulator.a);
}
