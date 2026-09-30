package com.example.minecraftlodmod.vulkanmod.vulkan.memory.buffer;

import com.example.minecraftlodmod.vulkanmod.render.chunk.buffer.UploadManager;
import com.example.minecraftlodmod.vulkanmod.vulkan.Vulkan;
import com.example.minecraftlodmod.vulkanmod.vulkan.device.DeviceManager;
import com.example.minecraftlodmod.vulkanmod.vulkan.memory.MemoryType;
import com.example.minecraftlodmod.vulkanmod.vulkan.queue.CommandPool;
import com.example.minecraftlodmod.vulkanmod.vulkan.queue.TransferQueue;

import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;

public class IndirectBuffer extends Buffer {
    CommandPool.CommandBuffer commandBuffer;

    public IndirectBuffer(long size, MemoryType type) {
        super(VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT, type);
        this.createBuffer(size);
    }

    public void recordCopyCmd(ByteBuffer byteBuffer) {
        int size = byteBuffer.remaining();

        if (size > this.bufferSize - this.usedBytes) {
            resizeBuffer((long) (this.bufferSize * 1.5f));
            this.usedBytes = 0;
        }

        if (this.type.mappable()) {
            this.type.copyToBuffer(this, byteBuffer, size, 0, this.usedBytes);
        } else {
            if (commandBuffer == null)
                commandBuffer = DeviceManager.getTransferQueue().beginCommands();

            StagingBuffer stagingBuffer = Vulkan.getStagingBuffer();
            stagingBuffer.copyBuffer(size, byteBuffer);

            TransferQueue.uploadBufferCmd(commandBuffer.getHandle(), stagingBuffer.getId(), stagingBuffer.getOffset(), this.getId(), this.getUsedBytes(), size);
        }

        offset = usedBytes;
        usedBytes += size;
    }

    public void submitUploads() {
        if (commandBuffer == null)
            return;

        UploadManager.INSTANCE.submitTracked(commandBuffer);
        commandBuffer = null;
    }
}
