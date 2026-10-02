#version 150

// Terreno LOD texturizado, sin discard (ver include/lod_textura_color.glsl).
#moj_import <minecraftlodmod:lod_textura_color.glsl>

out vec4 fragColor;

void main() {
    fragColor = colorLod();
}
