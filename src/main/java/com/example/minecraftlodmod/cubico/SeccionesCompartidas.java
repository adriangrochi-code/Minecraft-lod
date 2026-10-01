package com.example.minecraftlodmod.cubico;

import com.example.minecraftlodmod.config.ConfigLod;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Etapa 2 de los cubic chunks, "no cargar lo lejano en vertical" (sección 32
 * de la arquitectura): las secciones uniformes (todo piedra, todo aire, un solo
 * bioma) usan un contenedor de paleta compartido por valor en vez de uno
 * propio. Medido en el mundo alto, el 74% de los contenedores tenían un solo
 * valor y cada uno pesa ~250 bytes fijos (candados, detector de hilos,
 * configuración) aunque no guarde datos: es lo que más pesa de lo lejano en
 * vertical (relleno y aire, sobre todo con {@link GeneracionVertical}).
 *
 * Solo en el servidor, al cargar cada chunk. Copia al escribir: antes de que
 * {@link LevelChunk#setBlockState} cambie una sección compartida, la sección
 * pasa a tener su propia copia. Los biomas no la necesitan (vanilla arma un
 * contenedor nuevo al cambiarlos). Una escritura directa de otro mod en un
 * contenedor compartido falla con un mensaje claro (nunca cambia las demás).
 */
public final class SeccionesCompartidas {

    private SeccionesCompartidas() {
    }

    /** Tamaño serializado de un contenedor de un solo valor: bits + id + largo 0 (siempre menos de 8 bytes). */
    private static final int TAMANO_UNICO_MAXIMO = 8;

    private static final Map<BlockState, PalettedContainer<BlockState>> BLOQUES = new ConcurrentHashMap<>();
    private static final Map<Holder<Biome>, PalettedContainer<Holder<Biome>>> BIOMAS = new ConcurrentHashMap<>();

    @SubscribeEvent
    public static void alCargarChunk(ChunkEvent.Load evento) {
        if (evento.getLevel() instanceof ServerLevel nivel && evento.getChunk() instanceof LevelChunk chunk
                && ConfigLod.SPEC_SERVIDOR.isLoaded() && ConfigLod.SERVIDOR.compartirSeccionesUniformes.get()) {
            compartir(nivel, chunk);
        }
    }

    @SubscribeEvent
    public static void alParar(ServerStoppedEvent evento) {
        // Los holders de bioma son del registro de ese mundo.
        BLOQUES.clear();
        BIOMAS.clear();
    }

    static void compartir(ServerLevel nivel, LevelChunk chunk) {
        LevelChunkSection[] secciones = chunk.getSections();
        int compartidos = 0;
        for (int i = 0; i < secciones.length; i++) {
            LevelChunkSection s = secciones[i];
            PalettedContainer<BlockState> bloques = s.getStates();
            PalettedContainerRO<Holder<Biome>> biomas = s.getBiomes();
            PalettedContainer<BlockState> nuevosBloques = bloques;
            PalettedContainerRO<Holder<Biome>> nuevosBiomas = biomas;
            if (!compartido(bloques) && bloques.getSerializedSize() < TAMANO_UNICO_MAXIMO) {
                nuevosBloques = BLOQUES.computeIfAbsent(bloques.get(0, 0, 0), e -> marcar(
                        new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, e, PalettedContainer.Strategy.SECTION_STATES)));
                compartidos++;
            }
            if (!compartido(biomas) && biomas.getSerializedSize() < TAMANO_UNICO_MAXIMO) {
                nuevosBiomas = BIOMAS.computeIfAbsent(biomas.get(0, 0, 0), b -> marcar(new PalettedContainer<>(
                        nivel.registryAccess().registryOrThrow(Registries.BIOME).asHolderIdMap(), b,
                        PalettedContainer.Strategy.SECTION_BIOMES)));
                compartidos++;
            }
            if (nuevosBloques != bloques || nuevosBiomas != biomas) {
                secciones[i] = new LevelChunkSection(nuevosBloques, nuevosBiomas);
            }
        }
        ESTADISTICAS.compartidos.add(compartidos);
    }

    /** Antes de escribir en la sección de esa altura: si usa el contenedor compartido, se copia. */
    public static void copiarSiCompartida(LevelChunk chunk, int y) {
        int i = chunk.getSectionIndex(y);
        LevelChunkSection[] secciones = chunk.getSections();
        if (i < 0 || i >= secciones.length) {
            return;
        }
        LevelChunkSection s = secciones[i];
        if (compartido(s.getStates())) {
            secciones[i] = new LevelChunkSection(s.getStates().copy(), s.getBiomes());
            ESTADISTICAS.copias.increment();
        }
    }

    private static boolean compartido(Object contenedor) {
        return contenedor instanceof Compartible c && c.minecraftlodmod$compartido();
    }

    private static <T> PalettedContainer<T> marcar(PalettedContainer<T> c) {
        ((Compartible) c).minecraftlodmod$marcarCompartido();
        return c;
    }

    public static final Estadisticas ESTADISTICAS = new Estadisticas();

    public static final class Estadisticas {
        final LongAdder compartidos = new LongAdder();
        final LongAdder copias = new LongAdder();

        public String resumenYReiniciar() {
            long c = compartidos.sumThenReset();
            long k = copias.sumThenReset();
            if (c == 0 && k == 0) {
                return null;
            }
            return c + " contenedores uniformes compartidos (~" + (c * 250 >> 20) + " MB menos), "
                    + k + " secciones copiadas al escribir, " + (BLOQUES.size() + BIOMAS.size()) + " valores distintos";
        }
    }
}
