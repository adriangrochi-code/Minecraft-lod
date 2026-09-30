package com.example.minecraftlodmod.render;

import com.example.minecraftlodmod.generation.ColorTextura;
import com.example.minecraftlodmod.generation.ColoresBloque;
import com.example.minecraftlodmod.generation.GreedyMesher;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import com.example.minecraftlodmod.generation.Quad;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.FoliageColor;
import net.minecraft.world.level.GrassColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.TextureAtlasStitchedEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Calcula la {@link ColoresBloque.Paleta} con las texturas reales cargadas
 * (incluye resource packs y bloques de mods). Solo cliente.
 *
 * Por estado de bloque: la textura de su cara de ARRIBA (la que más se ve
 * del terreno lejano; si no tiene, cualquiera de su modelo, y si no, la de
 * partículas), promediada con {@link ColorTextura#promedio}, más el tipo de
 * tinte, deducido de {@link BlockColors} como lo aplica vanilla.
 *
 * Se recalcula en el hilo de render en el primer tick después de cada carga
 * de recursos ({@link TextureAtlasStitchedEvent}): leer modelos e imágenes
 * mientras se recargan no es seguro, y los ~27k estados vanilla tardan
 * poco porque el promedio se cachea por textura.
 */
public final class PaletaTexturas {

    private static final Logger LOG = LogUtils.getLogger();

    /**
     * Tabla de sprites que lee el shader del LOD (ver
     * {@link GeometriaLod#texelesSprite}): el vértice compacto solo lleva el
     * índice del sprite, el rectángulo en el atlas y el promedio salen de acá.
     */
    public static final ResourceLocation TABLA_SPRITES =
            ResourceLocation.fromNamespaceAndPath(com.example.minecraftlodmod.MinecraftLodMod.MOD_ID, "tabla_sprites");

    /**
     * Atlas propio del LOD ({@link AtlasLod}): las texturas en uso en teselas
     * del mismo tamaño, con los huecos del follaje oscurecidos, más los
     * modelos horneados. La tabla apunta a sus teselas, no al atlas de bloques.
     */
    public static final ResourceLocation ATLAS =
            ResourceLocation.fromNamespaceAndPath(com.example.minecraftlodmod.MinecraftLodMod.MOD_ID, "atlas_lod");

    private volatile boolean pendiente = true;
    /** Valor de "texturas como terreno" con que se armó la tabla: si cambia, se rearma. */
    private boolean comoTerrenoCalculado;

    /** Texturas por estado de bloque para el render (null hasta la primera carga). */
    private static volatile TablaTexturas tabla;

    public static TablaTexturas tabla() {
        return tabla;
    }

    /**
     * Cara de arriba y de costado (también usada abajo) de cada estado de
     * bloque, con su rectángulo en el atlas de bloques ACTIVO: cambiar de
     * paquete de texturas recalcula la tabla y el LOD se redibuja con él.
     */
    public record TablaTexturas(GeometriaLod.Cara[] arriba, GeometriaLod.Cara[] costado)
            implements GeometriaLod.Texturas {
        @Override
        public GeometriaLod.Cara cara(int idEstado, Quad.Eje eje, boolean positivo) {
            if (idEstado < 0 || idEstado >= arriba.length) {
                return null;
            }
            return eje == Quad.Eje.Y && positivo ? arriba[idEstado] : costado[idEstado];
        }
    }

    /** Bus del mod: el atlas cambió (arranque, F3+T, resource pack). */
    public void alCoserAtlas(TextureAtlasStitchedEvent evento) {
        pendiente = true;
    }

    /** Bus de NeoForge. */
    public void alTerminarTick(ClientTickEvent.Post evento) {
        boolean comoTerreno = com.example.minecraftlodmod.config.ConfigLod.CLIENTE.texturasComoTerreno.get();
        if (comoTerreno != comoTerrenoCalculado) {
            pendiente = true;
        }
        if (!pendiente || Minecraft.getInstance().getOverlay() != null) {
            return; // durante la pantalla de carga los recursos todavía no están listos
        }
        pendiente = false;
        comoTerrenoCalculado = comoTerreno;
        long inicio = System.nanoTime();
        ColoresBloque.Paleta paleta = calcular(comoTerreno);
        ColoresBloque.publicar(paleta);
        BlockState nieve = Blocks.SNOW.defaultBlockState();
        GreedyMesher.definirNieve(ColoresBloque.rgb(nieve, null, 0, 0), Block.getId(nieve));
        RenderLod.texturasCambiaron();
        long conColor = Arrays.stream(paleta.rgbBase()).filter(c -> c >= 0).count();
        LOG.info("LOD: paleta de texturas lista ({} estados con color) en {} ms", conColor,
                (System.nanoTime() - inicio) / 1_000_000);
    }

    /**
     * @param comoTerreno buscar los costados con franja (pasto, nieve) y su
     *                    textura de abajo, para los vóxeles grandes (ver
     *                    {@link ColorTextura#tieneFranja})
     */
    private static ColoresBloque.Paleta calcular(boolean comoTerreno) {
        Minecraft mc = Minecraft.getInstance();
        BlockColors colores = mc.getBlockColors();
        int total = Block.BLOCK_STATE_REGISTRY.size();
        int[] base = new int[total];
        byte[] tinte = new byte[total];
        int[] fijo = new int[total];
        Arrays.fill(base, -1);
        GeometriaLod.Cara[] carasArriba = new GeometriaLod.Cara[total];
        GeometriaLod.Cara[] carasCostado = new GeometriaLod.Cara[total];
        Map<TextureAtlasSprite, Integer> promedios = new HashMap<>();
        Map<TextureAtlasSprite, Integer> indices = new HashMap<>();
        Map<TextureAtlasSprite, TextureAtlasSprite> abajoDe = new HashMap<>();
        Map<TextureAtlasSprite, Boolean> revisados = new HashMap<>();
        List<TextureAtlasSprite> sprites = new ArrayList<>();
        sprites.add(null); // el índice 0 es "sin textura"
        RandomSource azar = RandomSource.create(42);
        List<Horneo> aHornear = new ArrayList<>();

        for (BlockState estado : Block.BLOCK_STATE_REGISTRY) {
            int id = Block.BLOCK_STATE_REGISTRY.getId(estado);
            if (id < 0 || id >= total || estado.isAir()) {
                continue;
            }
            try {
                BakedModel modelo = mc.getBlockRenderer().getBlockModel(estado);
                if (!cuboSimple(modelo, estado, azar)) {
                    aHornear.add(new Horneo(id, estado, modelo));
                }
                BakedQuad quad = primerQuad(modelo, estado, azar);
                TextureAtlasSprite sprite = quad != null ? quad.getSprite() : modelo.getParticleIcon();
                int promedio = promedios.computeIfAbsent(sprite, PaletaTexturas::promedio);
                if (promedio < 0) {
                    continue;
                }
                base[id] = promedio;
                carasArriba[id] = cara(indice(sprite, indices, sprites), promedio, true);
                BakedQuad quadCostado = quadCostado(modelo, estado, azar);
                TextureAtlasSprite spriteCostado = quadCostado != null ? quadCostado.getSprite() : sprite;
                int promedioCostado = promedios.computeIfAbsent(spriteCostado, PaletaTexturas::promedio);
                // El color del vóxel (textura de arriba × tinte) vale para el costado solo
                // si es la misma textura o también se tiñe; si no (tierra bajo el pasto,
                // corteza de un tronco), el costado usa el promedio de su propia textura.
                boolean costadoUsaColor = spriteCostado == sprite || (quadCostado != null && quadCostado.isTinted());
                carasCostado[id] = promedioCostado < 0 ? carasArriba[id]
                        : cara(indice(spriteCostado, indices, sprites), promedioCostado, costadoUsaColor);
                if (comoTerreno && promedioCostado >= 0 && !revisados.containsKey(spriteCostado)) {
                    BakedQuad quadAbajo = quadDe(modelo, estado, azar, Direction.DOWN);
                    TextureAtlasSprite spriteAbajo = quadAbajo != null ? quadAbajo.getSprite() : null;
                    boolean franja = spriteAbajo != null && spriteAbajo != spriteCostado
                            && ColorTextura.tieneFranja(pixeles(spriteCostado), spriteCostado.contents().width(),
                            spriteCostado.contents().height(), promedios.computeIfAbsent(spriteAbajo, PaletaTexturas::promedio));
                    revisados.put(spriteCostado, franja);
                    if (franja) {
                        abajoDe.put(spriteCostado, spriteAbajo);
                        indice(spriteAbajo, indices, sprites);
                    }
                }
                // Por tipo de fluido y no por tag: en el menú principal los tags
                // todavía no están cargados (llegan al entrar a un mundo).
                boolean esAgua = estado.getBlock() instanceof LiquidBlock
                        && estado.getFluidState().getType().isSame(Fluids.WATER);
                if (esAgua) {
                    tinte[id] = (byte) ColoresBloque.Tinte.AGUA.ordinal();
                } else if (quad != null && quad.isTinted()) {
                    int porDefecto = colorSinMundo(colores, estado, quad.getTintIndex());
                    ColoresBloque.Tinte t = porDefecto == FoliageColor.getDefaultColor() ? ColoresBloque.Tinte.FOLLAJE
                            : porDefecto == GrassColor.getDefaultColor() ? ColoresBloque.Tinte.PASTO
                            : porDefecto == -1 ? ColoresBloque.Tinte.NINGUNO
                            : ColoresBloque.Tinte.FIJO;
                    tinte[id] = (byte) t.ordinal();
                    fijo[id] = porDefecto & 0xFFFFFF;
                }
            } catch (RuntimeException e) {
                // Modelo de un mod que no tolera consultas fuera del mundo: queda con color de mapa.
                base[id] = -1;
            }
        }
        int tesela = AtlasLod.tamanoTesela(sprites.stream().skip(1)
                .mapToInt(sp -> sp.contents().width()).max().orElse(16));
        List<int[]> horneadas = new ArrayList<>();
        List<Integer> promediosHorneadas = new ArrayList<>();
        hornearModelos(aHornear, tesela, sprites.size(), horneadas, promediosHorneadas,
                base, carasArriba, carasCostado);
        subirAtlas(mc, tesela, sprites, promedios, abajoDe, indices, horneadas, promediosHorneadas);
        if (comoTerreno) {
            LOG.info("LOD: {} texturas de costado con franja (pasto, nieve...)", abajoDe.size());
            // La cara del costado también lleva la de abajo: con la superficie a la altura real,
            // GeometriaLod parte ahí el costado (franja arriba, tierra abajo).
            for (int id = 0; id < total; id++) {
                GeometriaLod.Cara c = carasCostado[id];
                if (c == null || c.sprite() <= 0 || c.sprite() >= sprites.size()) {
                    continue;
                }
                TextureAtlasSprite abajo = abajoDe.get(sprites.get(c.sprite()));
                Integer indiceAbajo = abajo == null ? null : indices.get(abajo);
                if (indiceAbajo != null && indiceAbajo <= 0xFFFF) {
                    carasCostado[id] = new GeometriaLod.Cara(c.sprite(), c.promedioRgb(), c.usaColorDelVoxel(),
                            indiceAbajo, promedios.getOrDefault(abajo, c.promedioRgb()));
                }
            }
        }
        tabla = new TablaTexturas(carasArriba, carasCostado);
        return new ColoresBloque.Paleta(base, tinte, fijo);
    }

    private static GeometriaLod.Cara cara(int indice, int promedio, boolean usaColorDelVoxel) {
        // Más de 65535 sprites distintos no entran en el vértice compacto: esos van con color plano.
        return new GeometriaLod.Cara(indice > 0xFFFF ? 0 : indice, promedio, usaColorDelVoxel);
    }

    private static int indice(TextureAtlasSprite sprite, Map<TextureAtlasSprite, Integer> indices,
                              List<TextureAtlasSprite> sprites) {
        return indices.computeIfAbsent(sprite, s -> {
            sprites.add(s);
            return sprites.size() - 1;
        });
    }

    /** Un estado de bloque cuyo modelo no es un cubo simple (ver {@link #cuboSimple}). */
    private record Horneo(int id, BlockState estado, BakedModel modelo) {
    }

    /**
     * ¿Seis caras enteras y nada más? Entonces su textura va tal cual (con la
     * franja de pasto, el tinte por cara...). Si no, se hornea.
     */
    private static boolean cuboSimple(BakedModel modelo, BlockState estado, RandomSource azar) {
        azar.setSeed(42);
        if (!modelo.getQuads(estado, null, azar).isEmpty()) {
            return false;
        }
        for (Direction lado : Direction.values()) {
            BakedQuad q = quadDe(modelo, estado, azar, lado);
            if (q == null || !AtlasLod.caraCompleta(posiciones(q), lado.getAxis().ordinal(),
                    lado.getAxisDirection() == Direction.AxisDirection.POSITIVE)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Hornea desde arriba y desde un costado los modelos que no son cubos
     * (idea de Voxy, ver {@link AtlasLod}) y los pone en la tabla en lugar de
     * la textura suelta de una cara. Teselas repetidas (mismo modelo en otra
     * orientación que se ve igual) se comparten.
     */
    private static void hornearModelos(List<Horneo> aHornear, int tesela, int primerIndice,
                                       List<int[]> horneadas, List<Integer> promediosHorneadas, int[] base,
                                       GeometriaLod.Cara[] carasArriba, GeometriaLod.Cara[] carasCostado) {
        RandomSource azar = RandomSource.create(42);
        Map<java.nio.IntBuffer, Integer> repetidas = new HashMap<>();
        Map<TextureAtlasSprite, int[]> pixelesPorSprite = new HashMap<>();
        Map<BakedModel, GeometriaLod.Cara[]> porModelo = new HashMap<>();
        for (Horneo h : aHornear) {
            try {
                GeometriaLod.Cara[] caras = porModelo.get(h.modelo());
                if (caras == null) {
                    List<AtlasLod.QuadModelo> quads = new ArrayList<>();
                    boolean tenido = false;
                    for (Direction lado : LADOS_Y_SUELTOS) {
                        azar.setSeed(42);
                        for (BakedQuad q : h.modelo().getQuads(h.estado(), lado, azar)) {
                            quads.add(quadModelo(q, pixelesPorSprite));
                            tenido |= q.isTinted();
                        }
                    }
                    int[] arriba = AtlasLod.hornear(quads, AtlasLod.Vista.ARRIBA, tesela);
                    int[] costado = AtlasLod.hornear(quads, AtlasLod.Vista.COSTADO, tesela);
                    caras = new GeometriaLod.Cara[] {
                            teselaHorneada(arriba, true, tesela, primerIndice, repetidas, horneadas, promediosHorneadas),
                            teselaHorneada(costado, tenido, tesela, primerIndice, repetidas, horneadas, promediosHorneadas),
                            null};
                    if (!AtlasLod.vacia(arriba)) {
                        caras[2] = new GeometriaLod.Cara(0, ColorTextura.promedio(arriba), true);
                    }
                    porModelo.put(h.modelo(), caras);
                }
                if (caras[0] != null && base[h.id()] >= 0) {
                    carasArriba[h.id()] = caras[0];
                    // El color del vóxel pasa a ser el de lo que se ve desde arriba (un cerco: el poste, no el aire).
                    if (caras[2] != null) {
                        base[h.id()] = caras[2].promedioRgb();
                    }
                }
                if (caras[1] != null && base[h.id()] >= 0) {
                    carasCostado[h.id()] = caras[1];
                }
            } catch (RuntimeException e) {
                // Modelo de un mod que no se deja leer fuera del mundo: queda con la textura de su cara.
            }
        }
        LOG.info("LOD: {} modelos horneados en {} teselas", porModelo.size(), horneadas.size());
    }

    private static final Direction[] LADOS_Y_SUELTOS = {
            Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, null};

    private static GeometriaLod.Cara teselaHorneada(int[] cruda, boolean usaColorDelVoxel, int tesela, int primerIndice,
                                                    Map<java.nio.IntBuffer, Integer> repetidas,
                                                    List<int[]> horneadas, List<Integer> promediosHorneadas) {
        if (AtlasLod.vacia(cruda)) {
            return null;
        }
        int promedio = ColorTextura.promedio(cruda);
        // Huecos con el color plano (no oscurecidos): el aire de un cerco o la mitad vacía de una losa no es sombra.
        int[] opaca = AtlasLod.rellenarHuecos(cruda, promedio, false);
        java.nio.IntBuffer clave = java.nio.IntBuffer.wrap(opaca);
        Integer indice = repetidas.get(clave);
        if (indice == null) {
            indice = primerIndice + horneadas.size();
            horneadas.add(opaca);
            promediosHorneadas.add(AtlasLod.promedioTesela(opaca));
            repetidas.put(clave, indice);
        }
        return cara(indice, promedio, usaColorDelVoxel);
    }

    private static AtlasLod.QuadModelo quadModelo(BakedQuad q, Map<TextureAtlasSprite, int[]> pixelesPorSprite) {
        TextureAtlasSprite s = q.getSprite();
        int[] v = q.getVertices();
        int paso = v.length / 4;
        float[] uv = new float[8];
        float du = s.getU1() - s.getU0(), dv = s.getV1() - s.getV0();
        for (int i = 0; i < 4; i++) {
            uv[i * 2] = du == 0 ? 0 : (Float.intBitsToFloat(v[i * paso + 4]) - s.getU0()) / du;
            uv[i * 2 + 1] = dv == 0 ? 0 : (Float.intBitsToFloat(v[i * paso + 5]) - s.getV0()) / dv;
        }
        return new AtlasLod.QuadModelo(posiciones(q), uv, pixelesPorSprite.computeIfAbsent(s, PaletaTexturas::pixeles),
                s.contents().width(), s.contents().height());
    }

    /** x, y, z de los 4 vértices del quad (formato de bloque: posición en los 3 primeros enteros). */
    private static float[] posiciones(BakedQuad q) {
        int[] v = q.getVertices();
        int paso = v.length / 4;
        float[] pos = new float[12];
        for (int i = 0; i < 4; i++) {
            for (int c = 0; c < 3; c++) {
                pos[i * 3 + c] = Float.intBitsToFloat(v[i * paso + c]);
            }
        }
        return pos;
    }

    /**
     * Hilo de render: arma el atlas propio del LOD (ver {@link AtlasLod}) con
     * los sprites en uso y los modelos horneados, sus mipmaps, y la tabla que
     * lee el shader con el rectángulo de cada tesela.
     */
    private static void subirAtlas(Minecraft mc, int tesela, List<TextureAtlasSprite> sprites,
                                   Map<TextureAtlasSprite, Integer> promedios,
                                   Map<TextureAtlasSprite, TextureAtlasSprite> abajoDe,
                                   Map<TextureAtlasSprite, Integer> indices,
                                   List<int[]> horneadas, List<Integer> promediosHorneadas) {
        int cantidad = Math.min(sprites.size() + horneadas.size(), 0x10000);
        int porFila = AtlasLod.teselasPorFila(cantidad);
        int filasAtlas = Math.max(1, (cantidad + porFila - 1) / porFila);
        NativeImage atlas = new NativeImage(porFila * tesela, filasAtlas * tesela, true);
        int ancho = GeometriaLod.SPRITES_POR_FILA * GeometriaLod.TEXELES_POR_SPRITE;
        int filas = Math.max(1, (cantidad + GeometriaLod.SPRITES_POR_FILA - 1) / GeometriaLod.SPRITES_POR_FILA);
        NativeImage tablaImagen = new NativeImage(ancho, filas, true);
        int follaje = 0;
        for (int i = 1; i < cantidad; i++) {
            int[] opaca;
            int indiceAbajo = 0;
            if (i < sprites.size()) {
                TextureAtlasSprite s = sprites.get(i);
                int[] cruda = AtlasLod.escalar(pixeles(s), s.contents().width(), s.contents().height(), tesela);
                boolean esFollaje = AtlasLod.esFollaje(cruda);
                follaje += esFollaje ? 1 : 0;
                opaca = AtlasLod.rellenarHuecos(cruda, Math.max(0, promedios.getOrDefault(s, 0)), esFollaje);
                TextureAtlasSprite abajo = abajoDe.get(s);
                indiceAbajo = abajo == null ? 0 : indices.getOrDefault(abajo, 0);
            } else {
                opaca = horneadas.get(i - sprites.size());
            }
            int x0 = (i % porFila) * tesela, y0 = (i / porFila) * tesela;
            for (int y = 0; y < tesela; y++) {
                for (int x = 0; x < tesela; x++) {
                    atlas.setPixelRGBA(x0 + x, y0 + y, opaca[y * tesela + x]);
                }
            }
            // El promedio de la tesela ya rellena: de lejos (último mipmap) el color es el del vóxel.
            int[] texeles = GeometriaLod.texelesSprite(x0, y0, tesela, tesela, AtlasLod.promedioTesela(opaca),
                    indiceAbajo < cantidad ? indiceAbajo : 0);
            int x = (i % GeometriaLod.SPRITES_POR_FILA) * GeometriaLod.TEXELES_POR_SPRITE;
            for (int t = 0; t < texeles.length; t++) {
                tablaImagen.setPixelRGBA(x + t, i / GeometriaLod.SPRITES_POR_FILA, texeles[t]);
            }
        }
        LOG.info("LOD: atlas propio de {}x{} ({} teselas de {} px, {} con follaje)", atlas.getWidth(),
                atlas.getHeight(), cantidad - 1, tesela, follaje);
        mc.getTextureManager().register(ATLAS, new TexturaConMipmaps(atlas, AtlasLod.nivelesMipmap(tesela)));
        mc.getTextureManager().register(TABLA_SPRITES, new DynamicTexture(tablaImagen));
    }

    /** Textura con mipmaps propios (DynamicTexture no los tiene). Hilo de render. */
    private static final class TexturaConMipmaps extends net.minecraft.client.renderer.texture.AbstractTexture {
        TexturaConMipmaps(NativeImage base, int niveles) {
            NativeImage[] mips = net.minecraft.client.renderer.texture.MipmapGenerator.generateMipLevels(
                    new NativeImage[] {base}, niveles);
            com.mojang.blaze3d.platform.TextureUtil.prepareImage(getId(), niveles, base.getWidth(), base.getHeight());
            for (int nivel = 0; nivel < mips.length; nivel++) {
                mips[nivel].upload(nivel, 0, 0, false);
                mips[nivel].close();
            }
            setFilter(false, true);
        }

        @Override
        public void load(net.minecraft.server.packs.resources.ResourceManager recursos) {
            // Ya subida en el constructor.
        }
    }

    private static BakedQuad quadCostado(BakedModel modelo, BlockState estado, RandomSource azar) {
        return quadDe(modelo, estado, azar, Direction.NORTH);
    }

    private static BakedQuad quadDe(BakedModel modelo, BlockState estado, RandomSource azar, Direction lado) {
        azar.setSeed(42);
        List<BakedQuad> quads = modelo.getQuads(estado, lado, azar);
        return quads.isEmpty() ? null : quads.get(0);
    }

    private static BakedQuad primerQuad(BakedModel modelo, BlockState estado, RandomSource azar) {
        azar.setSeed(42);
        List<BakedQuad> arriba = modelo.getQuads(estado, Direction.UP, azar);
        if (!arriba.isEmpty()) {
            return arriba.get(0);
        }
        azar.setSeed(42);
        List<BakedQuad> sinCara = modelo.getQuads(estado, null, azar);
        return sinCara.isEmpty() ? null : sinCara.get(0);
    }

    /** Color que da {@link BlockColors} sin mundo: vanilla devuelve el default del pasto o del follaje, o uno fijo. */
    private static int colorSinMundo(BlockColors colores, BlockState estado, int indiceTinte) {
        try {
            return colores.getColor(estado, null, null, indiceTinte);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static int promedio(TextureAtlasSprite sprite) {
        return ColorTextura.promedio(pixeles(sprite));
    }

    private static int[] pixeles(TextureAtlasSprite sprite) {
        NativeImage imagen = sprite.contents().getOriginalImage();
        // Solo el primer cuadro de las texturas animadas (agua, lava, fuego).
        int ancho = sprite.contents().width();
        int alto = sprite.contents().height();
        int[] pixeles = new int[ancho * alto];
        for (int y = 0; y < alto; y++) {
            for (int x = 0; x < ancho; x++) {
                pixeles[y * ancho + x] = imagen.getPixelRGBA(x, y);
            }
        }
        return pixeles;
    }
}
