#version 150

// La textura aporta solo DETALLE (textura / su promedio): el color medio de
// la cara no cambia, así el pasto queda teñido por su bioma, los niveles
// reducidos conservan el color medio de lo que representan y cualquier
// paquete de texturas funciona igual. Se repite una vez por bloque (misma
// escala que el terreno vanilla); textureGrad con las derivadas de la
// coordenada sin envolver evita costuras en los bordes de repetición, y los
// mipmaps simplifican la textura sola a medida que se aleja.
uniform sampler2D Sampler0;
uniform vec4 ColorModulator;

in vec3 posLocal;
in vec4 vertexColor;
in vec2 uvOrigen;
in vec2 uvTamano;
in vec3 promedio;
in vec3 normal;

out vec4 fragColor;

void main() {
    vec3 color = vertexColor.rgb;
    if (uvTamano.x > 0.0) {
        vec3 n = abs(normal);
        vec2 repeticion;
        if (n.y > 0.5) {
            repeticion = posLocal.xz;
        } else if (n.x > 0.5) {
            repeticion = vec2(posLocal.z, -posLocal.y);
        } else {
            repeticion = vec2(posLocal.x, -posLocal.y);
        }
        vec2 uv = uvOrigen + fract(repeticion) * uvTamano;
        vec4 tex = textureGrad(Sampler0, uv, dFdx(repeticion) * uvTamano, dFdy(repeticion) * uvTamano);
        vec3 detalle = tex.rgb / max(promedio, vec3(1.0 / 255.0));
        // En los huecos transparentes (hojas) queda el color plano: nada de agujeros negros.
        color *= mix(vec3(1.0), detalle, tex.a);
    }
    fragColor = vec4(color, 1.0) * ColorModulator;
}
