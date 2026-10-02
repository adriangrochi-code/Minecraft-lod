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
    /**
     * @param cobertura qué parte de la textura de cada estado no es transparente (0-255): una
     *                  flor tiñe el suelo de abajo en esa proporción ({@link #cobertura(int)})
     */
    public record Paleta(int[] rgbBase, byte[] tinte, int[] tinteFijo, byte[] cobertura) {
        public boolean contiene(int id) {
            return id >= 0 && id < rgbBase.length && rgbBase[id] >= 0;
        }
    }

    /** Cobertura de la textura del estado (0-255); 0 sin paleta o fuera de rango. */
    public static int cobertura(int id) {
        Paleta p = paleta;
        return p == null || !p.contiene(id) ? 0 : p.cobertura()[id] & 0xFF;
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

    /** Colores de pasto, follaje y agua en un lugar (0xRRGGBB), ya mezclados entre biomas vecinos. */
    public record Tintes(int pasto, int follaje, int agua) {
    }

    /** Como {@link #rgb(BlockState, Biome, int, int)}, con los tintes ya resueltos (mezcla de biomas). */
    public static int rgb(BlockState estado, Tintes tintes) {
        return rgb(Block.getId(estado), estado, tintes);
    }

    /** Con el id del estado ya conocido ({@code Block.getId} es una búsqueda en un mapa por bloque). */
    public static int rgb(int id, BlockState estado, Tintes tintes) {
        Paleta p = paleta;
        if (p == null || !p.contiene(id)) {
            return estado.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col;
        }
        int base = p.rgbBase()[id];
        return switch (TINTES[p.tinte()[id]]) {
            case NINGUNO -> base;
            case FIJO -> ColorTextura.tenir(base, p.tinteFijo()[id]);
            case PASTO -> ColorTextura.tenir(base, tintes.pasto());
            case FOLLAJE -> ColorTextura.tenir(base, tintes.follaje());
            case AGUA -> ColorTextura.tenir(base, tintes.agua());
        };
    }

    /** Color de bioma ya mezclado de un tipo de tinte en un lugar; se pide solo si el bloque lo usa. */
    public interface FuenteTinte {
        /** @param tinte PASTO, FOLLAJE o AGUA */
        int color(Tinte tinte, int x, int y, int z);
    }

    /**
     * Como {@link #rgb(int, BlockState, Tintes)}, pero el tinte se pide a la
     * fuente solo si el bloque lo usa (la piedra no mezcla biomas) y solo el
     * canal que usa. Mismo resultado.
     */
    public static int rgb(int id, BlockState estado, FuenteTinte fuente, int x, int y, int z) {
        Paleta p = paleta;
        if (p == null || !p.contiene(id)) {
            return estado.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col;
        }
        int base = p.rgbBase()[id];
        Tinte tinte = TINTES[p.tinte()[id]];
        return switch (tinte) {
            case NINGUNO -> base;
            case FIJO -> ColorTextura.tenir(base, p.tinteFijo()[id]);
            case PASTO, FOLLAJE, AGUA -> ColorTextura.tenir(base, fuente.color(tinte, x, y, z));
        };
    }

    /**
     * @param bioma  bioma en esa posición (para el tinte); puede ser null
     * @param x      coordenadas de mundo, para la variación del pasto (pantanos)
     * @return RGB 0xRRGGBB
     */
    public static int rgb(BlockState estado, Biome bioma, int x, int z) {
        return rgb(Block.getId(estado), estado, bioma, x, z);
    }

    /** Con el id del estado ya conocido. */
    public static int rgb(int id, BlockState estado, Biome bioma, int x, int z) {
        Paleta p = paleta;
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
