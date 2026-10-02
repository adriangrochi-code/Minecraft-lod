package com.example.minecraftlodmod.cubico;

import com.example.minecraftlodmod.config.ConfigLod;
import net.minecraft.Util;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.fml.loading.LoadingModList;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Generación de chunks en paralelo (opción de servidor {@code generacionParalela}).
 *
 * <p>Vanilla 1.21.1 corre cada paso de generación desde un único "mailbox"
 * de worldgen: los pasos que devuelven un futuro asíncrono (biomas, ruido,
 * luz) se reparten entre hilos, pero superficie, carvers y features se hacen
 * en el momento, de a un chunk por vez, y con varios núcleos el resto queda
 * esperando. Acá esos tres pasos se mandan al pool de fondo de Minecraft:
 * <ul>
 *   <li>superficie y carvers escriben solo su propio chunk (leen biomas y
 *   ruido de los vecinos, que ya están completos por las dependencias de los
 *   pasos): no necesitan más;</li>
 *   <li>features escribe en los 3×3 chunks de alrededor: se toma un candado
 *   por chunk (rayados, 4096) en orden creciente, así dos features que se
 *   pisan no corren juntas y no hay abrazos mortales.</li>
 * </ul>
 * La misma idea que C2ME (si está instalado, esto no hace nada).
 */
public final class GeneracionParalela {

    private static final int CANDADOS = 4096;
    private static final ReentrantLock[] RAYAS = new ReentrantLock[CANDADOS];
    private static final boolean CON_C2ME = LoadingModList.get() != null
            && LoadingModList.get().getModFileById("c2me") != null;

    /** true mientras el hilo corre el paso original (el mixin no lo vuelve a desviar). */
    private static final ThreadLocal<Boolean> ORIGINAL = ThreadLocal.withInitial(() -> false);

    static {
        for (int i = 0; i < CANDADOS; i++) {
            RAYAS[i] = new ReentrantLock();
        }
    }

    private GeneracionParalela() {
    }

    /** true si hay que desviar el paso al pool (opción prendida y no es la llamada original). */
    public static boolean desviar() {
        return !CON_C2ME && !ORIGINAL.get() && ConfigLod.SPEC_SERVIDOR.isLoaded()
                && ConfigLod.SERVIDOR.generacionParalela.get();
    }

    /** Corre el paso original en el pool de fondo. */
    public static <T> CompletableFuture<T> enPool(Supplier<CompletableFuture<T>> paso) {
        return CompletableFuture.supplyAsync(() -> original(paso), Util.backgroundExecutor()).thenCompose(f -> f);
    }

    /** Como {@link #enPool}, con los candados de los 3×3 chunks alrededor de {@code centro}. */
    public static <T> CompletableFuture<T> enPoolConVecinos(ChunkPos centro, Supplier<CompletableFuture<T>> paso) {
        return CompletableFuture.supplyAsync(() -> {
            int[] indices = indicesVecinos(centro.x, centro.z);
            for (int i : indices) {
                RAYAS[i].lock();
            }
            try {
                return original(paso);
            } finally {
                for (int k = indices.length - 1; k >= 0; k--) {
                    RAYAS[indices[k]].unlock();
                }
            }
        }, Util.backgroundExecutor()).thenCompose(f -> f);
    }

    private static <T> CompletableFuture<T> original(Supplier<CompletableFuture<T>> paso) {
        ORIGINAL.set(true);
        try {
            return paso.get();
        } finally {
            ORIGINAL.set(false);
        }
    }

    /** Índices de candado de los 3×3 chunks, sin repetir y en orden creciente (orden global = sin abrazos mortales). */
    static int[] indicesVecinos(int cx, int cz) {
        int[] indices = new int[9];
        int n = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                indices[n++] = raya(cx + dx, cz + dz);
            }
        }
        Arrays.sort(indices);
        int unicos = 0;
        for (int i = 0; i < n; i++) {
            if (unicos == 0 || indices[unicos - 1] != indices[i]) {
                indices[unicos++] = indices[i];
            }
        }
        return Arrays.copyOf(indices, unicos);
    }

    /** Chunks a menos de 64 de distancia en cada eje nunca comparten candado. */
    static int raya(int cx, int cz) {
        return ((cx & 63) << 6) | (cz & 63);
    }
}
