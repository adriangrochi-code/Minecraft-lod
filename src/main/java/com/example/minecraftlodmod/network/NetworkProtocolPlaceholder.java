package com.example.minecraftlodmod.network;

/**
 * TODO (hito 7 del documento de arquitectura): protocolo cliente-servidor.
 *
 * Pendiente de completar contra la Payload/Network API real de NeoForge
 * 1.21.1 (el nombre exacto de las clases cambia entre versiones — verificar
 * contra la documentación/MDK antes de implementar):
 *
 * 1. Definir un record de payload de PEDIDO (cliente -> servidor):
 *    región + nivel de LOD pedido. Registrar con el sistema de payloads de
 *    NeoForge (típicamente vía {@code IPayloadHandler} y registro en el
 *    evento de registro de payloads del mod).
 *
 * 2. Definir un record de payload de RESPUESTA (servidor -> cliente):
 *    los bytes ya serializados por
 *    {@link com.example.minecraftlodmod.storage.OctreeNodeCodec#serializar}
 *    (mismo formato que se usa en disco, sin repetir el header de región
 *    completo — el cliente ya sabe qué pidió).
 *
 * 3. Del lado servidor: al recibir un pedido, generar (si no está cacheado)
 *    o leer del cache en disco (modo LOCAL de generation/) y responder.
 *
 * 4. Del lado cliente: al conectar, sondear si el servidor entiende el
 *    canal custom (respuesta o timeout) para decidir entre modo REMOTO
 *    (con companion server-side) o modo de compatibilidad (solo LOD de
 *    zonas ya visitadas localmente).
 */
public final class NetworkProtocolPlaceholder {
    private NetworkProtocolPlaceholder() {
    }
}
