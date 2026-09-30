#version 150

// Cuadrado de pantalla completa para las pasadas de FSR (render/EscaladoFsr):
// las posiciones ya vienen en coordenadas de recorte (-1..1).
in vec3 Position;

void main() {
    gl_Position = vec4(Position.xy, 0.0, 1.0);
}
