package com.example.minecraftlodmod.render;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StreamlineParametrosTest {

    private static ByteBuffer buffer(int bytes) {
        return ByteBuffer.allocate(bytes).order(ByteOrder.nativeOrder());
    }

    @Test
    void laCabeceraLlevaElGuidYLaVersion() {
        ByteBuffer b = StreamlineParametros.base(buffer(40), 40, StreamlineParametros.VIEWPORT, 1);
        assertEquals(0x171b6435, b.getInt(8));
        assertEquals((short) 0x9b3c, b.getShort(12));
        assertEquals((short) 0x4fc8, b.getShort(14));
        assertEquals((byte) 0x99, b.get(16));
        assertEquals((byte) 0xa4, b.get(23));
        assertEquals(1, b.getLong(24));
    }

    @Test
    void lasConstantesPonenCadaCampoDondeLoLeeStreamline() {
        float[] id = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        float[] p = new float[16];
        for (int i = 0; i < 16; i++) {
            p[i] = i + 1;
        }
        ByteBuffer b = StreamlineParametros.constantes(buffer(StreamlineParametros.BYTES_CONSTANTES), p, id, id, id,
                0.25f, -0.5f, 1, 1, new float[] {0, 1, 0}, new float[] {1, 0, 0}, new float[] {0, 0, -1},
                0.05f, 2000f, 1.22f, 1.78f, true);
        assertEquals(1f, b.getFloat(32), "cameraViewToClip arranca después de la cabecera");
        assertEquals(16f, b.getFloat(32 + 60));
        assertEquals(1f, b.getFloat(160), "clipToLensClip identidad");
        assertEquals(0.25f, b.getFloat(352));
        assertEquals(-0.5f, b.getFloat(356));
        assertEquals(1f, b.getFloat(392), "cameraUp.y");
        assertEquals(-1f, b.getFloat(420), "cameraFwd.z");
        assertEquals(0.05f, b.getFloat(424));
        assertEquals(2000f, b.getFloat(428));
        assertEquals(1, b.get(445), "cameraMotionIncluded");
        assertEquals(1, b.get(447), "reset");
        assertEquals(40f, b.getFloat(452));
    }

    @Test
    void elRecursoYElTagTienenLosOffsetsDelHeader() {
        ByteBuffer r = StreamlineParametros.recurso(buffer(StreamlineParametros.BYTES_RECURSO),
                new StreamlineParametros.Imagen(11, 12, 13, 97, 1920, 1080, 0x1f));
        assertEquals(11, r.getLong(40));
        assertEquals(12, r.getLong(48));
        assertEquals(13, r.getLong(56));
        assertEquals(1, r.getInt(64), "layout GENERAL");
        assertEquals(1920, r.getInt(68));
        assertEquals(97, r.getInt(76));
        assertEquals(0x1f, r.getInt(100));
        ByteBuffer t = StreamlineParametros.tag(buffer(StreamlineParametros.BYTES_TAG), 999,
                StreamlineParametros.BUFFER_MOVIMIENTO, 960, 540);
        assertEquals(999, t.getLong(32));
        assertEquals(1, t.getInt(40));
        assertEquals(2, t.getInt(44), "eValidUntilEvaluate");
        assertEquals(960, t.getInt(56));
    }

    @Test
    void lasPreferenciasPidenVulkanYHookeoManual() {
        ByteBuffer b = StreamlineParametros.preferencias(buffer(StreamlineParametros.BYTES_PREFERENCIAS), 100, 1,
                200, 300, 1, 400, 500);
        assertEquals(100, b.getLong(40));
        assertEquals(1, b.getInt(48));
        assertEquals(200, b.getLong(56));
        assertEquals(5L, b.getLong(88));
        assertEquals(300, b.getLong(96));
        assertEquals(500, b.getLong(128));
        assertEquals(2, b.getInt(136));
    }

    @Test
    void elModoDeDlssSaleDeLaEscala() {
        assertEquals(StreamlineParametros.DLSS_RENDIMIENTO, StreamlineParametros.modoPara(0.5));
        assertEquals(StreamlineParametros.DLSS_CALIDAD, StreamlineParametros.modoPara(0.67));
        assertEquals(StreamlineParametros.DLSS_ULTRA_CALIDAD, StreamlineParametros.modoPara(0.77));
        assertEquals(StreamlineParametros.DLSS_DLAA, StreamlineParametros.modoPara(1.0));
    }
}
