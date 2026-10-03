#version 150

// Siluetas de plantas en cruz del LOD (GeometriaLod#agregarCruces): lo de lod_textura más
// el recorte por el alfa de la tesela. Programa aparte porque el discard apaga la prueba de
// profundidad temprana: solo lo usan los planos de las cruces, en su propia pasada.
#moj_import <minecraftlodmod:lod_textura_color.glsl>

out vec4 fragColor;

void main() {
    fragColor = colorLod();
    if (alfaTextura < 0.5) {
        discard;
    }
}
