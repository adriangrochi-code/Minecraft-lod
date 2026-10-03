package com.example.minecraftlodmod.vulkanmod.interfaces;

import net.minecraft.client.renderer.ShaderInstance;
import com.example.minecraftlodmod.vulkanmod.vulkan.shader.GraphicsPipeline;
import com.example.minecraftlodmod.vulkanmod.vulkan.shader.descriptor.UBO;
import com.example.minecraftlodmod.vulkanmod.vulkan.util.MappedBuffer;

import java.util.function.Supplier;

public interface ShaderMixed {

    static ShaderMixed of(ShaderInstance compiledShaderProgram) {
        return (ShaderMixed) compiledShaderProgram;
    }

    void setPipeline(GraphicsPipeline graphicsPipeline);

    GraphicsPipeline getPipeline();

    void setupUniformSuppliers(UBO ubo);

    Supplier<MappedBuffer> getUniformSupplier(String name);

    void setDoUniformsUpdate();
}