package com.example.minecraftlodmod.render;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;

class XessParametrosTest {

    @Test
    void losTamanosSonLosDelHeaderConPack8() {
        // imageView(8) image(8) range(20) format(4) width(4) height(4) = 48
        assertEquals(48, XessParametros.BYTES_VISTA);
        // 6 × 48 + 4 × 4 + 2 × 4 + 6 × 8 = 360
        assertEquals(360, XessParametros.BYTES_EJECUCION);
        // output(8) calidad(4) flags(4) masks(8) heap(8) off(8) heap(8) off(8) cache(8) = 64
        assertEquals(64, XessParametros.BYTES_INICIO);
    }

    @Test
    void laEjecucionPoneCadaCampoEnSuOffset() {
        ByteBuffer b = ByteBuffer.allocate(XessParametros.BYTES_EJECUCION);
        XessParametros.ejecucion(b, new XessParametros.Vista(11, 12, 37, 480, 270),
                new XessParametros.Vista(21, 22, 83, 480, 270), new XessParametros.Vista(31, 32, 100, 480, 270),
                new XessParametros.Vista(61, 62, 97, 960, 540), 0.25f, -0.125f, true, 480, 270);
        b.order(ByteOrder.nativeOrder());
        assertEquals(11, b.getLong(0));
        assertEquals(12, b.getLong(8));
        assertEquals(1, b.getInt(16), "aspectMask COLOR");
        assertEquals(1, b.getInt(24), "levelCount");
        assertEquals(1, b.getInt(32), "layerCount");
        assertEquals(37, b.getInt(36));
        assertEquals(480, b.getInt(40));
        assertEquals(22, b.getLong(48 + 8), "La imagen de velocidad es la segunda vista");
        assertEquals(0, b.getLong(3 * 48), "Sin textura de exposición");
        assertEquals(61, b.getLong(5 * 48), "La salida es la sexta vista");
        assertEquals(960, b.getInt(5 * 48 + 40));
        assertEquals(0.25f, b.getFloat(288));
        assertEquals(-0.125f, b.getFloat(292));
        assertEquals(1f, b.getFloat(296), "exposureScale");
        assertEquals(1, b.getInt(300), "resetHistory");
        assertEquals(480, b.getInt(304));
        assertEquals(270, b.getInt(308));
    }

    @Test
    void elPresetEsElDeEscalaMasCercana() {
        assertEquals(XessParametros.CALIDAD_EQUILIBRADO, XessParametros.calidadPara(1 / 0.5));
        assertEquals(XessParametros.CALIDAD_ULTRA_PLUS, XessParametros.calidadPara(1 / 0.77));
        assertEquals(XessParametros.CALIDAD_ULTRA, XessParametros.calidadPara(1 / 0.67));
        assertEquals(XessParametros.CALIDAD, XessParametros.calidadPara(1 / 0.59));
    }

    @Test
    void elInicioLlevaResolucionCalidadYBanderas() {
        ByteBuffer b = XessParametros.inicio(ByteBuffer.allocate(64), 1920, 1080, XessParametros.CALIDAD,
                XessParametros.FLAG_ENTRADA_LDR);
        assertEquals(1920, b.getInt(0));
        assertEquals(1080, b.getInt(4));
        assertEquals(103, b.getInt(8));
        assertEquals(64, b.getInt(12));
    }
}
