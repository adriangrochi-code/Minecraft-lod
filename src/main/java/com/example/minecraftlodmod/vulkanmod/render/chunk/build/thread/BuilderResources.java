package com.example.minecraftlodmod.vulkanmod.render.chunk.build.thread;

import com.example.minecraftlodmod.vulkanmod.Initializer;
import com.example.minecraftlodmod.vulkanmod.render.chunk.RenderSection;
import com.example.minecraftlodmod.vulkanmod.render.chunk.build.renderer.BlockRenderer;
import com.example.minecraftlodmod.vulkanmod.render.chunk.build.renderer.FluidRenderer;
import com.example.minecraftlodmod.vulkanmod.render.chunk.build.RenderRegion;
import com.example.minecraftlodmod.vulkanmod.render.chunk.build.color.TintCache;
import com.example.minecraftlodmod.vulkanmod.render.chunk.build.light.LightMode;
import com.example.minecraftlodmod.vulkanmod.render.chunk.build.light.LightPipeline;
import com.example.minecraftlodmod.vulkanmod.render.chunk.build.light.data.ArrayLightDataCache;
import com.example.minecraftlodmod.vulkanmod.render.chunk.build.light.data.QuadLightData;
import com.example.minecraftlodmod.vulkanmod.render.chunk.build.light.flat.FlatLightPipeline;
import com.example.minecraftlodmod.vulkanmod.render.chunk.build.light.smooth.NewSmoothLightPipeline;
import com.example.minecraftlodmod.vulkanmod.render.chunk.build.light.smooth.SmoothLightPipeline;

public class BuilderResources {
    public final ThreadBuilderPack builderPack = new ThreadBuilderPack();
    public final TintCache tintCache = new TintCache();

    public final BlockRenderer blockRenderer;
    public final FluidRenderer fluidRenderer;

    public final ArrayLightDataCache lightDataCache = new ArrayLightDataCache();
    public final QuadLightData quadLightData = new QuadLightData();

    private RenderRegion region;

    private int totalBuildTime = 0, buildCount = 0;

    public BuilderResources() {
        LightPipeline flatLightPipeline = new FlatLightPipeline(this.lightDataCache);

        LightPipeline smoothLightPipeline;
        if (Initializer.CONFIG.ambientOcclusion == LightMode.SUB_BLOCK) {
            smoothLightPipeline = new NewSmoothLightPipeline(lightDataCache);
        }
        else {
            smoothLightPipeline = new SmoothLightPipeline(lightDataCache);
        }

        this.blockRenderer = new BlockRenderer(flatLightPipeline, smoothLightPipeline);
        this.fluidRenderer = new FluidRenderer(flatLightPipeline, smoothLightPipeline);

        this.blockRenderer.setResources(this);
        this.fluidRenderer.setResources(this);
    }

    public void update(RenderRegion region, RenderSection renderSection) {
        this.region = region;
        this.blockRenderer.prepareForWorld(region, true);

        this.lightDataCache.reset(region, renderSection.xOffset(), renderSection.yOffset(), renderSection.zOffset());
    }

    public void clear() {
        builderPack.clearAll();
    }

    public void updateBuildStats(int buildTime) {
        this.buildCount++;
        this.totalBuildTime += buildTime;
    }

    public RenderRegion getRegion() {
        return region;
    }

    public int getTotalBuildTime() {
        return totalBuildTime;
    }

    public int getBuildCount() {
        return buildCount;
    }

    public void resetCounters() {
        totalBuildTime = 0;
        buildCount = 0;
    }

    public void free() {
        this.builderPack.freeAll();
    }
}