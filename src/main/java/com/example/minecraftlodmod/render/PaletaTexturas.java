package com.example.minecraftlodmod.render;

import com.example.minecraftlodmod.generation.ColorTextura;
import com.example.minecraftlodmod.generation.ColoresBloque;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.FoliageColor;
import net.minecraft.world.level.GrassColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.TextureAtlasStitchedEvent;
import org.slf4j.Logger;

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

    private volatile boolean pendiente = true;

    /** Bus del mod: el atlas cambió (arranque, F3+T, resource pack). */
    public void alCoserAtlas(TextureAtlasStitchedEvent evento) {
        pendiente = true;
    }

    /** Bus de NeoForge. */
    public void alTerminarTick(ClientTickEvent.Post evento) {
        if (!pendiente || Minecraft.getInstance().getOverlay() != null) {
            return; // durante la pantalla de carga los recursos todavía no están listos
        }
        pendiente = false;
        long inicio = System.nanoTime();
        ColoresBloque.Paleta paleta = calcular();
        ColoresBloque.publicar(paleta);
        long conColor = Arrays.stream(paleta.rgbBase()).filter(c -> c >= 0).count();
        LOG.info("LOD: paleta de texturas lista ({} estados con color) en {} ms", conColor,
                (System.nanoTime() - inicio) / 1_000_000);
    }

    private static ColoresBloque.Paleta calcular() {
        Minecraft mc = Minecraft.getInstance();
        BlockColors colores = mc.getBlockColors();
        int total = Block.BLOCK_STATE_REGISTRY.size();
        int[] base = new int[total];
        byte[] tinte = new byte[total];
        int[] fijo = new int[total];
        Arrays.fill(base, -1);
        Map<TextureAtlasSprite, Integer> promedios = new HashMap<>();
        RandomSource azar = RandomSource.create(42);

        for (BlockState estado : Block.BLOCK_STATE_REGISTRY) {
            int id = Block.BLOCK_STATE_REGISTRY.getId(estado);
            if (id < 0 || id >= total || estado.isAir()) {
                continue;
            }
            try {
                BakedModel modelo = mc.getBlockRenderer().getBlockModel(estado);
                BakedQuad quad = primerQuad(modelo, estado, azar);
                TextureAtlasSprite sprite = quad != null ? quad.getSprite() : modelo.getParticleIcon();
                int promedio = promedios.computeIfAbsent(sprite, PaletaTexturas::promedio);
                if (promedio < 0) {
                    continue;
                }
                base[id] = promedio;
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
        return new ColoresBloque.Paleta(base, tinte, fijo);
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
        return ColorTextura.promedio(pixeles);
    }
}
