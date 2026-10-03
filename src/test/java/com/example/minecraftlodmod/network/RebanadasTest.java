package com.example.minecraftlodmod.network;

import com.example.minecraftlodmod.generation.SectionExtractor;
import com.example.minecraftlodmod.storage.RegionFileStore;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RebanadasTest {

    @Test
    void elCodigoEsElNivelDeLaClave() {
        assertEquals(0, RebanadaId.codigo(SectionExtractor.claveNodo(0, 5, -4, 7)));
        assertEquals(3, RebanadaId.codigo(SectionExtractor.claveNodo(3, 5, 10, 7)));
        assertEquals(15, RebanadaId.codigo(SectionExtractor.claveNodo(15, 31, 0xFFF, 31)));
        assertEquals(-1, RebanadaId.codigo(1L << 40), "las claves anchas no son de nodo");
    }

    @Test
    void elRadioCuentaDesdeElBordeDeLaRegionYSeEstiraParaNivelesGrandes() {
        RebanadaId cerca = new RebanadaId(1, 0, 0); // chunks 32..63
        assertTrue(cerca.dentroDelRadio(0, 0, 40));
        RebanadaId lejos = new RebanadaId(4, 0, 0); // chunks 128..159
        assertFalse(lejos.dentroDelRadio(0, 0, 100));
        assertTrue(new RebanadaId(4, 0, 7).dentroDelRadio(0, 0, 100), "un nodo de nivel 7 cubre 128 chunks");
    }

    @Test
    void pedidoYRebanadaIdaYVuelta() {
        PedidoRebanadasPayload pedido = new PedidoRebanadasPayload(
                List.of(new RebanadaId(-3, 7, 0), new RebanadaId(100000, -5, 15)), new long[]{0, 123456789L});
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        PedidoRebanadasPayload.STREAM_CODEC.encode(buf, pedido);
        PedidoRebanadasPayload leido = PedidoRebanadasPayload.STREAM_CODEC.decode(buf);
        assertEquals(pedido.rebanadas(), leido.rebanadas());
        assertArrayEquals(pedido.huellas(), leido.huellas());

        RebanadaPayload parte = new RebanadaPayload((byte) 2, new RebanadaId(1, 2, 3), true, false, 99L,
                new long[]{5, 6}, new byte[][]{{1, 2, 3}, {}});
        buf = new FriendlyByteBuf(Unpooled.buffer());
        RebanadaPayload.STREAM_CODEC.encode(buf, parte);
        RebanadaPayload r = RebanadaPayload.STREAM_CODEC.decode(buf);
        assertEquals(parte.rebanada(), r.rebanada());
        assertEquals(99L, r.huella());
        assertTrue(r.ultima());
        assertArrayEquals(parte.claves(), r.claves());
        assertArrayEquals(parte.datos()[0], r.datos()[0]);
        assertEquals(0, buf.readableBytes());
    }

    @Test
    void partirRespetaElTamanoYMarcaLaUltima() {
        int n = 10;
        long[] claves = new long[n];
        byte[][] datos = new byte[n][];
        for (int i = 0; i < n; i++) {
            claves[i] = i;
            datos[i] = new byte[RebanadaPayload.BYTES_POR_PARTE / 3];
        }
        List<RebanadaPayload> partes = RebanadaPayload.partir((byte) 0, new RebanadaId(0, 0, 0), 7, claves, datos);
        assertEquals(4, partes.size());
        assertEquals(n, partes.stream().mapToInt(p -> p.claves().length).sum());
        for (int i = 0; i < partes.size(); i++) {
            assertEquals(i == partes.size() - 1, partes.get(i).ultima());
        }
        List<RebanadaPayload> vacia = RebanadaPayload.partir((byte) 0, new RebanadaId(0, 0, 0), 0, new long[0],
                new byte[0][]);
        assertEquals(1, vacia.size(), "una rebanada vacía igual avisa que terminó");
        assertTrue(vacia.get(0).ultima());
    }

    @Test
    void partirNoPasaElTopeDeEntradasQueAceptaElCliente() {
        // Marcas vacías: los bytes no cortan nunca, la cantidad sí (si no, el cliente rechaza el paquete).
        int n = RebanadaPayload.MAX_ENTRADAS * 2 + 5;
        long[] claves = new long[n];
        byte[][] datos = new byte[n][];
        for (int i = 0; i < n; i++) {
            claves[i] = i;
            datos[i] = new byte[0];
        }
        List<RebanadaPayload> partes = RebanadaPayload.partir((byte) 0, new RebanadaId(0, 0, 15), 7, claves, datos);
        assertEquals(3, partes.size());
        assertTrue(partes.stream().allMatch(p -> p.claves().length <= RebanadaPayload.MAX_ENTRADAS));
        assertEquals(n, partes.stream().mapToInt(p -> p.claves().length).sum());
    }

    @Test
    void elServidorMandaSoloElNivelPedidoYSinCambiosSiLaHuellaCoincide(@TempDir Path dir) throws Exception {
        try (RegionFileStore store = new RegionFileStore(dir, 1, 0)) {
            RegionFileStore.ClaveRegion region = new RegionFileStore.ClaveRegion((byte) 0, 0, 0);
            store.guardar(region, SectionExtractor.claveNodo(0, 1, 2, 3), new byte[]{1, 2, 3});
            store.guardar(region, SectionExtractor.claveNodo(0, 4, 2, 3), new byte[]{4, 5});
            store.guardar(region, SectionExtractor.claveNodo(2, 1, 2, 3), new byte[]{9});
            store.vaciar();
            store.guardar(region, SectionExtractor.claveNodo(0, 7, 2, 3), new byte[]{6}); // pendiente en memoria

            RebanadaId nivel0 = new RebanadaId(0, 0, 0);
            List<RebanadaPayload> partes = ServidorRebanadas.leer(store, (byte) 0, nivel0, 0);
            assertEquals(1, partes.size());
            RebanadaPayload p = partes.get(0);
            assertEquals(3, p.claves().length, "disco y memoria, solo nivel 0");
            assertFalse(p.sinCambios());

            List<RebanadaPayload> otra = ServidorRebanadas.leer(store, (byte) 0, nivel0, p.huella());
            assertTrue(otra.get(0).sinCambios());
            assertEquals(0, otra.get(0).claves().length);

            store.guardar(region, SectionExtractor.claveNodo(0, 8, 2, 3), new byte[]{7});
            assertFalse(ServidorRebanadas.leer(store, (byte) 0, nivel0, p.huella()).get(0).sinCambios(),
                    "con un nodo nuevo la huella cambia");

            // Lo que llega se guarda tal cual en el espejo y se lee igual.
            try (RegionFileStore espejo = new RegionFileStore(dir.resolve("espejo"), 2, 0)) {
                for (int i = 0; i < p.claves().length; i++) {
                    espejo.guardarComprimido(region, p.claves()[i], p.datos()[i]);
                }
                assertArrayEquals(new byte[]{1, 2, 3}, espejo.leer(region, SectionExtractor.claveNodo(0, 1, 2, 3)));
                assertArrayEquals(new byte[]{6}, espejo.leer(region, SectionExtractor.claveNodo(0, 7, 2, 3)));
            }
        }
    }

    @Test
    void elStoreAvisaLoQueFalta(@TempDir Path dir) throws Exception {
        try (RegionFileStore store = new RegionFileStore(dir, 1, 0)) {
            RegionFileStore.ClaveRegion region = new RegionFileStore.ClaveRegion((byte) 0, 2, 3);
            List<Long> faltas = new java.util.ArrayList<>();
            store.observarFaltantes((r, clave) -> faltas.add(clave));
            store.guardar(region, 5, new byte[]{1});
            assertNotNull(store.leer(region, 5));
            assertTrue(store.contiene(region, 5));
            assertNull(store.leer(region, 6));
            assertFalse(store.contiene(region, 7));
            assertEquals(List.of(6L, 7L), faltas);
        }
    }
}
