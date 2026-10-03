package com.example.minecraftlodmod.network;

import com.example.minecraftlodmod.generation.NivelesGrandes;
import com.example.minecraftlodmod.generation.SectionExtractor;

/**
 * Una "rebanada" del LOD guardado: todas las entradas de una región
 * ({@code RegionFileStore.ClaveRegion}, en la dimensión del jugador) con el
 * mismo código de nivel en su clave (bits 22-25 de
 * {@code SectionExtractor.claveNodo}: 0-4 niveles reales, 5-10 niveles
 * grandes, 15 marcas y aproximado fino, el resto aproximado). Es la unidad
 * en que el cliente de multijugador pide el LOD al servidor: el render
 * busca claves de pocos niveles por región, y una rebanada trae de una vez
 * todo lo de ese nivel en esa región. Lógica pura.
 */
public record RebanadaId(int regionX, int regionZ, int codigo) {

    public RebanadaId {
        if (codigo < 0 || codigo > 15) {
            throw new IllegalArgumentException("Código de nivel fuera de rango: " + codigo);
        }
    }

    /** Las claves de nodo del store usan 26 bits; las más anchas no viajan. */
    static final long LIMITE_CLAVE = 1L << 26;

    /** Código de nivel de una clave de nodo; -1 si no es una clave de {@code SectionExtractor.claveNodo}. */
    public static int codigo(long claveNodo) {
        return claveNodo < 0 || claveNodo >= LIMITE_CLAVE ? -1 : (int) (claveNodo >>> 22) & 0xF;
    }

    public static RebanadaId de(int regionX, int regionZ, long claveNodo) {
        int c = codigo(claveNodo);
        return c < 0 ? null : new RebanadaId(regionX, regionZ, c);
    }

    /** Las 3 partes en un long, para mapas y conjuntos. */
    public long empaquetada() {
        return (long) (regionX & 0x3FFFFFF) << 30 | (long) (regionZ & 0x3FFFFFF) << 4 | codigo;
    }

    /**
     * ¿La región toca el radio servido alrededor del jugador (en chunks)? Los
     * nodos de niveles grandes (5-10) se guardan en la región de su esquina y
     * cubren hasta {@code NivelesGrandes.ladoEnSecciones(10)} chunks: para esos
     * el radio se estira lo que mide el nodo. Los demás códigos altos (aproximado
     * grande) igual, porque también guardan nodos que cubren varias regiones.
     */
    public boolean dentroDelRadio(int chunkX, int chunkZ, int radioChunks) {
        int extra = codigo >= 5 && codigo <= 10 ? NivelesGrandes.ladoEnSecciones(codigo)
                : codigo >= 11 && codigo <= 14 ? NivelesGrandes.ladoEnSecciones(10) : 0;
        int lado = SectionExtractor.LADO_REGION;
        int minX = regionX * lado, minZ = regionZ * lado;
        int dx = Math.max(0, Math.max(minX - chunkX, chunkX - (minX + lado - 1)));
        int dz = Math.max(0, Math.max(minZ - chunkZ, chunkZ - (minZ + lado - 1)));
        long radio = (long) radioChunks + extra;
        return dx <= radio && dz <= radio;
    }
}
