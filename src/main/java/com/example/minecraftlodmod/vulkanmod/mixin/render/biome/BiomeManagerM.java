package com.example.minecraftlodmod.vulkanmod.mixin.render.biome;

import net.minecraft.world.level.biome.BiomeManager;
import com.example.minecraftlodmod.vulkanmod.interfaces.biome.BiomeManagerExtended;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(BiomeManager.class)
public class BiomeManagerM implements BiomeManagerExtended {

    @Shadow @Final private long biomeZoomSeed;

    @Override
    public long getBiomeZoomSeed() {
        return this.biomeZoomSeed;
    }
}