package com.example.minecraftlodmod.vulkanmod.interfaces.color;

import net.minecraft.client.color.block.BlockColors;
import com.example.minecraftlodmod.vulkanmod.render.chunk.build.color.BlockColorRegistry;

public interface BlockColorsExtended {

    static BlockColorsExtended from(BlockColors blockColors) {
        return (BlockColorsExtended) blockColors;
    }

    BlockColorRegistry getColorResolverMap();
}