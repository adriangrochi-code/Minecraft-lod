package com.example.minecraftlodmod.cubico.mixin;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/** Lo que usa vanilla para ordenar y sembrar las features de un chunk ({@code applyBiomeDecoration}). */
@Mixin(ChunkGenerator.class)
public interface AccesoGeneradorFeatures {

    @Accessor("featuresPerStep")
    Supplier<List<FeatureSorter.StepFeatureData>> minecraftlodmod$featuresPorPaso();

    @Accessor("generationSettingsGetter")
    Function<Holder<Biome>, BiomeGenerationSettings> minecraftlodmod$ajustesDeBioma();
}
