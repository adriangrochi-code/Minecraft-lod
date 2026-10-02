package com.example.minecraftlodmod.render;

import com.example.minecraftlodmod.core.SuperVoxel;
import com.example.minecraftlodmod.generation.GreedyMesher;
import com.example.minecraftlodmod.generation.Quad;
import com.example.minecraftlodmod.generation.VertexLightSampler;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Vértices de LOD listos para subir (lógica pura, sin Minecraft): recibe
 * grillas de supervóxeles por sección, las pasa por {@link GreedyMesher} y
 * acumula 4 vértices por quad — posición (float x3, relativa al origen de
 * la celda) y color ARGB ya sombreado.
 *
 * Sombreado, para que el terreno lejano no se vea plano:
 *  - por cara, los mismos factores que usa vanilla para bloques (arriba 1.0,
 *    abajo 0.5, norte/sur 0.8, este/oeste 0.6);
 *  - por vértice, la luz horneada de {@link VertexLightSampler}.
 * La perspectiva atmosférica (sección 17) todavía no se aplica acá: queda
 * para Pista B, cuando se pueda juzgar viéndola.
 *
 * Formato compacto de subida ({@link #escribirCompacto}, idea tomada de
 * Voxy — sección 25 del documento de arquitectura, sin su código): 12
 * bytes por vértice en vez de 36. Las posiciones son bloques enteros
 * relativos a la celda (shorts), y la textura no viaja por vértice: solo
 * el índice del sprite, que el shader resuelve con una tabla (ver
 * {@link #texelesSprite}); la normal sale del índice de cara.
 *
 * Por dirección (sección 25, punto 3): los vértices se escriben agrupados
 * por cara ({@link #escribirCompacto(ByteBuffer, int)}) con el plano
 * mínimo y máximo de cada grupo, para no dibujar los grupos que miran en
 * sentido contrario a la cámara ({@link #caraVisible}). Dentro de un grupo
 * visible, el orden de vértices es antihorario visto desde afuera, así el
 * culling de caras traseras de Minecraft descarta el resto.
 */
public final class GeometriaLod {

    /** Bits de {@code carasOmitidas}: caras laterales en el borde de la sección que no se dibujan. */
    public static final int OMITIR_X_NEG = 1, OMITIR_X_POS = 2, OMITIR_Z_NEG = 4, OMITIR_Z_POS = 8;

    /** Caras (direcciones) posibles: 0 -X, 1 +X, 2 -Y, 3 +Y, 4 -Z, 5 +Z. */
    public static final int CARAS = 6;
    /**
     * Grupo aparte para el agua cuando se separa ({@link #separarAgua}): se dibuja en
     * una pasada translúcida propia (contrato Voxy de los shaderpacks).
     */
    public static final int GRUPO_AGUA = CARAS;
    /**
     * Grupo de las plantas en cruz ({@link #dibujarCruces}): planos con silueta recortada
     * por alfa, dibujados aparte (shader con discard y sin culling: se ven de los dos lados).
     */
    public static final int GRUPO_CRUZ = CARAS + 1;
    /** Grupos de vértices: las 6 caras, el agua y las cruces. */
    public static final int GRUPOS = CARAS + 2;
    /**
     * Marca de cruz en {@link #niveles} (bit 7 del byte de alfa del vértice compacto; el
     * log2 del vóxel no pasa de 10): el shader corre el plano medio bloque hacia adentro,
     * al centro del bloque, que el formato de posiciones enteras no puede expresar.
     */
    static final int MARCA_CRUZ = 16;
    /**
     * Bytes por vértice del formato del contrato Voxy: el compacto más un int con el
     * estado de bloque (bits 0-15, para el {@code customId} del pack), la luz de cielo
     * horneada 0-15 (bits 16-19) y si es agua (bit 20). El color va sin sombra por cara:
     * el pack ilumina solo.
     */
    public static final int BYTES_VOXY = 16;

    /** Bytes por vértice del formato compacto: 3 shorts de posición + short de sprite + RGBA. */
    public static final int BYTES_COMPACTO = 12;
    /**
     * Bytes por vértice del formato de bloque vanilla ({@code DefaultVertexFormat.BLOCK}):
     * posición (3 float), RGBA, UV (2 float), lightmap (2 short), normal (3 byte + relleno).
     * Es el que usan los shaderpacks (Iris lo dibuja con su gbuffers_terrain).
     */
    public static final int BYTES_BLOQUE = 32;
    /**
     * El formato de bloque extendido que Iris usa con un shaderpack activo
     * ({@code IrisVertexFormats.TERRAIN}): el de vanilla más mc_Entity (2 short),
     * mc_midTexCoord (2 float), at_tangent (4 byte) y at_midBlock (3 byte + relleno).
     */
    public static final int BYTES_BLOQUE_IRIS = 52;
    /** Texeles RGBA8 por sprite en la tabla que lee el shader. */
    public static final int TEXELES_POR_SPRITE = 4;
    /** Índice de sprite más alto que entra en el vértice compacto (14 bits; los 2 de arriba son luz de bloque). */
    public static final int MAX_SPRITE = 0x3FFF;
    /** Sprites por fila de la tabla (textura de 1024 texeles de ancho). */
    public static final int SPRITES_POR_FILA = 256;

    /**
     * Brillo por nivel de oclusión ambiental (índice 0 = rincón cerrado, 3 =
     * libre). Valores de partida parecidos al smooth lighting de vanilla; el
     * ajuste fino es de Pista B (se juzga viéndolo).
     */
    static final float[] BRILLO_OCLUSION = {0.55f, 0.7f, 0.85f, 1.0f};

    /** Brillo mínimo con luz horneada 0: el terreno a oscuras no queda negro puro. */
    static final float LUZ_MINIMA = 0.25f;

    /**
     * De dónde sale la textura de una cara (la implementa el cliente con
     * el atlas de bloques activo, ver {@code PaletaTexturas}). Mantiene
     * esta clase pura: no conoce sprites ni atlas.
     */
    public interface Texturas {
        /** @return la textura de esa cara del estado de bloque, o null para dibujarla con color plano */
        Cara cara(int idEstado, Quad.Eje eje, boolean positivo);

        /** Silueta recortable de una planta en cruz (con alfa); null = no se dibuja. */
        default Cara cruz(int idEstado) {
            return null;
        }
    }

    /**
     * Textura de una cara: índice del sprite en la tabla del shader (1..65535;
     * su rectángulo en el atlas y su promedio viven ahí), su color promedio,
     * y si el color del vóxel (que ya trae el tinte del bioma) aplica a esta
     * cara. No aplica, por ejemplo, al costado de un bloque de pasto: la
     * tierra no se tiñe, así que ahí manda el promedio de su textura.
     *
     * {@code spriteAbajo} (0 = ninguno): en un costado con franja (pasto,
     * nieve) la textura que va debajo de la fila de arriba, con su promedio.
     */
    public record Cara(int sprite, int promedioRgb, boolean usaColorDelVoxel, int spriteAbajo, int promedioAbajoRgb) {

        public Cara(int sprite, int promedioRgb, boolean usaColorDelVoxel) {
            this(sprite, promedioRgb, usaColorDelVoxel, 0, 0);
        }
    }

    /** Capacidad que {@link #reiniciar} conserva (más grande, vuelve a la inicial): ~12 MB de arreglos. */
    static final int MAX_VERTICES_RETENIDOS = 1 << 19;

    private float[] posiciones = new float[3 * 1024];
    private int[] colores = new int[1024];
    /** Color sin luz horneada ni sombra por cara (para shaders, que iluminan solos). */
    private int[] coloresBase = new int[1024];
    /** Luz horneada 0-15 por vértice (va al lightmap en el formato de bloque). */
    private byte[] luces = new byte[1024];
    /** Por cara: cantidad de vértices y planos extremos (coordenada sobre su eje, en bloques de la celda). */
    private final int[] verticesPorCara = new int[GRUPOS];
    private final float[] planoMin = new float[GRUPOS];
    private final float[] planoMax = new float[GRUPOS];

    {
        Arrays.fill(planoMin, Float.POSITIVE_INFINITY);
        Arrays.fill(planoMax, Float.NEGATIVE_INFINITY);
    }

    /** Luz de bloque cuantizada 0-3 por vértice ({@link SuperVoxel#luzBloque()}). */
    private byte[] lucesBloque = new byte[1024];
    /** Índice de sprite por vértice (0: sin textura). */
    private int[] sprites = new int[1024];
    /** Cara por vértice: 0 -X, 1 +X, 2 -Y, 3 +Y, 4 -Z, 5 +Z. */
    private byte[] caras = new byte[1024];
    /** log2 de los bloques por lado del vóxel de cada vértice (0 = bloque, 4 = sección entera). */
    private byte[] niveles = new byte[1024];
    /** Estado de bloque del vóxel de cada vértice ({@link SuperVoxel#idEstado()}). */
    private int[] estados = new int[1024];
    /** Grupo de cada vértice: su cara, o {@link #GRUPO_AGUA}. */
    private byte[] grupos = new byte[1024];
    private int vertices;
    private boolean separarAgua;
    private Texturas fuenteTexturas;
    private boolean descartarSinLuz;
    private boolean costadosAlAire;
    private boolean bordesAlAire;
    private static final VertexLightSampler.LuzEsquinas LUZ_PLENA = new VertexLightSampler.LuzEsquinas(15, 15, 15, 15);
    private boolean oclusionAmbiental;
    private boolean oclusionCostados;
    private boolean cruces;

    /**
     * Vacía la geometría conservando sus arreglos: el hilo de mallas usa siempre la
     * misma. Antes cada celda creaba una nueva que crecía duplicando sus arreglos
     * hasta varios MB (asignaciones "humongous" que disparaban pausas del GC).
     */
    public void reiniciar() {
        if (colores.length > MAX_VERTICES_RETENIDOS) {
            // Una celda enorme no deja decenas de MB retenidos para siempre.
            posiciones = new float[3 * 1024];
            colores = new int[1024];
            coloresBase = new int[1024];
            luces = new byte[1024];
            lucesBloque = new byte[1024];
            sprites = new int[1024];
            caras = new byte[1024];
            niveles = new byte[1024];
            estados = new int[1024];
            grupos = new byte[1024];
        }
        vertices = 0;
        Arrays.fill(verticesPorCara, 0);
        Arrays.fill(planoMin, Float.POSITIVE_INFINITY);
        Arrays.fill(planoMax, Float.NEGATIVE_INFINITY);
        fuenteTexturas = null;
        descartarSinLuz = false;
        costadosAlAire = false;
        bordesAlAire = false;
        oclusionAmbiental = false;
        oclusionCostados = false;
        separarAgua = false;
        cruces = false;
    }

    /**
     * Terreno aproximado: no tiene cuevas, así que toda cara lateral que queda
     * (las enterradas entre chunks las tapan sus vecinos) da al aire. Su luz
     * horneada no sirve (el subsuelo de una columna se guarda con luz 0) y la
     * pared de un acantilado se descartaba como "sin luz": huecos con el cielo
     * detrás. Con esto, los costados llevan luz plena y no se descartan.
     *
     * @param bordes también las caras del borde de la grilla. Con vecinas a los
     *               costados (chunks aproximados) las enterradas ya no se generan;
     *               sin ellas (teselas grandes) el borde sigue la regla de la luz,
     *               o cada tesela dibujaría paredes enterradas de cientos de bloques.
     */
    public void costadosAlAire(boolean alAire, boolean bordes) {
        this.costadosAlAire = alAire;
        this.bordesAlAire = alAire && bordes;
    }

    private boolean costadoAlAire(Quad q, int capa, int lado) {
        if (!costadosAlAire || q.eje() == Quad.Eje.Y) {
            return false;
        }
        boolean borde = q.positivo() ? capa == lado - 1 : capa == 0;
        return bordesAlAire || !borde;
    }

    /** El agua va a {@link #GRUPO_AGUA} en vez de al grupo de su cara (pasada translúcida aparte). */
    public void separarAgua(boolean separar) {
        this.separarAgua = separar;
    }

    /**
     * Plantas en cruz ({@link SuperVoxel.Material#CRUZ}) del nivel 0 como dos planos con su
     * silueta, en {@link #GRUPO_CRUZ}. Solo con texturas (el recorte es del shader propio);
     * si no, no se dibujan (como el aire).
     */
    public void dibujarCruces(boolean dibujar) {
        this.cruces = dibujar;
    }

    /** Oscurecer rincones y bases de paredes (oclusión ambiental por vértice, sección 25 punto 5). */
    public void usarOclusionAmbiental(boolean usar) {
        this.oclusionAmbiental = usar;
    }

    /** Con la oclusión prendida, también en costados y caras de abajo (no solo arriba). */
    public void usarOclusionCostados(boolean usar) {
        this.oclusionCostados = usar;
    }

    /**
     * Descartar caras sin ninguna luz (cielo ni bloque) en sus cuatro
     * esquinas: interiores de cuevas y caras enterradas, invisibles desde
     * afuera. Es el "cave culling" de Distant Horizons; sin esto el LOD de
     * un radio grande dibuja decenas de millones de vértices ocultos.
     */
    public void descartarCarasSinLuz(boolean descartar) {
        this.descartarSinLuz = descartar;
    }

    /** Fuente de texturas para lo que se agregue después; null = todo con color plano. */
    public void usarTexturas(Texturas fuente) {
        this.fuenteTexturas = fuente;
    }

    /**
     * @param grid   grilla de la sección en el nivel elegido, indexada (x*lado+y)*lado+z
     * @param lado   supervóxeles por arista (16 en nivel 0, 1 en nivel 4)
     * @param ox     origen de la sección relativo a la celda, en bloques
     * @param escala bloques por supervóxel (16 / lado)
     * @return quads agregados
     */
    public int agregarSeccion(SuperVoxel[] grid, int lado, float ox, float oy, float oz, float escala) {
        return agregarSeccion(grid, lado, ox, oy, oz, escala, 0);
    }

    /**
     * @param carasOmitidas bits {@code OMITIR_*}: caras en el borde de la
     *                      sección tapadas por el vecino (vanilla u otro chunk
     *                      de LOD). Sin esto cada chunk es una caja cerrada y,
     *                      donde el vecino lo dibuja vanilla, queda a la vista
     *                      su pared subterránea sin luz.
     */
    public int agregarSeccion(SuperVoxel[] grid, int lado, float ox, float oy, float oz, float escala,
                              int carasOmitidas) {
        return agregarSeccion(grid, lado, ox, oy, oz, escala, carasOmitidas, null);
    }

    /**
     * @param vecinos vóxeles de las grillas de al lado del mismo nivel (ver
     *                {@link GreedyMesher.Vecinos}): las caras del borde que
     *                tapan no se generan. null = bordes expuestos.
     */
    public int agregarSeccion(SuperVoxel[] grid, int lado, float ox, float oy, float oz, float escala,
                              int carasOmitidas, GreedyMesher.Vecinos vecinos) {
        int agregados = 0;
        // Superficie a la altura real dentro de los vóxeles grandes (recortes en bloques enteros).
        int bloquesPorVoxel = escala >= 2 && escala == Math.round(escala) ? Math.round(escala) : 0;
        for (Quad q : GreedyMesher.mallar(grid, lado, vecinos, oclusionAmbiental, oclusionCostados, bloquesPorVoxel, superficies())) {
            if (omitida(q, lado, carasOmitidas)) {
                continue;
            }
            if (agregarQuad(grid, lado, q, ox, oy, oz, escala)) {
                agregados++;
            }
        }
        if (cruces && fuenteTexturas != null && escala == 1f) {
            agregados += agregarCruces(grid, lado, ox, oy, oz);
        }
        return agregados;
    }

    /**
     * Dos planos por planta (uno perpendicular a X y otro a Z, por el centro del bloque),
     * una cruz "+" vista de arriba; una columna de la misma planta (caña, bambú) va en un
     * solo par de planos. Luz: la del lugar de la planta, sin oclusión.
     *
     * @return planos agregados
     */
    int agregarCruces(SuperVoxel[] grid, int lado, float ox, float oy, float oz) {
        int agregados = 0;
        for (int x = 0; x < lado; x++) {
            for (int z = 0; z < lado; z++) {
                int y = 0;
                while (y < lado) {
                    SuperVoxel v = grid[(x * lado + y) * lado + z];
                    if (v.material() != SuperVoxel.Material.CRUZ) {
                        y++;
                        continue;
                    }
                    int alto = 1;
                    while (y + alto < lado) {
                        SuperVoxel w = grid[(x * lado + y + alto) * lado + z];
                        if (w.material() != SuperVoxel.Material.CRUZ || w.estado() != v.estado()
                                || w.r() != v.r() || w.g() != v.g() || w.b() != v.b()
                                || w.luzHorneada() != v.luzHorneada()) {
                            break;
                        }
                        alto++;
                    }
                    Cara cara = v.idEstado() == SuperVoxel.SIN_ESTADO ? null : fuenteTexturas.cruz(v.idEstado());
                    // Sin tesela propia (sprites de más): sin recorte sería un rectángulo, no se dibuja.
                    if (cara != null && cara.sprite() != 0) {
                        int rgb = cara.usaColorDelVoxel()
                                ? ((v.r() & 0xFF) << 16) | ((v.g() & 0xFF) << 8) | (v.b() & 0xFF) : cara.promedioRgb();
                        float x0 = ox + x, y0 = oy + y, z0 = oz + z;
                        // Plano X (cara +X, corrido a x + 0,5 por el shader): de z a z+1.
                        verticeCruz(x0, y0, z0, 1, v, rgb, cara);
                        verticeCruz(x0, y0, z0 + 1, 1, v, rgb, cara);
                        verticeCruz(x0, y0 + alto, z0 + 1, 1, v, rgb, cara);
                        verticeCruz(x0, y0 + alto, z0, 1, v, rgb, cara);
                        // Plano Z (cara +Z, a z + 0,5): de x a x+1.
                        verticeCruz(x0, y0, z0, 5, v, rgb, cara);
                        verticeCruz(x0 + 1, y0, z0, 5, v, rgb, cara);
                        verticeCruz(x0 + 1, y0 + alto, z0, 5, v, rgb, cara);
                        verticeCruz(x0, y0 + alto, z0, 5, v, rgb, cara);
                        agregados += 2;
                    }
                    y += alto;
                }
            }
        }
        return agregados;
    }

    /** Sin sombra por cara, como vanilla: los modelos en cruz llevan "shade": false. */
    static final float SOMBRA_CRUZ = 1f;

    private void verticeCruz(float x, float y, float z, int cara, SuperVoxel v, int rgb, Cara textura) {
        asegurarCapacidad();
        int i = vertices * 3;
        posiciones[i] = x;
        posiciones[i + 1] = y;
        posiciones[i + 2] = z;
        int luz = v.luzHorneada();
        colores[vertices] = color(rgb, SOMBRA_CRUZ, luz);
        coloresBase[vertices] = color(rgb, 1f, 15);
        luces[vertices] = (byte) luz;
        lucesBloque[vertices] = (byte) v.luzBloque();
        sprites[vertices] = textura.sprite();
        caras[vertices] = (byte) cara;
        niveles[vertices] = (byte) MARCA_CRUZ;
        estados[vertices] = v.idEstado();
        grupos[vertices] = (byte) GRUPO_CRUZ;
        verticesPorCara[GRUPO_CRUZ]++;
        vertices++;
    }

    /**
     * Para el mesher: lo que dibuja cada cara según la fuente de texturas, así se
     * fusionan vóxeles de distinto estado con la misma textura. Sin texturas, null
     * (decide solo el color).
     */
    private GreedyMesher.Superficies superficies() {
        Texturas fuente = fuenteTexturas;
        if (fuente == null) {
            return null;
        }
        return (idEstado, eje, positivo) -> {
            Cara cara = idEstado == SuperVoxel.SIN_ESTADO ? null : fuente.cara(idEstado, eje, positivo);
            return claveCara(cara);
        };
    }

    /** Misma clave = misma textura, misma franja y el mismo color base (0 = sin textura). */
    static int claveCara(Cara cara) {
        if (cara == null) {
            return 0;
        }
        return (cara.sprite() & MAX_SPRITE) | (cara.spriteAbajo() & MAX_SPRITE) << 14
                | (cara.usaColorDelVoxel() ? 1 << 28 : 0) | 1 << 29;
    }

    static boolean omitida(Quad q, int lado, int carasOmitidas) {
        if (carasOmitidas == 0) {
            return false;
        }
        return switch (q.eje()) {
            case X -> q.positivo() ? q.x() == lado - 1 && (carasOmitidas & OMITIR_X_POS) != 0
                    : q.x() == 0 && (carasOmitidas & OMITIR_X_NEG) != 0;
            case Z -> q.positivo() ? q.z() == lado - 1 && (carasOmitidas & OMITIR_Z_POS) != 0
                    : q.z() == 0 && (carasOmitidas & OMITIR_Z_NEG) != 0;
            case Y -> false;
        };
    }

    /** @return false si la cara se descartó (sin luz) */
    private boolean agregarQuad(SuperVoxel[] grid, int lado, Quad q, float ox, float oy, float oz, float escala) {
        // Descomposición (capa, u, v) inversa a GreedyMesher#construirQuad:
        // X -> u=y (alto), v=z (ancho) | Y -> u=x (ancho), v=z (alto) | Z -> u=x (ancho), v=y (alto)
        int capa, u0, v0, largoU, largoV;
        switch (q.eje()) {
            case X -> { capa = q.x(); u0 = q.y(); v0 = q.z(); largoU = q.alto(); largoV = q.ancho(); }
            case Y -> { capa = q.y(); u0 = q.x(); v0 = q.z(); largoU = q.ancho(); largoV = q.alto(); }
            default -> { capa = q.z(); u0 = q.x(); v0 = q.y(); largoU = q.ancho(); largoV = q.alto(); }
        }
        float plano = capa + (q.positivo() ? 1 : 0);
        VertexLightSampler.LuzEsquinas luz = costadoAlAire(q, capa, lado) ? LUZ_PLENA
                : VertexLightSampler.calcular(grid, lado, q);
        // Las caras bajo el agua (fondo marino) se ven a través del agua aunque estén a oscuras.
        if (descartarSinLuz && !q.bajoAgua()
                && luz.minMin() == 0 && luz.maxMin() == 0 && luz.minMax() == 0 && luz.maxMax() == 0) {
            return false;
        }
        SuperVoxel v = q.voxelRepresentativo();
        if (separarAgua && v.material() == SuperVoxel.Material.AGUA && !(q.eje() == Quad.Eje.Y && q.positivo())
                && (q.eje() == Quad.Eje.Y || capa == (q.positivo() ? lado - 1 : 0))) {
            // Agua translúcida: sin las paredes de agua en el borde de la grilla (entre celdas y
            // secciones, donde el vecino no se conoce): opacas quedaban tapadas por la superficie,
            // translúcidas se verían como una grilla. Los costados de adentro dan a aire de verdad
            // (cascadas, ríos en pendiente) y se dibujan.
            return false;
        }
        float sombra = sombraDeCara(q.eje(), q.positivo());
        Cara cara = fuenteTexturas == null || v.idEstado() == SuperVoxel.SIN_ESTADO ? null
                : fuenteTexturas.cara(v.idEstado(), q.eje(), q.positivo());
        int rgbBase = cara == null || cara.usaColorDelVoxel()
                ? ((v.r() & 0xFF) << 16) | ((v.g() & 0xFF) << 8) | (v.b() & 0xFF)
                : cara.promedioRgb();

        // Contorno (u0,v0) (u1,v0) (u1,v1) (u0,v1): su normal por la regla de la
        // mano derecha es +X, -Y o +Z según el eje; para las otras tres caras se
        // recorre al revés, así todas quedan antihorarias vistas desde afuera.
        int u1 = u0 + largoU, v1 = v0 + largoV;
        // Esquinas en orden antihorario: índice 0 (u0,v0), 1 (u1,v0), 2 (u1,v1), 3 (u0,v1).
        int[] us = {u0, u1, u1, u0};
        int[] vs = {v0, v0, v1, v1};
        int[] luces = {luz.minMin(), luz.maxMin(), luz.maxMax(), luz.minMax()};
        int[] oclusion = {q.oclusionEn(false, false), q.oclusionEn(true, false),
                q.oclusionEn(true, true), q.oclusionEn(false, true)};
        int[] orden = ordenEsquinas(antihorarioDirecto(q.eje(), q.positivo()), oclusion);
        if (!q.recortado()) {
            float[] sinAjuste = new float[4];
            emitir(q, plano, us, vs, luces, oclusion, orden, sinAjuste, rgbBase, cara, sombra, ox, oy, oz, escala,
                    nivelDeEscala(escala));
            return true;
        }
        // Superficie a la altura real: la cara de arriba baja entera; en los costados el borde
        // de arriba baja y el de abajo sube (el alto está en u para X y en v para Z).
        float[] arriba = new float[4], abajo = new float[4];
        boolean[] esArriba = new boolean[4];
        for (int e = 0; e < 4; e++) {
            esArriba[e] = switch (q.eje()) {
                case Y -> true;
                case X -> us[e] == u1;
                case Z -> vs[e] == v1;
            };
        }
        int alto = Math.round(escala) - q.recorteArriba() - q.recorteAbajo();
        boolean franja = q.eje() != Quad.Eje.Y && cara != null && cara.spriteAbajo() != 0 && alto > 1;
        if (!franja) {
            for (int e = 0; e < 4; e++) {
                arriba[e] = esArriba[e] ? -q.recorteArriba() : q.recorteAbajo();
            }
            // Nivel 0: el shader repite la textura por bloque sin buscar el borde del vóxel.
            emitir(q, plano, us, vs, luces, oclusion, orden, arriba, rgbBase, cara, sombra, ox, oy, oz, escala, 0);
            return true;
        }
        // Costado con franja (pasto, nieve): la fila de arriba con su textura y debajo la de abajo
        // (tierra), como el corte del terreno; el shader no puede saber dónde quedó el borde.
        for (int e = 0; e < 4; e++) {
            arriba[e] = esArriba[e] ? -q.recorteArriba() : q.recorteAbajo() + alto - 1;
            abajo[e] = esArriba[e] ? -q.recorteArriba() - 1 : q.recorteAbajo();
        }
        emitir(q, plano, us, vs, luces, oclusion, orden, arriba, rgbBase, cara, sombra, ox, oy, oz, escala, 0);
        Cara caraAbajo = new Cara(cara.spriteAbajo(), cara.promedioAbajoRgb(), false);
        emitir(q, plano, us, vs, luces, oclusion, orden, abajo, cara.promedioAbajoRgb(), caraAbajo, sombra,
                ox, oy, oz, escala, 0);
        return true;
    }

    /** Las 4 esquinas del quad en el orden dado, con un ajuste en Y (bloques) por esquina. */
    private void emitir(Quad q, float plano, int[] us, int[] vs, int[] luces, int[] oclusion, int[] orden,
                        float[] ajusteY, int rgbBase, Cara cara, float sombra, float ox, float oy, float oz,
                        float escala, int nivelTextura) {
        for (int e : orden) {
            vertice(q, plano, us[e], vs[e], luces[e], BRILLO_OCLUSION[oclusion[e]], rgbBase, cara, sombra,
                    ox, oy, oz, escala, ajusteY[e], nivelTextura);
        }
    }

    /**
     * Orden de las 4 esquinas (0 (u0,v0), 1 (u1,v0), 2 (u1,v1), 3 (u0,v1))
     * para emitir el quad: antihorario desde afuera, y empezando de modo que
     * la diagonal que Minecraft usa para partirlo en triángulos (vértice 0 a
     * 2 del quad) una las esquinas MÁS claras. Si no, un rincón oscuro se
     * estira en una franja a lo largo de la diagonal (el "anisotropía" típico
     * de la oclusión por vértice en quads).
     */
    static int[] ordenEsquinas(boolean directo, int[] oclusion) {
        int[] orden = directo ? new int[]{0, 1, 2, 3} : new int[]{3, 2, 1, 0};
        int diagonalActual = oclusion[orden[0]] + oclusion[orden[2]];
        int diagonalOtra = oclusion[orden[1]] + oclusion[orden[3]];
        if (diagonalOtra > diagonalActual) {
            orden = new int[]{orden[1], orden[2], orden[3], orden[0]}; // rotar conserva el sentido
        }
        return orden;
    }

    /** true si el contorno (u0,v0) (u1,v0) (u1,v1) (u0,v1) ya es antihorario visto desde afuera. */
    static boolean antihorarioDirecto(Quad.Eje eje, boolean positivo) {
        return eje == Quad.Eje.Y ? !positivo : positivo;
    }

    private void vertice(Quad q, float plano, int u, int v, int luz, float brilloOclusion, int rgbBase, Cara cara,
                         float sombra, float ox, float oy, float oz, float escala, float ajusteY, int nivelTextura) {
        Quad.Eje eje = q.eje();
        float x, y, z;
        switch (eje) {
            case X -> { x = plano; y = u; z = v; }
            case Y -> { x = u; y = plano; z = v; }
            default -> { x = u; y = v; z = plano; }
        }
        asegurarCapacidad();
        int i = vertices * 3;
        posiciones[i] = ox + x * escala;
        posiciones[i + 1] = oy + y * escala + ajusteY;
        posiciones[i + 2] = oz + z * escala;
        colores[vertices] = color(rgbBase, sombra * brilloOclusion, luz);
        coloresBase[vertices] = color(rgbBase, brilloOclusion, 15);
        luces[vertices] = (byte) luz;
        lucesBloque[vertices] = (byte) q.voxelRepresentativo().luzBloque();
        sprites[vertices] = cara == null ? 0 : cara.sprite();
        int indiceCara = eje.ordinal() * 2 + (q.positivo() ? 1 : 0);
        caras[vertices] = (byte) indiceCara;
        niveles[vertices] = (byte) nivelTextura;
        SuperVoxel representativo = q.voxelRepresentativo();
        estados[vertices] = representativo.idEstado();
        int grupo = separarAgua && representativo.material() == SuperVoxel.Material.AGUA ? GRUPO_AGUA : indiceCara;
        grupos[vertices] = (byte) grupo;
        float coordenadaPlano = posiciones[i + eje.ordinal()];
        verticesPorCara[grupo]++;
        planoMin[grupo] = Math.min(planoMin[grupo], coordenadaPlano);
        planoMax[grupo] = Math.max(planoMax[grupo], coordenadaPlano);
        vertices++;
    }

    /** log2 de una escala potencia de 2 (bloques por vóxel), entre 0 y 31; otras escalas cuentan como 0. */
    static int nivelDeEscala(float escala) {
        int e = Math.round(escala);
        return e > 0 && e == escala && Integer.bitCount(e) == 1 ? Integer.numberOfTrailingZeros(e) : 0;
    }

    /** log2 del tamaño del vóxel del vértice i, en bloques. */
    public int nivel(int i) {
        return niveles[i];
    }

    static float sombraDeCara(Quad.Eje eje, boolean positivo) {
        return switch (eje) {
            case Y -> positivo ? 1.0f : 0.5f;
            case Z -> 0.8f;
            case X -> 0.6f;
        };
    }

    static int color(SuperVoxel v, float sombra, int luz) {
        return color(((v.r() & 0xFF) << 16) | ((v.g() & 0xFF) << 8) | (v.b() & 0xFF), sombra, luz);
    }

    static int color(int rgb, float sombra, int luz) {
        float factor = sombra * (LUZ_MINIMA + (1 - LUZ_MINIMA) * luz / 15f);
        int r = Math.round(((rgb >> 16) & 0xFF) * factor);
        int g = Math.round(((rgb >> 8) & 0xFF) * factor);
        int b = Math.round((rgb & 0xFF) * factor);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private void asegurarCapacidad() {
        if (vertices == colores.length) {
            colores = Arrays.copyOf(colores, colores.length * 2);
            coloresBase = Arrays.copyOf(coloresBase, coloresBase.length * 2);
            luces = Arrays.copyOf(luces, luces.length * 2);
            lucesBloque = Arrays.copyOf(lucesBloque, lucesBloque.length * 2);
            posiciones = Arrays.copyOf(posiciones, posiciones.length * 2);
            sprites = Arrays.copyOf(sprites, sprites.length * 2);
            caras = Arrays.copyOf(caras, caras.length * 2);
            niveles = Arrays.copyOf(niveles, niveles.length * 2);
            estados = Arrays.copyOf(estados, estados.length * 2);
            grupos = Arrays.copyOf(grupos, grupos.length * 2);
        }
    }

    public int vertices() {
        return vertices;
    }

    public float x(int i) {
        return posiciones[i * 3];
    }

    public float y(int i) {
        return posiciones[i * 3 + 1];
    }

    public float z(int i) {
        return posiciones[i * 3 + 2];
    }

    /** ARGB. */
    public int color(int i) {
        return colores[i];
    }

    /** true si el vértice i lleva textura. */
    public boolean texturizado(int i) {
        return sprites[i] != 0;
    }

    public int sprite(int i) {
        return sprites[i];
    }

    /** 0 -X, 1 +X, 2 -Y, 3 +Y, 4 -Z, 5 +Z. */
    public int cara(int i) {
        return caras[i];
    }

    /**
     * Escribe todos los vértices en el formato compacto
     * ({@link #BYTES_COMPACTO} bytes c/u, little-endian como espera la GPU):
     * x, y, z (short, bloques relativos a la celda), sprite (bits 0-13) y luz
     * de bloque 0-3 (bits 14-15: el shader deja iluminado de noche lo que
     * tiene antorchas o lava),
     * R, G, B (ya sombreados) y en el byte de alfa la cara (bits 0-2) y el
     * log2 del tamaño del vóxel (bits 3-7: el shader repite la franja de
     * pasto una vez por vóxel, no una por bloque).
     *
     * @throws IllegalStateException si una posición no es entera o no entra en un short
     */
    public void escribirCompacto(ByteBuffer destino) {
        escribirCompacto(destino, -1);
    }

    /**
     * Como {@link #escribirCompacto(ByteBuffer)}, solo los vértices de una
     * cara (0-5); -1 = todos.
     */
    public void escribirCompacto(ByteBuffer destino, int soloCara) {
        ByteBuffer b = destino.order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < vertices; i++) {
            if (!enGrupo(i, soloCara)) {
                continue;
            }
            b.putShort(aShort(posiciones[i * 3]));
            b.putShort(aShort(posiciones[i * 3 + 1]));
            b.putShort(aShort(posiciones[i * 3 + 2]));
            b.putShort((short) ((sprites[i] & MAX_SPRITE) | lucesBloque[i] << 14));
            int c = colores[i];
            b.put((byte) (c >> 16)).put((byte) (c >> 8)).put((byte) c).put((byte) (caras[i] | niveles[i] << 3));
        }
    }

    /**
     * Escribe los vértices (de una cara 0-5, o todos con -1) en el formato de
     * bloque vanilla ({@link #BYTES_BLOQUE} bytes c/u, little-endian). El color
     * va sin luz ni sombra por cara (el shaderpack ilumina con la normal y el
     * lightmap); la UV es fija (se dibuja con una textura blanca: el color ya
     * trae el promedio de la textura del bloque).
     */
    public void escribirBloque(ByteBuffer destino, int soloCara) {
        escribirBloque(destino, soloCara, false);
    }

    /** @param extendidoIris true: {@link #BYTES_BLOQUE_IRIS} bytes por vértice (formato de Iris) */
    public void escribirBloque(ByteBuffer destino, int soloCara, boolean extendidoIris) {
        ByteBuffer b = destino.order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < vertices; i++) {
            if (!enGrupo(i, soloCara)) {
                continue;
            }
            b.putFloat(posiciones[i * 3]).putFloat(posiciones[i * 3 + 1]).putFloat(posiciones[i * 3 + 2]);
            int c = coloresBase[i];
            b.put((byte) (c >> 16)).put((byte) (c >> 8)).put((byte) c).put((byte) 0xFF);
            b.putFloat(0.5f).putFloat(0.5f);
            // Lightmap como vanilla: (luz de bloque × 16, luz de cielo × 16); la horneada va como cielo
            // y la de bloque cuantizada vuelve a 0-15.
            b.putShort((short) (lucesBloque[i] * 5 * 16)).putShort((short) (luces[i] * 16));
            int cara = caras[i];
            int signo = (cara & 1) == 1 ? 127 : -127;
            int eje = cara >> 1;
            b.put((byte) (eje == 0 ? signo : 0)).put((byte) (eje == 1 ? signo : 0)).put((byte) (eje == 2 ? signo : 0))
                    .put((byte) 0);
            if (extendidoIris) {
                // mc_Entity -1: ningún bloque especial del block.properties del pack.
                b.putShort((short) -1).putShort((short) -1);
                b.putFloat(0.5f).putFloat(0.5f);
                // Tangente sobre el plano de la cara (X para caras Y/Z, Z para caras X).
                b.put((byte) (eje == 0 ? 0 : 127)).put((byte) 0).put((byte) (eje == 0 ? 127 : 0)).put((byte) 127);
                b.put((byte) 0).put((byte) 0).put((byte) 0).put((byte) 0);
            }
        }
    }

    /** Vértices del grupo (cara 0-5 o {@link #GRUPO_AGUA}). */
    public int verticesDeCara(int cara) {
        return verticesPorCara[cara];
    }

    /** Vértices fuera de los grupos del agua y de las cruces (todos si no se separan). */
    public int verticesOpacos() {
        return vertices - verticesPorCara[GRUPO_AGUA] - verticesPorCara[GRUPO_CRUZ];
    }

    /** -1: todos menos el agua separada y las cruces; si no, ese grupo. */
    private boolean enGrupo(int i, int grupo) {
        return grupo < 0 ? grupos[i] < CARAS : grupos[i] == grupo;
    }

    /**
     * Escribe los vértices de un grupo (-1: todos menos el agua separada) en el formato
     * del contrato Voxy ({@link #BYTES_VOXY} bytes c/u): como {@link #escribirCompacto}
     * con el color sin sombra por cara ni luz horneada, más el int de estado, luz de
     * cielo y agua.
     */
    public void escribirVoxy(ByteBuffer destino, int grupo) {
        ByteBuffer b = destino.order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < vertices; i++) {
            if (!enGrupo(i, grupo)) {
                continue;
            }
            b.putShort(aShort(posiciones[i * 3]));
            b.putShort(aShort(posiciones[i * 3 + 1]));
            b.putShort(aShort(posiciones[i * 3 + 2]));
            b.putShort((short) ((sprites[i] & MAX_SPRITE) | lucesBloque[i] << 14));
            int c = coloresBase[i];
            b.put((byte) (c >> 16)).put((byte) (c >> 8)).put((byte) c).put((byte) (caras[i] | niveles[i] << 3));
            b.putInt(extraVoxy(estados[i], luces[i], grupos[i] == GRUPO_AGUA));
        }
    }

    /** El int extra del formato Voxy: estado (16 bits), luz de cielo (4) y agua (1). */
    static int extraVoxy(int idEstado, int luzCielo, boolean agua) {
        return (idEstado & 0xFFFF) | (luzCielo & 0xF) << 16 | (agua ? 1 << 20 : 0);
    }

    /** Plano más bajo de las caras de ese índice, sobre su eje (bloques de la celda). */
    public float planoMin(int cara) {
        return planoMin[cara];
    }

    public float planoMax(int cara) {
        return planoMax[cara];
    }

    /**
     * true si alguna cara del grupo puede mirar hacia la cámara: una cara +X
     * en el plano p solo se ve con la cámara en x > p (y al revés las -X).
     * Si ni el plano más favorable cumple, el grupo entero se saltea sin
     * mandarlo a la GPU.
     *
     * @param camara coordenada de la cámara sobre el eje de la cara, relativa a la celda
     */
    public static boolean caraVisible(int cara, double camara, float planoMin, float planoMax) {
        return (cara & 1) == 1 ? camara > planoMin : camara < planoMax;
    }

    private static short aShort(float f) {
        int v = (int) f;
        if (v != f || v < Short.MIN_VALUE || v > Short.MAX_VALUE) {
            throw new IllegalStateException("posición fuera del formato compacto: " + f);
        }
        return (short) v;
    }

    /**
     * Los {@link #TEXELES_POR_SPRITE} texeles RGBA8 de un sprite en la tabla
     * del shader, como enteros con R en el byte bajo (el orden de
     * {@code NativeImage#setPixelRGBA}): (x, y) y (ancho, alto) en píxeles
     * del atlas, 16 bits cada uno, el color promedio de la textura y el
     * índice del sprite "de abajo" (0 = ninguno): en los costados con franja
     * (pasto, nieve) la textura que va debajo de la fila de arriba en un vóxel
     * grande.
     */
    public static int[] texelesSprite(int x, int y, int ancho, int alto, int promedioRgb, int spriteAbajo) {
        return new int[] {
                (x & 0xFFFF) | (y & 0xFFFF) << 16,
                (ancho & 0xFFFF) | (alto & 0xFFFF) << 16,
                ((promedioRgb >> 16) & 0xFF) | (promedioRgb & 0xFF00) | (promedioRgb & 0xFF) << 16 | 0xFF000000,
                (spriteAbajo & 0xFFFF) | 0xFF000000
        };
    }
}
