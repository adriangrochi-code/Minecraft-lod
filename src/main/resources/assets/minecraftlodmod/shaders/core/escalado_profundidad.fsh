#version 150

// Copia la profundidad de la escena a una textura de color R32F (la entrada de
// profundidad de XeSS/DLSS, que vive en memoria compartida con Vulkan).

uniform sampler2D Sampler0;

out vec4 fragColor;

void main() {
    fragColor = vec4(texelFetch(Sampler0, ivec2(gl_FragCoord.xy), 0).r);
}
