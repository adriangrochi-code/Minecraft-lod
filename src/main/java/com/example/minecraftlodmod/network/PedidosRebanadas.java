package com.example.minecraftlodmod.network;

import java.util.ArrayList;
import java.util.List;

/**
 * Qué rebanadas pedir y cuándo (cliente, {@link EspejoServidor}). Lógica pura y
 * thread-safe: el render avisa lo que le falta desde varios hilos y el tick del
 * cliente saca lo que hay que mandar.
 *
 * - Una rebanada que falta se pide una vez, en el orden en que el render la buscó
 *   (planifica de cerca hacia afuera: lo cercano primero).
 * - Con {@link #MAX_EN_VUELO} pedidas sin respuesta no se piden más.
 * - Recibida: no se vuelve a pedir por {@link #VIGENCIA_NANOS} aunque al render le
 *   sigan faltando claves (lo que falta en una rebanada recibida no existe en el
 *   servidor). Pasado ese tiempo, la próxima falta la vuelve a pedir: así llega lo
 *   que el servidor generó después (jugadores explorando, pregeneración).
 * - Pedida sin respuesta en {@link #ESPERA_MAXIMA_NANOS}: se da por perdida.
 * - Huellas: la de cada rebanada recibida se manda al volver a pedirla; si no
 *   cambió en el servidor, la respuesta no trae datos. Se guardan por dimensión y
 *   sobreviven a {@link #reiniciar} (y a la sesión, ver {@link #escribirHuellas}).
 */
public final class PedidosRebanadas {

    static final int MAX_EN_VUELO = 24;
    static final long VIGENCIA_NANOS = 120_000_000_000L;
    static final long ESPERA_MAXIMA_NANOS = 60_000_000_000L;

    private static final byte QUERIDA = 0, PEDIDA = 1, RECIBIDA = 2;

    private final it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap estados = new it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap();
    private final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap tiempos = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
    private final java.util.ArrayDeque<RebanadaId> queridas = new java.util.ArrayDeque<>();
    private int enVuelo;
    private final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap huellas = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
    private byte dimension;

    /** Dimensión de las rebanadas que se piden ahora (las huellas son por dimensión). */
    public synchronized void dimension(byte dimension) {
        this.dimension = dimension;
    }

    private long claveHuella(RebanadaId r) {
        return r.empaquetada() | (long) (dimension & 0xFF) << 56;
    }

    /** La huella conocida de la rebanada en la dimensión actual; 0 si no hay. */
    public synchronized long huella(RebanadaId r) {
        return huellas.get(claveHuella(r));
    }

    public synchronized void querer(RebanadaId r, long ahora) {
        long k = r.empaquetada();
        if (estados.containsKey(k)) {
            byte e = estados.get(k);
            long t = tiempos.get(k);
            boolean vencida = e == RECIBIDA && ahora - t > VIGENCIA_NANOS
                    || e == PEDIDA && ahora - t > ESPERA_MAXIMA_NANOS;
            if (!vencida) {
                return;
            }
            if (e == PEDIDA) {
                enVuelo--;
            }
        }
        estados.put(k, QUERIDA);
        tiempos.put(k, ahora);
        queridas.add(r);
    }

    /** Hasta {@code maximo} rebanadas para pedir ahora (quedan como pedidas). */
    public synchronized List<RebanadaId> aMandar(long ahora, int maximo) {
        List<RebanadaId> lote = new ArrayList<>();
        while (lote.size() < maximo && enVuelo < MAX_EN_VUELO && !queridas.isEmpty()) {
            RebanadaId r = queridas.poll();
            long k = r.empaquetada();
            if (estados.get(k) != QUERIDA) {
                continue;
            }
            estados.put(k, PEDIDA);
            tiempos.put(k, ahora);
            enVuelo++;
            lote.add(r);
        }
        return lote;
    }

    public synchronized void recibida(RebanadaId r, long ahora, long huella) {
        if (huella != 0) {
            huellas.put(claveHuella(r), huella);
        }
        long k = r.empaquetada();
        if (estados.containsKey(k) && estados.get(k) == PEDIDA) {
            enVuelo--;
        }
        estados.put(k, RECIBIDA);
        tiempos.put(k, ahora);
    }

    public synchronized int enVuelo() {
        return enVuelo;
    }

    public synchronized void reiniciar() {
        estados.clear();
        tiempos.clear();
        queridas.clear();
        enVuelo = 0;
    }

    /** Olvida también las huellas (otro servidor). */
    public synchronized void reiniciarTodo() {
        reiniciar();
        huellas.clear();
    }

    /** Huellas a disco: cantidad, y pares (clave, huella). */
    public synchronized void escribirHuellas(java.io.DataOutput salida) throws java.io.IOException {
        salida.writeInt(huellas.size());
        for (var e : huellas.long2LongEntrySet()) {
            salida.writeLong(e.getLongKey());
            salida.writeLong(e.getLongValue());
        }
    }

    public synchronized void leerHuellas(java.io.DataInput entrada) throws java.io.IOException {
        int n = entrada.readInt();
        if (n < 0 || n > 10_000_000) {
            throw new java.io.IOException("Cantidad de huellas inválida: " + n);
        }
        for (int i = 0; i < n; i++) {
            huellas.put(entrada.readLong(), entrada.readLong());
        }
    }
}
