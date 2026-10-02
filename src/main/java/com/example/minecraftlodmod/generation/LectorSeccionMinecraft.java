package com.example.minecraftlodmod.generation;

import com.example.minecraftlodmod.core.SuperVoxel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.material.MapColor;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.VoxelShape;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link SectionExtractor.LectorSeccion} sobre una sección real del mundo.
 *
 * Se construye con {@link #capturar} en el hilo que es dueño del chunk
 * (hilo del servidor), copiando la paleta de bloques y las capas de luz:
 * la copia es barata (paleta + arrays empaquetados) y deja al lector
 * independiente del chunk, así la extracción corre en el pool de
 * {@code GenerationTaskScheduler} sin carreras contra el juego.
 */
public final class LectorSeccionMinecraft implements SectionExtractor.LectorSeccion, ColoresBloque.FuenteTinte {

    private final PalettedContainer<BlockState> estados;
    /** Estados de la sección de arriba (para cubiertas sobre la fila y=15), o null si es solo aire. */
    private final PalettedContainer<BlockState> estadosArriba;

    /**
     * Cómo se representa un estado de bloque en el LOD, por su FORMA: el
     * LOD dibuja cubos, y dibujar como cubo lo que no lo es (pasto, flores,
     * antorchas, nieve fina) llena el paisaje de "cubitos" que vanilla no
     * tiene.
     */
    enum Forma {
        /** Se dibuja como cubo. */
        NORMAL,
        /** Sin colisión y no es una capa: plantas, flores, antorchas, rieles. Se omite (queda el suelo). */
        DECORACION,
        /** Capa fina que cubre toda la base (nieve, alfombras): pinta la cara de arriba del bloque de abajo. */
        CUBIERTA,
        /** Decoración sumergida (algas, pasto marino): cuenta como agua, sin huecos bajo el agua. */
        SUMERGIDA,
        /**
         * Planta alta y fina ({@link #esCruz}): en el nivel 0 se dibuja como dos planos cruzados
         * con su silueta ({@link SuperVoxel.Material#CRUZ}); también tiñe el suelo como la decoración.
         */
        CRUZ
    }

    /**
     * Plantas que de lejos se reconocen por su silueta alta: caña, bambú y las de dos
     * bloques (pasto alto, helechos grandes, girasoles, lilas, rosales, peonías). Las
     * sumergidas (pasto marino alto) siguen siendo agua.
     */
    public static boolean esCruz(BlockState estado) {
        Block b = estado.getBlock();
        return (b instanceof net.minecraft.world.level.block.DoublePlantBlock
                || b instanceof net.minecraft.world.level.block.SugarCaneBlock
                || b instanceof net.minecraft.world.level.block.BambooStalkBlock)
                && !estado.getFluidState().getType().isSame(Fluids.WATER);
    }

    private static final Map<BlockState, Forma> FORMAS = new ConcurrentHashMap<>();
    private static final BlockState AGUA = Blocks.WATER.defaultBlockState();

    static Forma forma(BlockState estado) {
        return FORMAS.computeIfAbsent(estado, LectorSeccionMinecraft::calcularForma);
    }

    private static Forma calcularForma(BlockState estado) {
        if (estado.isAir() || estado.getBlock() instanceof LiquidBlock) {
            return Forma.NORMAL; // el aire y el agua/lava se resuelven por material
        }
        if (esCruz(estado)) {
            return Forma.CRUZ;
        }
        try {
            VoxelShape forma = estado.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            if (!forma.isEmpty() && forma.max(Direction.Axis.Y) <= 0.25
                    && forma.min(Direction.Axis.X) <= 0.01 && forma.max(Direction.Axis.X) >= 0.99
                    && forma.min(Direction.Axis.Z) <= 0.01 && forma.max(Direction.Axis.Z) >= 0.99) {
                return Forma.CUBIERTA;
            }
            if (estado.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()) {
                return estado.getFluidState().getType().isSame(Fluids.WATER) ? Forma.SUMERGIDA : Forma.DECORACION;
            }
        } catch (RuntimeException e) {
            // Bloque de un mod que necesita el mundo para su forma: como cubo.
        }
        return Forma.NORMAL;
    }
    private final boolean soloAire;
    // Luz de la sección y de la de arriba (la capa de arriba ilumina la cara
    // superior de la fila y=15). Null si el motor de luz no la tiene todavía.
    private final DataLayer cielo, bloque, cieloArriba, bloqueArriba;
    /** Biomas de la sección (grilla 4×4×4, índice (x*4+y)*4+z), para el tinte de {@link ColoresBloque}. */
    private final Biome[] biomas;
    /**
     * Biomas de la sección más dos celdas de 4 bloques alrededor en X y Z,
     * tomadas de los chunks vecinos si están cargados (si no, se repite el
     * borde propio): grilla 8×4×8, índice (x*4+y)*8+z con x, z = celda + 2.
     * Null (tests): tinte por celda, sin mezcla.
     */
    private final Biome[] biomasAmplias;
    /** Tintes mezclados por celda (6×4×6, celdas -1..4), calculados la primera vez que se piden. */
    private ColoresBloque.Tintes[] tintesMezclados;
    private final int origenX, origenZ;

    private LectorSeccionMinecraft(PalettedContainer<BlockState> estados, PalettedContainer<BlockState> estadosArriba,
                                   boolean soloAire,
                                   DataLayer cielo, DataLayer bloque,
                                   DataLayer cieloArriba, DataLayer bloqueArriba,
                                   Biome[] biomas, Biome[] biomasAmplias, int origenX, int origenZ) {
        this.biomas = biomas;
        this.biomasAmplias = biomasAmplias;
        this.origenX = origenX;
        this.origenZ = origenZ;
        this.estados = estados;
        this.estadosArriba = estadosArriba;
        this.soloAire = soloAire;
        this.cielo = cielo;
        this.bloque = bloque;
        this.cieloArriba = cieloArriba;
        this.bloqueArriba = bloqueArriba;
    }

    /** Lector sobre estados ya armados, sin luz ni biomas (cielo abierto): para tests. */
    static LectorSeccionMinecraft deEstados(PalettedContainer<BlockState> estados) {
        return new LectorSeccionMinecraft(estados, null, false, null, null, null, null, new Biome[64], null, 0, 0);
    }

    /** Una sección capturada, con su posición en coordenadas de sección. */
    public record Captura(int seccionX, int seccionY, int seccionZ, LectorSeccionMinecraft lector) {
        public SectionExtractor.SeccionExtraida extraer() {
            return SectionExtractor.extraer(lector, seccionX, seccionY, seccionZ);
        }
    }

    /**
     * Captura todas las secciones no vacías del chunk. Llamar desde el hilo
     * dueño del chunk; el resultado se puede procesar en cualquier hilo.
     * Recorre el rango vertical real del chunk (no asume -64..320).
     */
    public static List<Captura> capturar(Level level, ChunkAccess chunk) {
        LevelChunkSection[] secciones = chunk.getSections();
        List<Captura> capturas = new ArrayList<>(secciones.length);
        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;
        var luzCielo = level.getLightEngine().getLayerListener(LightLayer.SKY);
        var luzBloque = level.getLightEngine().getLayerListener(LightLayer.BLOCK);
        // Vecinos cargados para mezclar el tinte de bioma en el borde (null fuera del hilo principal).
        LevelChunk[] vecinos = new LevelChunk[9];
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 || dz != 0) {
                    vecinos[(dx + 1) * 3 + dz + 1] = level.getChunkSource().getChunkNow(chunkX + dx, chunkZ + dz);
                }
            }
        }

        for (int i = 0; i < secciones.length; i++) {
            LevelChunkSection seccion = secciones[i];
            if (seccion == null || seccion.hasOnlyAir()) {
                continue;
            }
            int seccionY = chunk.getSectionYFromSectionIndex(i);
            SectionPos pos = SectionPos.of(chunkX, seccionY, chunkZ);
            SectionPos arriba = SectionPos.of(chunkX, seccionY + 1, chunkZ);
            LevelChunkSection deArriba = i + 1 < secciones.length ? secciones[i + 1] : null;
            capturas.add(new Captura(chunkX, seccionY, chunkZ, new LectorSeccionMinecraft(
                    seccion.getStates().copy(),
                    deArriba == null || deArriba.hasOnlyAir() ? null : deArriba.getStates().copy(), false,
                    copiar(luzCielo.getDataLayerData(pos)),
                    copiar(luzBloque.getDataLayerData(pos)),
                    copiar(luzCielo.getDataLayerData(arriba)),
                    copiar(luzBloque.getDataLayerData(arriba)),
                    copiarBiomas(seccion), biomasAmplias(seccion, vecinos, i), chunkX * 16, chunkZ * 16)));
        }
        return capturas;
    }

    /** 64 referencias: más barato que copiar el contenedor, y {@code recreate()} lo crea vacío. */
    private static Biome[] copiarBiomas(LevelChunkSection seccion) {
        Biome[] biomas = new Biome[64];
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 4; z++) {
                    biomas[(x * 4 + y) * 4 + z] = seccion.getNoiseBiome(x, y, z).value();
                }
            }
        }
        return biomas;
    }

    /** Ver {@link #biomasAmplias}: celdas -2..5 en X y Z. */
    private static Biome[] biomasAmplias(LevelChunkSection propia, LevelChunk[] vecinos, int indiceSeccion) {
        Biome[] amplias = new Biome[8 * 4 * 8];
        for (int cx = -2; cx < 6; cx++) {
            for (int cz = -2; cz < 6; cz++) {
                int dx = Math.floorDiv(cx, 4), dz = Math.floorDiv(cz, 4);
                LevelChunkSection fuente = propia;
                int lx = cx, lz = cz;
                LevelChunk vecino = dx == 0 && dz == 0 ? null : vecinos[(dx + 1) * 3 + dz + 1];
                if (vecino != null && indiceSeccion < vecino.getSections().length) {
                    fuente = vecino.getSection(indiceSeccion);
                    lx = Math.floorMod(cx, 4);
                    lz = Math.floorMod(cz, 4);
                } else {
                    lx = Math.max(0, Math.min(3, cx));
                    lz = Math.max(0, Math.min(3, cz));
                }
                for (int y = 0; y < 4; y++) {
                    amplias[((cx + 2) * 4 + y) * 8 + cz + 2] = fuente.getNoiseBiome(lx, y, lz).value();
                }
            }
        }
        return amplias;
    }

    /**
     * Pasto, follaje y agua en el bloque (x, y, z) de la sección, mezclados
     * como el "biome blend" de vanilla: cada celda de 4 bloques promedia las
     * 3×3 de alrededor y el bloque interpola entre los centros de las 4
     * celdas más cercanas. Sin eso el tinte cambia en escalones de 4 bloques
     * en el borde de dos biomas, muy visible en el LOD lejano. Solo el canal
     * que usa el bloque, y solo si lo usa ({@link ColoresBloque.FuenteTinte}).
     */
    @Override
    public int color(ColoresBloque.Tinte tinte, int x, int y, int z) {
        if (tintesMezclados == null) {
            tintesMezclados = mezclarTintes();
        }
        float px = (x + 0.5f) / 4 - 0.5f, pz = (z + 0.5f) / 4 - 0.5f;
        int cx = (int) Math.floor(px), cz = (int) Math.floor(pz);
        float fx = px - cx, fz = pz - cz;
        int cy = y >> 2;
        ColoresBloque.Tintes a = tintesMezclados[celdaMezcla(cx, cy, cz)];
        ColoresBloque.Tintes b = tintesMezclados[celdaMezcla(cx + 1, cy, cz)];
        ColoresBloque.Tintes c = tintesMezclados[celdaMezcla(cx, cy, cz + 1)];
        ColoresBloque.Tintes d = tintesMezclados[celdaMezcla(cx + 1, cy, cz + 1)];
        return switch (tinte) {
            case PASTO -> ColorTextura.bilineal(a.pasto(), b.pasto(), c.pasto(), d.pasto(), fx, fz);
            case FOLLAJE -> ColorTextura.bilineal(a.follaje(), b.follaje(), c.follaje(), d.follaje(), fx, fz);
            default -> ColorTextura.bilineal(a.agua(), b.agua(), c.agua(), d.agua(), fx, fz);
        };
    }

    private static int celdaMezcla(int cx, int cy, int cz) {
        return ((cx + 1) * 4 + cy) * 6 + cz + 1;
    }

    private ColoresBloque.Tintes[] mezclarTintes() {
        ColoresBloque.Tintes[] mezclados = new ColoresBloque.Tintes[6 * 4 * 6];
        int[] pasto = new int[9], follaje = new int[9], agua = new int[9];
        for (int cx = -1; cx < 5; cx++) {
            for (int cz = -1; cz < 5; cz++) {
                for (int cy = 0; cy < 4; cy++) {
                    int n = 0;
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            Biome bioma = biomasAmplias[((cx + dx + 2) * 4 + cy) * 8 + cz + dz + 2];
                            int bx = origenX + (cx + dx) * 4 + 2, bz = origenZ + (cz + dz) * 4 + 2;
                            pasto[n] = bioma.getGrassColor(bx, bz);
                            follaje[n] = bioma.getFoliageColor();
                            agua[n] = bioma.getWaterColor();
                            n++;
                        }
                    }
                    mezclados[celdaMezcla(cx, cy, cz)] = new ColoresBloque.Tintes(ColorTextura.promedioRgb(pasto, n),
                            ColorTextura.promedioRgb(follaje, n), ColorTextura.promedioRgb(agua, n));
                }
            }
        }
        return mezclados;
    }

    private static DataLayer copiar(DataLayer capa) {
        return capa == null ? null : capa.copy();
    }

    @Override
    public boolean soloAire() {
        return soloAire;
    }

    @Override
    public boolean homogenea() {
        // count() recorre la paleta, no los 4096 bloques uno por uno contra
        // el mundo: con un único estado en la paleta la sección es homogénea.
        int[] distintos = {0};
        estados.count((estado, cantidad) -> distintos[0]++);
        if (distintos[0] != 1) {
            return false;
        }
        // Una cubierta encima cambia la cara de arriba: ya no es uniforme.
        if (estadosArriba != null) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    if (forma(estadosArriba.get(x, 0, z)) == Forma.CUBIERTA) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** Lo que se consulta de cada estado de bloque, calculado una vez por sección. */
    private record InfoEstado(BlockState estado, int id, SuperVoxel.Material material, Forma forma, int emision) {
    }

    /**
     * Por entrada de la paleta de la sección (si tiene hasta {@link #PALETA_MAXIMA}):
     * cada bloque lee su índice del almacén de bits y va directo al arreglo, sin
     * buscar el estado en ningún mapa.
     */
    private static final int PALETA_MAXIMA = 256;
    private InfoEstado[] porPaleta;
    private net.minecraft.world.level.chunk.Palette<BlockState> paleta;
    private net.minecraft.util.BitStorage almacen;
    private boolean paletaLeida;

    /** Fila de abajo de la sección de arriba, por columna (x * 16 + z), llenada a medida que se pide. */
    private InfoEstado[] filaArriba;

    private InfoEstado infoArriba(int x, int z) {
        if (filaArriba == null) {
            filaArriba = new InfoEstado[256];
        }
        InfoEstado info = filaArriba[x * 16 + z];
        if (info == null) {
            info = info(estadosArriba.get(x, 0, z));
            filaArriba[x * 16 + z] = info;
        }
        return info;
    }

    private InfoEstado infoEn(int x, int y, int z) {
        if (!paletaLeida) {
            paletaLeida = true;
            var datos = estados.data;
            if (datos.palette().getSize() <= PALETA_MAXIMA) {
                paleta = datos.palette();
                almacen = datos.storage();
                porPaleta = new InfoEstado[paleta.getSize()];
            }
        }
        if (porPaleta == null) {
            return info(estados.get(x, y, z));
        }
        int i = almacen.get((y << 8) | (z << 4) | x);
        InfoEstado info = porPaleta[i];
        if (info == null) {
            info = info(paleta.valueFor(i));
            porPaleta[i] = info;
        }
        return info;
    }

    /**
     * Memo de {@link InfoEstado} por sección (una sección tiene pocos estados
     * distintos): antes cada bloque buscaba su estado en cuatro mapas (forma,
     * material y dos veces {@code Block.getId}), la mitad del costo de extraer.
     */
    private final it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap<BlockState, InfoEstado> infos =
            new it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap<>();
    private BlockState ultimoEstado;
    private InfoEstado ultimaInfo;

    private InfoEstado info(BlockState estado) {
        if (estado == ultimoEstado) {
            return ultimaInfo;
        }
        InfoEstado info = infos.get(estado);
        if (info == null) {
            info = new InfoEstado(estado, Block.getId(estado), material(estado), forma(estado), emision(estado));
            infos.put(estado, info);
        }
        ultimoEstado = estado;
        ultimaInfo = info;
        return info;
    }

    @Override
    public SuperVoxel voxel(int x, int y, int z) {
        InfoEstado info = infoEn(x, y, z);
        BlockState estado = info.estado();
        switch (info.forma()) {
            case DECORACION, CUBIERTA -> estado = Blocks.AIR.defaultBlockState();
            case SUMERGIDA -> estado = AGUA;
            case CRUZ -> {
                return cruz(info, x, y, z);
            }
            case NORMAL -> { }
        }
        if (info.forma() != Forma.NORMAL) {
            info = info(estado);
        }
        SuperVoxel.Material material = info.material();
        if (material == SuperVoxel.Material.AIRE) {
            return AIRE;
        }
        // Alfombra encima: el bloque toma la cubierta. Nieve fina: el bloque queda como es
        // (hojas, pasto) y solo su cara de arriba se dibuja nevada (SuperVoxel.nevado).
        InfoEstado infoEncima = y < SectionExtractor.LADO - 1 ? infoEn(x, y + 1, z)
                : estadosArriba != null ? infoArriba(x, z) : null;
        BlockState encima = infoEncima == null ? null : infoEncima.estado();
        boolean nevado = false;
        if (encima != null && infoEncima.forma() == Forma.CUBIERTA) {
            if (encima.is(Blocks.SNOW)) {
                nevado = true;
            } else {
                estado = encima;
                info = info(estado);
            }
        }
        int rgb;
        if (biomasAmplias != null) {
            rgb = ColoresBloque.rgb(info.id(), estado, this, x, y, z);
        } else {
            Biome bioma = biomas[((x >> 2) * 4 + (y >> 2)) * 4 + (z >> 2)];
            rgb = ColoresBloque.rgb(info.id(), estado, bioma, origenX + x, origenZ + z);
        }
        // Flores, pasto, cultivos y caña no se dibujan en el LOD: tiñen el bloque de abajo en
        // la proporción que cubren, así un campo de flores lejano conserva su color.
        if (infoEncima != null && (infoEncima.forma() == Forma.DECORACION || infoEncima.forma() == Forma.CRUZ)) {
            int cobertura = ColoresBloque.cobertura(infoEncima.id());
            if (cobertura > 0) {
                int rgbDecoracion = biomasAmplias != null ? ColoresBloque.rgb(infoEncima.id(), encima, this, x, y, z)
                        : ColoresBloque.rgb(infoEncima.id(), encima, biomas[((x >> 2) * 4 + (y >> 2)) * 4 + (z >> 2)],
                        origenX + x, origenZ + z);
                rgb = ColorTextura.mezclar(rgb, rgbDecoracion, ColorTextura.pesoCobertura(cobertura));
            }
        }
        // Luz de los 6 vecinos en una pasada: la máxima (cielo o bloque) y la de bloque sola.
        int luces = luces(x, y + 1, z);
        luces = maxLuces(luces, luces(x - 1, y, z));
        luces = maxLuces(luces, luces(x + 1, y, z));
        luces = maxLuces(luces, luces(x, y, z - 1));
        luces = maxLuces(luces, luces(x, y, z + 1));
        luces = maxLuces(luces, luces(x, y - 1, z));
        int luzBloque = SuperVoxel.cuantizarLuzBloque(Math.max(luces & 15, info.emision()));
        // Un solo objeto (antes, una copia por cada con...): mismos flags que conLuzHorneada,
        // conLuzBloque y conNevado; estado como conEstado (ids > 65535 quedan sin estado).
        int flags = (luces >> 4) << 4 | luzBloque << 2 | (nevado ? 0b10 : 0);
        int id = info.id();
        short idEstado = id > 0 && id <= 0xFFFF ? (short) id : SuperVoxel.SIN_ESTADO;
        return new SuperVoxel((byte) (rgb >> 16), (byte) (rgb >> 8), (byte) rgb, (byte) SuperVoxel.LLENO, material,
                (byte) flags, idEstado);
    }



    private static final SuperVoxel AIRE =
            new SuperVoxel((byte) 0, (byte) 0, (byte) 0, (byte) 0, SuperVoxel.Material.AIRE, (byte) 0);

    /** Planta en cruz: su color (con el tinte del bioma), la luz del lugar donde está y su estado. */
    private SuperVoxel cruz(InfoEstado info, int x, int y, int z) {
        int rgb = biomasAmplias != null ? ColoresBloque.rgb(info.id(), info.estado(), this, x, y, z)
                : ColoresBloque.rgb(info.id(), info.estado(), biomas[((x >> 2) * 4 + (y >> 2)) * 4 + (z >> 2)],
                origenX + x, origenZ + z);
        int luces = luces(x, y, z);
        int flags = (luces >> 4) << 4 | SuperVoxel.cuantizarLuzBloque(Math.max(luces & 15, info.emision())) << 2;
        int id = info.id();
        short idEstado = id > 0 && id <= 0xFFFF ? (short) id : SuperVoxel.SIN_ESTADO;
        return new SuperVoxel((byte) (rgb >> 16), (byte) (rgb >> 8), (byte) rgb, (byte) SuperVoxel.LLENO,
                SuperVoxel.Material.CRUZ, (byte) flags, idEstado);
    }

    private static int maxLuces(int a, int b) {
        return Math.max(a & 0xF0, b & 0xF0) | Math.max(a & 15, b & 15);
    }

    /**
     * Luz horneada del bloque: la MÁXIMA de sus vecinos (arriba, costados y
     * abajo), es decir la luz del aire que toca alguna de sus caras. Antes
     * era solo la del bloque de arriba, y la cara de un acantilado quedaba
     * "a oscuras" aunque le dé el sol de costado. Un bloque enterrado (todos
     * sus vecinos sólidos) queda con luz 0: eso es lo que permite descartar
     * cuevas y caras enterradas en el render.
     *
     * Vecinos fuera de la sección: arriba se lee la sección de arriba; a los
     * costados no hay dato y se asume luz plena (no descartar caras de un
     * acantilado justo en el borde de un chunk); abajo, sin luz.
     *
     * Devuelve las dos luces de un vecino juntas: la máxima entre cielo y
     * bloque en los bits 4-7 y la de bloque sola en los bits 0-3 (para
     * {@link SuperVoxel#luzBloque()}); fuera de la sección a los costados no
     * hay luz de bloque.
     */
    private int luces(int x, int y, int z) {
        if (x < 0 || x > 15 || z < 0 || z > 15) {
            return 15 << 4;
        }
        if (y < 0) {
            return 0;
        }
        boolean dentro = y < SectionExtractor.LADO;
        DataLayer capaCielo = dentro ? cielo : cieloArriba;
        DataLayer capaBloque = dentro ? bloque : bloqueArriba;
        int ly = y & 15;
        // Sin datos de luz del cielo (motor de luz todavía no corrió, o
        // sección por encima de todo): asumir cielo abierto.
        int deCielo = capaCielo == null ? 15 : capaCielo.get(x, ly, z);
        int deBloque = capaBloque == null ? 0 : capaBloque.get(x, ly, z);
        return Math.max(deCielo, deBloque) << 4 | deBloque;
    }

    /** Luz que emite el bloque mismo (lava, piedra luminosa): la de sus vecinos puede faltar si está enterrado a medias. */
    private static int emision(BlockState estado) {
        try {
            return estado.getLightEmission();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /**
     * Material por estado de bloque, calculado una vez: son hasta cinco
     * consultas de tags por bloque y se pregunta por cada bloque de cada
     * sección. Los tags cambian al recargar datapacks: ahí se vacía
     * ({@link #olvidarMateriales}).
     */
    private static final Map<BlockState, SuperVoxel.Material> MATERIALES = new ConcurrentHashMap<>();

    /** Tags recargados (/reload): los materiales cacheados pueden haber cambiado. */
    public static void olvidarMateriales() {
        MATERIALES.clear();
    }

    private static SuperVoxel.Material material(BlockState estado) {
        return MATERIALES.computeIfAbsent(estado, LectorSeccionMinecraft::calcularMaterial);
    }

    private static SuperVoxel.Material calcularMaterial(BlockState estado) {
        if (estado.isAir()) {
            return SuperVoxel.Material.AIRE;
        }
        if (estado.getBlock() instanceof LiquidBlock && estado.getFluidState().is(FluidTags.WATER)) {
            return SuperVoxel.Material.AGUA;
        }
        if (estado.is(BlockTags.LEAVES) || estado.is(BlockTags.REPLACEABLE_BY_TREES)
                || estado.is(BlockTags.FLOWERS) || estado.is(BlockTags.SAPLINGS)
                || estado.is(BlockTags.CROPS)) {
            return SuperVoxel.Material.VEGETACION;
        }
        // Invisible en el mapa vanilla (vidrio, barreras, luz): invisible en el LOD.
        if (estado.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) == MapColor.NONE) {
            return SuperVoxel.Material.AIRE;
        }
        return SuperVoxel.Material.SOLIDO;
    }
}
