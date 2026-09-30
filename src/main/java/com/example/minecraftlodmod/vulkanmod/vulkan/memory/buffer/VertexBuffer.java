package com.example.minecraftlodmod.vulkanmod.vulkan.memory.buffer;

import com.example.minecraftlodmod.vulkanmod.vulkan.memory.MemoryType;
import com.example.minecraftlodmod.vulkanmod.vulkan.memory.MemoryTypes;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;

public class VertexBuffer extends Buffer {

    public VertexBuffer(long size) {
        this(size, MemoryTypes.HOST_MEM);
    }

    public VertexBuffer(long size, MemoryType type) {
        super(VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, type);
        this.createBuffer(size);

    }

}
