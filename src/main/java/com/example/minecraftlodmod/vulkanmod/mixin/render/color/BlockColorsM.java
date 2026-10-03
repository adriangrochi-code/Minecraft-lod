package com.example.minecraftlodmod.vulkanmod.mixin.render.color;

import net.minecraft.client.color.block.BlockColor;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.world.level.block.Block;
import com.example.minecraftlodmod.vulkanmod.interfaces.color.BlockColorsExtended;
import com.example.minecraftlodmod.vulkanmod.render.chunk.build.color.BlockColorRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockColors.class)
public class BlockColorsM implements BlockColorsExtended {

    @Unique
    private BlockColorRegistry colorResolvers = new BlockColorRegistry();

    @Inject(method = "register", at = @At("RETURN"))
    private void onRegister(BlockColor blockColor, Block[] blocks, CallbackInfo ci) {
        this.colorResolvers.register(blockColor, blocks);
    }

    @Override
    public BlockColorRegistry getColorResolverMap() {
        return this.colorResolvers;
    }
}