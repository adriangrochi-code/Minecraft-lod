package com.example.minecraftlodmod.vulkanmod.render.texture;

import com.example.minecraftlodmod.vulkanmod.vulkan.Synchronization;
import com.example.minecraftlodmod.vulkanmod.vulkan.device.DeviceManager;
import com.example.minecraftlodmod.vulkanmod.vulkan.queue.CommandPool;
import com.example.minecraftlodmod.vulkanmod.vulkan.queue.Queue;

public class ImageUploadHelper {

    public static final ImageUploadHelper INSTANCE = new ImageUploadHelper();

    final Queue queue;
    private CommandPool.CommandBuffer currentCmdBuffer;

    public ImageUploadHelper() {
        queue = DeviceManager.getGraphicsQueue();
    }

    public void submitCommands() {
        if (this.currentCmdBuffer == null) {
            return;
        }

        long fence = queue.submitCommands(this.currentCmdBuffer);
        Synchronization.INSTANCE.addCommandBuffer(this.currentCmdBuffer);

        this.currentCmdBuffer = null;
    }

    public CommandPool.CommandBuffer getOrStartCommandBuffer() {
        if (this.currentCmdBuffer == null) {
            this.currentCmdBuffer = this.queue.beginCommands();
        }

        return this.currentCmdBuffer;
    }

    public CommandPool.CommandBuffer getCommandBuffer() {
        return this.currentCmdBuffer;
    }
}
