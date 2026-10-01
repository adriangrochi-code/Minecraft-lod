package com.example.minecraftlodmod.vulkanmod.render.chunk.build;

import com.example.minecraftlodmod.vulkanmod.render.chunk.cull.QuadFacing;
import com.example.minecraftlodmod.vulkanmod.render.chunk.util.BufferUtil;
import com.example.minecraftlodmod.vulkanmod.render.vertex.TerrainBufferBuilder;
import com.example.minecraftlodmod.vulkanmod.render.vertex.TerrainBuilder;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;

public class UploadBuffer {
    public final int indexCount;
    public final boolean autoIndices;
    public final boolean indexOnly;
    private final ByteBuffer[] vertexBuffers;
    private final ByteBuffer indexBuffer;

    public UploadBuffer(TerrainBuilder terrainBuilder, TerrainBuilder.DrawState drawState) {
        this.indexCount = drawState.indexCount();
        this.autoIndices = drawState.sequentialIndex();
        this.indexOnly = drawState.indexOnly();

        if (!this.indexOnly) {
            this.vertexBuffers = new ByteBuffer[QuadFacing.COUNT];
            for (int i = 0; i < QuadFacing.COUNT; i++) {
                TerrainBufferBuilder bb = terrainBuilder.getBufferBuilder(i);
                if (bb.getVertices() > 0) {
                    this.vertexBuffers[i] = BufferUtil.clone(bb.getBuffer());
                }
            }
        } else {
            this.vertexBuffers = null;
        }

        if (!drawState.sequentialIndex()) {
            this.indexBuffer = BufferUtil.clone(terrainBuilder.getIndexBuffer());
        } else {
            this.indexBuffer = null;
        }
    }

    public ByteBuffer[] getVertexBuffers() { return vertexBuffers; }
    public ByteBuffer getIndexBuffer() { return indexBuffer; }

    public void release() {
        if (vertexBuffers != null)
            for (ByteBuffer buf : vertexBuffers)
                if (buf != null) MemoryUtil.memFree(buf);
        if (indexBuffer != null) MemoryUtil.memFree(indexBuffer);
    }
}