package com.example.minecraftlodmod.core;

/**
 * Un supervóxel: la unidad mínima de terreno LOD simplificado.
 * Corresponde 1:1 al formato binario de 8 bytes definido en la arquitectura
 * (ver sección 5 del documento): color (3), altura local (1), material (1),
 * flags (1), estado de bloque (2) — los 2 bytes antes "reservados".
 *
 * Es un record inmutable a propósito: los supervóxeles se generan una vez
 * en el pipeline de generation/ y no se mutan después — cualquier cambio
 * (por ejemplo al recalcular un nivel LOD, o al hornear la luz) crea uno nuevo.
 *
 * {@code alturaLocal} es el RELLENO del vóxel (ver {@link #relleno()}): qué
 * fracción de su alto ocupa lo sólido, medida desde abajo y promediada entre
 * sus columnas. Con eso la superficie de un vóxel grande se dibuja a la
 * altura real del terreno y no en el borde del cubo.
 *
 * Uso de {@code flags}: bit 0 = colapsado por homogeneidad, bit 1 = nevado
 * (ver {@link #nevado()}), bits 4-7 = luz horneada (0-15, ver
 * {@link #luzHorneada()}), bits 2-3 = luz de bloque cuantizada (0-3, ver
 * {@link #luzBloque()}; datos anteriores a 0.24.0 la tienen en 0).
 */
public record SuperVoxel(
        byte r, byte g, byte b,
        byte alturaLocal,
        Material material,
        byte flags,
        short estado
) {

    public static final int BYTES = 8;

    /** Relleno de un vóxel lleno (todo bloque visible del nivel 0). */
    public static final int LLENO = 255;

    /** Sin estado de bloque conocido: se dibuja con color plano. */
    public static final short SIN_ESTADO = 0;

    /**
     * Supervóxel sin estado de bloque (solo color).
     */
    public SuperVoxel(byte r, byte g, byte b, byte alturaLocal, Material material, byte flags) {
        this(r, g, b, alturaLocal, material, flags, SIN_ESTADO);
    }

    /**
     * Id global del estado de bloque que representa (el dominante, en niveles
     * reducidos), 0-65535; {@link #SIN_ESTADO} si no hay. Permite dibujar el
     * LOD con la TEXTURA real del paquete de texturas activo en el cliente,
     * no solo con su color — ver {@code render/PaletaTexturas}.
     */
    public int idEstado() {
        return estado & 0xFFFF;
    }

    /**
     * Cuánto del alto del vóxel ocupa lo sólido, de 0 a {@link #LLENO}: la
     * superficie de un vóxel con aire arriba está a {@code relleno/255} de su
     * alto. Es el promedio entre sus columnas, así una ladera queda a media
     * altura en vez de un escalón del tamaño del vóxel.
     */
    public int relleno() {
        return alturaLocal & 0xFF;
    }

    /** Copia con el relleno indicado (0-{@link #LLENO}). */
    public SuperVoxel conRelleno(int relleno) {
        if (relleno == relleno()) {
            return this; // inmutable: sin copia si no cambia (la generación lo llama por cada vóxel)
        }
        return new SuperVoxel(r, g, b, (byte) Math.max(0, Math.min(LLENO, relleno)), material, flags, estado);
    }

    /** Copia con el estado de bloque indicado (0-65535; fuera de rango queda sin estado). */
    public SuperVoxel conEstado(int idEstado) {
        short nuevo = idEstado > 0 && idEstado <= 0xFFFF ? (short) idEstado : SIN_ESTADO;
        return new SuperVoxel(r, g, b, alturaLocal, material, flags, nuevo);
    }

    public enum Material {
        AIRE((byte) 0),
        SOLIDO((byte) 1),
        AGUA((byte) 2),
        VEGETACION((byte) 3),
        /**
         * Planta alta y fina (caña, bambú, pasto alto, girasoles) en el nivel 0: no ocupa
         * volumen (para el terreno, las caras y la luz cuenta como aire), y se dibuja como
         * dos planos cruzados con su silueta ({@code GeometriaLod}). Al reducir a niveles
         * superiores desaparece.
         */
        CRUZ((byte) 4);

        public final byte codigo;

        Material(byte codigo) {
            this.codigo = codigo;
        }

        /** values() clona el arreglo en cada llamada; se llama por cada vóxel leído. */
        public static final Material[] TODOS = values();

        public static Material fromCodigo(byte codigo) {
            for (Material m : TODOS) {
                if (m.codigo == codigo) return m;
            }
            throw new IllegalArgumentException("Código de material desconocido: " + codigo);
        }
    }

    /** Aire o una planta en cruz: nada que tape caras, dé relieve o se reduzca. */
    public boolean sinVolumen() {
        return material == Material.AIRE || material == Material.CRUZ;
    }

    /** Bit 0 de flags: si el supervóxel proviene de un colapso por homogeneidad. */
    public boolean esHomogeneo() {
        return (flags & 0b0000_0001) != 0;
    }

    /**
     * Bit 1 de flags: tiene una capa de nieve encima. El vóxel conserva su
     * color y estado (los costados se ven como el bloque: hojas, pasto,
     * piedra) y solo su cara de arriba se dibuja como nieve
     * ({@code GreedyMesher}), en vez de un cubo blanco entero.
     */
    public boolean nevado() {
        return (flags & 0b0000_0010) != 0;
    }

    /** Copia con el bit de nevado puesto o sacado. */
    public SuperVoxel conNevado(boolean nevado) {
        byte nuevosFlags = (byte) (nevado ? flags | 0b0000_0010 : flags & ~0b0000_0010);
        if (nuevosFlags == flags) {
            return this;
        }
        return new SuperVoxel(r, g, b, alturaLocal, material, nuevosFlags, estado);
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

    /**
     * Luz de BLOQUE (antorchas, lava, faroles) que recibe, cuantizada a 0-3
     * en los bits 2-3 de {@code flags}: {@link #luzHorneada()} es el máximo
     * entre cielo y bloque, y de noche el render oscurece todo menos lo que
     * tiene luz de bloque (idea de Voxy: luz de cielo y de bloque por
     * separado). 2 bits alcanzan para que se vea de lejos.
     */
    public int luzBloque() {
        return (flags >> 2) & 0b11;
    }

    /** Luz de bloque 0-15 llevada a los 4 escalones de {@link #luzBloque()} (13-15 → 3, 8-12 → 2, 3-7 → 1). */
    public static int cuantizarLuzBloque(int nivel) {
        return Math.max(0, Math.min(3, (nivel + 2) / 5));
    }

    /** Copia con la luz de bloque ya cuantizada (0-3). */
    public SuperVoxel conLuzBloque(int cuantizada) {
        int q = Math.max(0, Math.min(3, cuantizada));
        byte nuevosFlags = (byte) ((flags & ~0b0000_1100) | (q << 2));
        if (nuevosFlags == flags) {
            return this;
        }
        return new SuperVoxel(r, g, b, alturaLocal, material, nuevosFlags, estado);
    }

    /** Devuelve una copia de este supervóxel con la luz horneada indicada (0-15). */
    public SuperVoxel conLuzHorneada(int nivelLuz) {
        if (nivelLuz < 0 || nivelLuz > 15) {
            throw new IllegalArgumentException("La luz horneada debe estar entre 0 y 15, fue: " + nivelLuz);
        }
        int flagsBajos = flags & 0x0F; // preservar bit de homogéneo y los demás bits bajos
        byte nuevosFlags = (byte) (flagsBajos | (nivelLuz << 4));
        return new SuperVoxel(r, g, b, alturaLocal, material, nuevosFlags, estado);
    }

    /** Serializa este supervóxel al formato binario de 8 bytes (big-endian, campo a campo). */
    public void escribirEn(byte[] destino, int offset) {
        destino[offset] = r;
        destino[offset + 1] = g;
        destino[offset + 2] = b;
        destino[offset + 3] = alturaLocal;
        destino[offset + 4] = material.codigo;
        destino[offset + 5] = flags;
        destino[offset + 6] = (byte) (estado >> 8);
        destino[offset + 7] = (byte) estado;
    }

    public static SuperVoxel leerDe(byte[] origen, int offset) {
        return new SuperVoxel(
                origen[offset],
                origen[offset + 1],
                origen[offset + 2],
                origen[offset + 3],
                Material.fromCodigo(origen[offset + 4]),
                origen[offset + 5],
                (short) (((origen[offset + 6] & 0xFF) << 8) | (origen[offset + 7] & 0xFF))
        );
    }
}
