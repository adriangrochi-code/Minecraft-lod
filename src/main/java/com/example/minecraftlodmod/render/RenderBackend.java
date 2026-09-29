package com.example.minecraftlodmod.render;

import com.example.minecraftlodmod.generation.Quad;

import java.util.List;

/**
 * Abstracción del backend de render, para poder tener dos implementaciones
 * intercambiables sin que el resto del mod (core/generation/storage) sepa
 * cuál está activa:
 *
 *   - VertexConsumerRenderBackend (a implementar en el hito de render real):
 *     dibuja usando VertexConsumer/RenderType de Minecraft — compatible con
 *     cualquier shader y con reemplazos de renderer razonables (Embeddium,
 *     y VulkanMod si en algún momento hay soporte real). Es el backend por
 *     DEFECTO.
 *
 *   - RawGpuRenderBackend (opcional, futuro, opt-in del usuario): bypass del
 *     renderer vanilla al estilo Distant Horizons / FarPlaneTwo, priorizando
 *     rendimiento y radio máximo por sobre compatibilidad — mismo trade-off
 *     que esos mods (pierde compatibilidad con shaders genéricos y con
 *     reemplazos de renderer). Se activa explícitamente desde la config,
 *     nunca por defecto, y el usuario debe ser informado del trade-off.
 *
 * Esta interfaz es intencionalmente mínima y en términos genéricos (no usa
 * tipos de Minecraft) para que ambas implementaciones puedan satisfacerla
 * sin acoplar core/generation/storage a ninguna de las dos.
 */
public interface RenderBackend {

    /** Nombre para mostrar en la config/logs (ej. "Compatible (VertexConsumer)", "Alto rendimiento (nativo)"). */
    String nombre();

    /** true si este backend requiere que el usuario acepte explícitamente un trade-off de compatibilidad. */
    boolean requiereAdvertenciaDeCompatibilidad();

    /**
     * Sube (o actualiza) la geometría de una región a la estructura de datos
     * de GPU que use este backend. La lista de quads viene de
     * {@link com.example.minecraftlodmod.generation.GreedyMesher}, ya con
     * la luz por vértice de
     * {@link com.example.minecraftlodmod.generation.VertexLightSampler}
     * aplicada por quien llama.
     */
    void subirGeometriaDeRegion(long claveRegion, List<Quad> quads);

    /** Descarta la geometría de una región (ej. salió del radio de renderizado). */
    void descargarGeometriaDeRegion(long claveRegion);

    /** Dibuja todas las regiones actualmente subidas — el detalle de cómo depende de la implementación. */
    void dibujarFrame();
}
