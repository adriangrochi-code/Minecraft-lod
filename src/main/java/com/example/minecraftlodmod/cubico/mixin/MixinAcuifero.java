package com.example.minecraftlodmod.cubico.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import org.apache.commons.lang3.mutable.MutableDouble;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * El acuífero de vanilla, más rápido y con el mismo resultado. Por cada bloque
 * sin densidad (aire y cuevas, casi la mitad de cada chunk) vanilla busca los
 * 12 centros de acuífero vecinos en su cache (índice, valor empaquetado,
 * desempaquetado) y los ordena por distancia. Los 12 dependen solo de la celda
 * de acuífero (16×12×16 bloques), y los bloques que se generan seguidos caen
 * casi siempre en la misma: se guardan desempaquetados los de la última celda.
 * El recorrido, los desempates por distancia, la siembra de los centros y el
 * resto del método son los de vanilla. No se aplica con C2ME (PluginCubico).
 */
@Mixin(Aquifer.NoiseBasedAquifer.class)
public abstract class MixinAcuifero {

    @Shadow
    @Final
    private Aquifer.FluidPicker globalFluidPicker;
    @Shadow
    @Final
    protected long[] aquiferLocationCache;
    @Shadow
    @Final
    private PositionalRandomFactory positionalRandomFactory;
    @Shadow
    protected boolean shouldScheduleFluidUpdate;
    @Shadow
    @Final
    private static double FLOWING_UPDATE_SIMULARITY;

    @Shadow
    protected abstract int getIndex(int x, int y, int z);

    @Shadow
    protected static double similarity(int a, int b) {
        throw new AssertionError();
    }

    @Shadow
    protected abstract double calculatePressure(DensityFunction.FunctionContext contexto, MutableDouble presion,
                                                Aquifer.FluidStatus a, Aquifer.FluidStatus b);

    @Shadow
    protected abstract Aquifer.FluidStatus getAquiferStatus(long posicion);

    /** Celda de acuífero de los centros guardados (Integer.MIN_VALUE = ninguna). */
    @Unique
    private int lod$celdaX = Integer.MIN_VALUE, lod$celdaY, lod$celdaZ;
    @Unique
    private final long[] lod$centros = new long[12];
    @Unique
    private final int[] lod$x = new int[12], lod$y = new int[12], lod$z = new int[12];

    /**
     * @author minecraftlodmod
     * @reason los 12 centros de acuífero de la celda, guardados entre bloques (mismo resultado que vanilla)
     */
    @Nullable
    @Overwrite
    public BlockState computeSubstance(DensityFunction.FunctionContext contexto, double densidad) {
        int bx = contexto.blockX();
        int by = contexto.blockY();
        int bz = contexto.blockZ();
        if (densidad > 0.0) {
            this.shouldScheduleFluidUpdate = false;
            return null;
        }
        Aquifer.FluidStatus global = this.globalFluidPicker.computeFluid(bx, by, bz);
        if (global.at(by).is(Blocks.LAVA)) {
            this.shouldScheduleFluidUpdate = false;
            return Blocks.LAVA.defaultBlockState();
        }
        int cx = Math.floorDiv(bx - 5, 16);
        int cy = Math.floorDiv(by + 1, 12);
        int cz = Math.floorDiv(bz - 5, 16);
        if (cx != lod$celdaX || cy != lod$celdaY || cz != lod$celdaZ) {
            lod$cargarCentros(cx, cy, cz);
        }
        int d1 = Integer.MAX_VALUE;
        int d2 = Integer.MAX_VALUE;
        int d3 = Integer.MAX_VALUE;
        long p1 = 0L;
        long p2 = 0L;
        long p3 = 0L;
        long[] centros = lod$centros;
        int[] xs = lod$x, ys = lod$y, zs = lod$z;
        // Mismo orden que vanilla (x 0..1, y -1..1, z 0..1) y mismos desempates (>=).
        for (int n = 0; n < 12; n++) {
            int dx = xs[n] - bx;
            int dy = ys[n] - by;
            int dz = zs[n] - bz;
            int d = dx * dx + dy * dy + dz * dz;
            if (d1 >= d) {
                p3 = p2;
                p2 = p1;
                p1 = centros[n];
                d3 = d2;
                d2 = d1;
                d1 = d;
            } else if (d2 >= d) {
                p3 = p2;
                p2 = centros[n];
                d3 = d2;
                d2 = d;
            } else if (d3 >= d) {
                p3 = centros[n];
                d3 = d;
            }
        }
        Aquifer.FluidStatus estado1 = this.getAquiferStatus(p1);
        double parecido12 = similarity(d1, d2);
        BlockState bloque = estado1.at(by);
        if (parecido12 <= 0.0) {
            this.shouldScheduleFluidUpdate = parecido12 >= FLOWING_UPDATE_SIMULARITY;
            return bloque;
        }
        if (bloque.is(Blocks.WATER) && this.globalFluidPicker.computeFluid(bx, by - 1, bz).at(by - 1).is(Blocks.LAVA)) {
            this.shouldScheduleFluidUpdate = true;
            return bloque;
        }
        MutableDouble presion = new MutableDouble(Double.NaN);
        Aquifer.FluidStatus estado2 = this.getAquiferStatus(p2);
        double barrera12 = parecido12 * this.calculatePressure(contexto, presion, estado1, estado2);
        if (densidad + barrera12 > 0.0) {
            this.shouldScheduleFluidUpdate = false;
            return null;
        }
        Aquifer.FluidStatus estado3 = this.getAquiferStatus(p3);
        double parecido13 = similarity(d1, d3);
        if (parecido13 > 0.0) {
            double barrera13 = parecido12 * parecido13 * this.calculatePressure(contexto, presion, estado1, estado3);
            if (densidad + barrera13 > 0.0) {
                this.shouldScheduleFluidUpdate = false;
                return null;
            }
        }
        double parecido23 = similarity(d2, d3);
        if (parecido23 > 0.0) {
            double barrera23 = parecido12 * parecido23 * this.calculatePressure(contexto, presion, estado2, estado3);
            if (densidad + barrera23 > 0.0) {
                this.shouldScheduleFluidUpdate = false;
                return null;
            }
        }
        this.shouldScheduleFluidUpdate = true;
        return bloque;
    }

    /** Los 12 centros de la celda, del cache de vanilla o sembrados como en vanilla, ya desempaquetados. */
    @Unique
    private void lod$cargarCentros(int cx, int cy, int cz) {
        int n = 0;
        for (int ox = 0; ox <= 1; ox++) {
            for (int oy = -1; oy <= 1; oy++) {
                for (int oz = 0; oz <= 1; oz++) {
                    int gx = cx + ox;
                    int gy = cy + oy;
                    int gz = cz + oz;
                    int indice = this.getIndex(gx, gy, gz);
                    long centro = this.aquiferLocationCache[indice];
                    if (centro == Long.MAX_VALUE) {
                        RandomSource azar = this.positionalRandomFactory.at(gx, gy, gz);
                        centro = BlockPos.asLong(gx * 16 + azar.nextInt(10), gy * 12 + azar.nextInt(9),
                                gz * 16 + azar.nextInt(10));
                        this.aquiferLocationCache[indice] = centro;
                    }
                    lod$centros[n] = centro;
                    lod$x[n] = BlockPos.getX(centro);
                    lod$y[n] = BlockPos.getY(centro);
                    lod$z[n] = BlockPos.getZ(centro);
                    n++;
                }
            }
        }
        lod$celdaX = cx;
        lod$celdaY = cy;
        lod$celdaZ = cz;
    }
}
