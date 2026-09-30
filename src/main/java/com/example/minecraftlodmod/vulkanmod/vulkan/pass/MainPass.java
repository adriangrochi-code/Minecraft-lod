package com.example.minecraftlodmod.vulkanmod.vulkan.pass;

import com.example.minecraftlodmod.vulkanmod.vulkan.Vulkan;
import com.example.minecraftlodmod.vulkanmod.vulkan.framebuffer.SwapChain;
import com.example.minecraftlodmod.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

public interface MainPass {

    void begin(VkCommandBuffer commandBuffer, MemoryStack stack);

    void end(VkCommandBuffer commandBuffer);

    default void mainTargetBindWrite() {}

    default void mainTargetUnbindWrite() {}

    default void rebindMainTarget() {}

    default void bindAsTexture() {}

    default int getColorAttachmentGlId() {
        return -1;
    }

    default VulkanImage getColorAttachment() {
        return null;
    }
}
