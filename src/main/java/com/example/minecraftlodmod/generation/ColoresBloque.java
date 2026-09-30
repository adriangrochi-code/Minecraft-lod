package com.example.minecraftlodmod.generation;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Color de un bloque para el LOD. Si el cliente publicó una
 * {@link Paleta} (texturas reales, ver {@code render/PaletaTexturas}), se
 * usa promedio de textura × tinte del bioma ({@link ColorTextura}); si no —
 * servidor dedicado, que no tiene texturas — el color de mapa de vanilla.
 *
 * En singleplayer el servidor integrado comparte la JVM con el cliente, así
 * que genera con la paleta real. En multiplayer los nodos llegan con color
 * de mapa: recolorear en el cliente queda anotado en NOTES.md.
 */
public final class ColoresBloque {

    /** Qué color de bioma multiplica a la textura (como {@code BlockColors} de vanilla). */
    public enum Tinte {
        NINGUNO, PASTO, FOLLAJE, AGUA, FIJO
    }

    /**
     * Paleta indexada por id global de estado de bloque
     * ({@link Block#getId(BlockState)}); inmutable una vez publicada.
     */
    public record Paleta(int[] rgbBase, byte[] tinte, int[] tinteFijo) {
        public boolean contiene(int id) {
            return id >= 0 && id < rgbBase.length && rgbBase[id] >= 0;
        }
    }

    private static volatile Paleta paleta;
    /** Tinte.values() clona el arreglo en cada llamada; se usa por cada bloque. */
    private static final Tinte[] TINTES = Tinte.values();

    private ColoresBloque() {
    }

    /** Lo llama el cliente al (re)cargar recursos; null para volver al color de mapa. */
    public static void publicar(Paleta nueva) {
        paleta = nueva;
    }

    public static boolean hayPaletaDeTexturas() {
        return paleta != null;
    }

    /**
     * @param bioma  bioma en esa posición (para el tinte); puede ser null
     * @param x      coordenadas de mundo, para la variación del pasto (pantanos)
     * @return RGB 0xRRGGBB
     */
    public static int rgb(BlockState estado, Biome bioma, int x, int z) {
        Paleta p = paleta;
        int id = Block.getId(estado);
        if (p == null || !p.contiene(id)) {
            return estado.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col;
        }
        int base = p.rgbBase()[id];
        Tinte tinte = TINTES[p.tinte()[id]];
        return switch (tinte) {
            case NINGUNO -> base;
            case FIJO -> ColorTextura.tenir(base, p.tinteFijo()[id]);
            case PASTO -> bioma == null ? base : ColorTextura.tenir(base, bioma.getGrassColor(x, z));
            case FOLLAJE -> bioma == null ? base : ColorTextura.tenir(base, bioma.getFoliageColor());
            case AGUA -> bioma == null ? base : ColorTextura.tenir(base, bioma.getWaterColor());
        };
    }
}
