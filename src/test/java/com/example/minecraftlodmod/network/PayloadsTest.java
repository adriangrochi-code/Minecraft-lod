package com.example.minecraftlodmod.network;

import com.example.minecraftlodmod.core.OctreeNode;
import com.example.minecraftlodmod.core.SuperVoxel;
import com.example.minecraftlodmod.generation.GeneradorLocal;
import com.example.minecraftlodmod.storage.OctreeNodeCodec;
import com.example.minecraftlodmod.storage.RegionFileStore;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PayloadsTest {

    @Test
    void elPedidoIdaYVueltaConservaLosNodos() {
        PedidoNodosPayload original = new PedidoNodosPayload(List.of(
                new NodoId(0, 5, -4, 7), new NodoId(4, -100000, 19, 2_000_000)));
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        PedidoNodosPayload.STREAM_CODEC.encode(buf, original);
        assertEquals(original, PedidoNodosPayload.STREAM_CODEC.decode(buf));
        assertEquals(0, buf.readableBytes());
    }

    @Test
    void unPedidoDemasiadoGrandeSeRechazaAlLeer() {
        List<NodoId> muchos = new ArrayList<>();
        for (int i = 0; i <= LimitadorPedidos.MAX_NODOS_POR_PEDIDO; i++) {
            muchos.add(new NodoId(0, i, 0, 0));
        }
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        PedidoNodosPayload.STREAM_CODEC.encode(buf, new PedidoNodosPayload(muchos));
        assertThrows(IllegalArgumentException.class, () -> PedidoNodosPayload.STREAM_CODEC.decode(buf));
    }

    @Test
    void laRespuestaIdaYVueltaConservaLosBytes() {
        byte[] datos = {1, 2, 3, 4, 5};
        RespuestaNodoPayload original = new RespuestaNodoPayload((byte) 1, new NodoId(2, 3, 4, 5),
                RespuestaNodoPayload.Estado.EXISTE, datos);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        RespuestaNodoPayload.STREAM_CODEC.encode(buf, original);
        RespuestaNodoPayload leida = RespuestaNodoPayload.STREAM_CODEC.decode(buf);

        assertEquals(1, leida.dimensionId());
        assertEquals(original.nodo(), leida.nodo());
        assertEquals(RespuestaNodoPayload.Estado.EXISTE, leida.estado());
        assertArrayEquals(datos, leida.datos());
    }

    @Test
    void responderDistingueExisteVacioYNoGenerado(@TempDir Path dir) throws Exception {
        try (RegionFileStore store = new RegionFileStore(dir, 42, 0)) {
            byte dim = 0;
            SuperVoxel v = new SuperVoxel((byte) 10, (byte) 20, (byte) 30, (byte) 15,
                    SuperVoxel.Material.SOLIDO, (byte) 1);
            OctreeNode nodo = OctreeNode.homogeneo(4, 0, 0, 0, 16, v);
            NodoId existe = new NodoId(4, 3, 2, -1);
            store.guardar(GeneradorLocal.claveRegion(dim, 3, -1), existe.claveNodo(),
                    OctreeNodeCodec.serializar(nodo, 1));
            store.guardar(GeneradorLocal.claveRegion(dim, 3, -1), GeneradorLocal.claveMarca(3, -1), new byte[0]);

            RespuestaNodoPayload r = ProtocoloLod.responder(store, dim, existe);
            assertEquals(RespuestaNodoPayload.Estado.EXISTE, r.estado());
            assertEquals(v, OctreeNodeCodec.deserializar(r.datos(), 0, 1).voxeles()[0]);

            assertEquals(RespuestaNodoPayload.Estado.VACIO,
                    ProtocoloLod.responder(store, dim, new NodoId(4, 3, 10, -1)).estado());
            assertEquals(RespuestaNodoPayload.Estado.NO_GENERADO,
                    ProtocoloLod.responder(store, dim, new NodoId(4, 4, 2, -1)).estado());
        }
    }
}
