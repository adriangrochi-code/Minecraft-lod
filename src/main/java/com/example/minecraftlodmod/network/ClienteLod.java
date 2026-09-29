package com.example.minecraftlodmod.network;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * Lado cliente del protocolo. Solo se carga desde código de cliente: nunca
 * referenciar esta clase desde lógica que corre en un servidor dedicado.
 */
public final class ClienteLod {

    private ClienteLod() {
    }

    /**
     * Modo REMOTO (true) si el servidor tiene el companion, o modo de
     * compatibilidad (false, sección 10). En singleplayer el servidor
     * integrado siempre lo tiene.
     */
    public static boolean servidorTieneCompanion() {
        ClientPacketListener conexion = Minecraft.getInstance().getConnection();
        return conexion != null && conexion.hasChannel(PedidoNodosPayload.TYPE);
    }

    /**
     * Pide nodos al servidor, partiendo en lotes de
     * {@link LimitadorPedidos#MAX_NODOS_POR_PEDIDO}. No hace nada sin companion.
     */
    public static void pedir(List<NodoId> nodos) {
        if (!servidorTieneCompanion()) {
            return;
        }
        for (int i = 0; i < nodos.size(); i += LimitadorPedidos.MAX_NODOS_POR_PEDIDO) {
            List<NodoId> lote = nodos.subList(i, Math.min(nodos.size(), i + LimitadorPedidos.MAX_NODOS_POR_PEDIDO));
            PacketDistributor.sendToServer(new PedidoNodosPayload(lote));
        }
    }
}
