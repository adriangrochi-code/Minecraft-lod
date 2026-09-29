package com.example.minecraftlodmod.generation;

/**
 * TODO: extractor de datos de sección (hito 3 del documento de arquitectura).
 * La lógica pura ya está completa (HierarchicalReducer, GreedyMesher); esto
 * es la parte que sí necesita leer el mundo real vía la API de NeoForge/Minecraft.
 *
 * Requisitos de compatibilidad con generadores de mundo variados (mods de
 * worldgen, dimensiones custom, terreno modificado):
 *
 * 1. RANGO DE ALTURA DINÁMICO — nunca asumir 384/-64/320 fijos. Leer siempre
 *    {@code Level.getMinBuildHeight()} y {@code Level.getHeight()} en tiempo
 *    real; algunos mods de worldgen o dimension_type custom usan rangos
 *    verticales distintos a vanilla.
 *
 * 2. COLOR DESDE MapColor, NO UNA PALETA HARDCODEADA — usar
 *    {@code BlockState.getMapColor(...)} (el mismo sistema que usa el mapa
 *    in-game de Minecraft) para derivar el color del supervóxel. Esto
 *    funciona automáticamente para bloques de cualquier mod de worldgen sin
 *    mantener una tabla propia — si un bloque se ve bien en el mapa vanilla,
 *    también se va a ver bien en el LOD.
 *
 * 3. MATERIAL SIMPLIFICADO desde el propio MapColor o desde tags de bloque
 *    ({@code BlockTags.WATER... }, comprobación de fluido) en vez de listar
 *    IDs de bloque específicos uno por uno.
 *
 * 4. SECCIÓN VACÍA/HOMOGÉNEA — {@code LevelChunkSection.hasOnlyAir()} y
 *    comprobar si todos los BlockState de la sección son iguales, sin asumir
 *    qué bloque específico es (puede ser piedra vanilla, o el "stone
 *    replacement" de un mod de worldgen).
 *
 * Con estos 4 puntos, el extractor queda agnóstico al origen del terreno —
 * el resto del pipeline (HierarchicalReducer, GreedyMesher) ya opera sobre
 * SuperVoxel, que es una abstracción que no conoce Minecraft en absoluto.
 */
public final class SectionExtractorPlaceholder {
    private SectionExtractorPlaceholder() {
    }
}
