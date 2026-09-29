package com.example.minecraftlodmod.core;

/**
 * Un supervóxel: la unidad mínima de terreno LOD simplificado.
 * Corresponde 1:1 al formato binario de 8 bytes definido en la arquitectura
 * (ver sección 5 del documento): color (3), altura local (1), material (1),
 * flags (1), reservado (2).
 *
 * Es un record inmutable a propósito: los supervóxeles se generan una vez
 * en el pipeline de generation/ y no se mutan después — cualquier cambio
 * (por ejemplo al recalcular un nivel LOD, o al hornear la luz) crea uno nuevo.
 *
 * Uso de {@code flags}: bit 0 = colapsado por homogeneidad, bits 4-7 =
 * luz horneada (0-15, ver {@link #luzHorneada()}). Los bits 1-3 quedan
 * libres para futuros flags sin romper compatibilidad de formato.
 */
public record SuperVoxel(
        byte r, byte g, byte b,
        byte alturaLocal,
        Material material,
        byte flags
) {

    public static final int BYTES = 8;

    public enum Material {
        AIRE((byte) 0),
        SOLIDO((byte) 1),
        AGUA((byte) 2),
        VEGETACION((byte) 3);

        public final byte codigo;

        Material(byte codigo) {
            this.codigo = codigo;
        }

        public static Material fromCodigo(byte codigo) {
            for (Material m : values()) {
                if (m.codigo == codigo) return m;
            }
            throw new IllegalArgumentException("Código de material desconocido: " + codigo);
        }
    }

    /** Bit 0 de flags: si el supervóxel proviene de un colapso por homogeneidad. */
    public boolean esHomogeneo() {
        return (flags & 0b0000_0001) != 0;
    }

    /**
     * Luz horneada (0-15, misma escala que la luz de bloque/cielo de
     * Minecraft), empaquetada en los 4 bits altos de {@code flags}. Se
     * calcula una sola vez al generar el supervóxel (promedio de la luz
     * vanilla de los bloques que representa) en vez de iluminar el terreno
     * lejano en tiempo real — ver la sección de iluminación del documento
     * de arquitectura.
     */
    public int luzHorneada() {
        return (flags >> 4) & 0x0F;
    }

    /** Devuelve una copia de este supervóxel con la luz horneada indicada (0-15). */
    public SuperVoxel conLuzHorneada(int nivelLuz) {
        if (nivelLuz < 0 || nivelLuz > 15) {
            throw new IllegalArgumentException("La luz horneada debe estar entre 0 y 15, fue: " + nivelLuz);
        }
        int flagsBajos = flags & 0x0F; // preservar bit de homogéneo y los demás bits bajos
        byte nuevosFlags = (byte) (flagsBajos | (nivelLuz << 4));
        return new SuperVoxel(r, g, b, alturaLocal, material, nuevosFlags);
    }

    /** Serializa este supervóxel al formato binario de 8 bytes (big-endian, campo a campo). */
    public void escribirEn(byte[] destino, int offset) {
        destino[offset] = r;
        destino[offset + 1] = g;
        destino[offset + 2] = b;
        destino[offset + 3] = alturaLocal;
        destino[offset + 4] = material.codigo;
        destino[offset + 5] = flags;
        destino[offset + 6] = 0; // reservado
        destino[offset + 7] = 0; // reservado
    }

    public static SuperVoxel leerDe(byte[] origen, int offset) {
        return new SuperVoxel(
                origen[offset],
                origen[offset + 1],
                origen[offset + 2],
                origen[offset + 3],
                Material.fromCodigo(origen[offset + 4]),
                origen[offset + 5]
        );
    }
}
