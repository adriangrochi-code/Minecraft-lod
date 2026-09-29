package com.example.minecraftlodmod.render;

/**
 * TODO (hito 2 del documento de arquitectura): implementar el hook de render.
 *
 * Pendiente de completar con las clases reales de NeoForge (no verificadas
 * en este entorno, requieren compilar contra las dependencias reales):
 *
 * 1. Registrar un listener de {@code net.neoforged.neoforge.client.event.RenderLevelStageEvent}
 *    en la etapa AFTER_SOLID_BLOCKS (o la equivalente en la versión exacta de NeoForge usada).
 *
 * 2. Por cada región visible (según el resultado de
 *    {@link com.example.minecraftlodmod.core.LodSelector#seleccionarNodosVisibles}),
 *    dibujar un buffer de GPU agrupado por región (no por nodo individual —
 *    ver sección 6: minimizar draw calls es crítico en iGPU).
 *
 * 3. Los quads generados por
 *    {@link com.example.minecraftlodmod.generation.GreedyMesher#mallar}
 *    se traducen a vértices reales acá (posición en espacio de mundo =
 *    posición del nodo + posición local del quad × escala del nivel LOD).
 *
 * 4. El shader de blend/dithering (sección 6: patrón Bayer + discard) se
 *    escribe como GLSL y se sube junto con el resto de shaders del mod
 *    (típicamente en src/main/resources/assets/minecraftlodmod/shaders/).
 *
 * 5. Leer el FOV efectivo desde
 *    {@code net.neoforged.neoforge.client.event.ViewportEvent.ComputeFov}
 *    (leer DESPUÉS de que otros mods de zoom ya lo modificaron, con
 *    prioridad de evento baja / al final) y pasarlo al LodSelector.
 *
 * COMPATIBILIDAD CON MODS QUE REEMPLAZAN EL RENDERER (ej. VulkanMod):
 *
 * Regla estricta: NUNCA llamar directamente a OpenGL (GL11/GL30/GL45 de
 * LWJGL). Todo el dibujado debe pasar por las abstracciones vanilla de
 * Minecraft: {@code VertexConsumer}, {@code RenderType} (custom si hace
 * falta, registrado como cualquier RenderType del juego), y
 * {@code BufferBuilder}. Un mod que reemplaza el renderer completo
 * (VulkanMod hoy, o cualquier otro en el futuro) intercepta esas
 * abstracciones — pero no puede interceptar llamadas OpenGL crudas que
 * un mod haga por su cuenta, y ahí es donde se rompe la compatibilidad.
 *
 * El shader de blend/dithering (patrón Bayer + discard) debe registrarse
 * como un {@code ShaderInstance} del sistema de shaders de Minecraft, no
 * cargarse "a mano" con llamadas directas a la API de shaders de OpenGL.
 *
 * Nota: VulkanMod es históricamente un mod de Fabric — verificar temprano
 * si existe soporte real para NeoForge antes de invertir tiempo en probar
 * esta compatibilidad específica; si no lo hay, esta regla de todos modos
 * sigue siendo la correcta porque es la misma que garantiza compatibilidad
 * con Embeddium.
 */
public final class RenderHookPlaceholder {
    private RenderHookPlaceholder() {
    }
}
