package com.example.minecraftlodmod.storage;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;

/**
 * Cache en memoria de datos de nodos ya serializados (bytes, tal como los
 * produce {@link OctreeNodeCodec#serializar}), con un límite REAL de bytes
 * — hasta ahora `QualityPreset.cacheRamMb` existía como número en el preset
 * pero nada lo hacía cumplir. Esta clase es la pieza que le da efecto real.
 *
 * Política LRU (least-recently-used): cuando se supera el presupuesto de
 * bytes, se descartan primero las entradas menos usadas recientemente —
 * son las que con más probabilidad ya no son relevantes (el jugador se
 * alejó de esa región). Lo descartado no se pierde: sigue en disco (ver
 * sección 5 del documento de arquitectura, RegionHeader/OctreeNodeCodec),
 * así que un miss de este cache solo cuesta una lectura de SSD, no
 * regenerar desde cero.
 *
 * Implementado sobre {@code Long2ObjectLinkedOpenHashMap} (fastutil, claves
 * {@code long} sin objetos por entrada) con el orden de inserción como orden
 * LRU (cada lectura mueve la entrada al final); el control de tamaño es
 * manual porque el límite es por BYTES totales, no por cantidad de entradas
 * (las entradas tienen tamaños muy distintos: un nodo homogéneo pesa mucho
 * menos que uno mixto con muchas corridas RLE).
 *
 * Los bytes que se cuentan son los que ocupa de verdad cada entrada en el
 * heap ({@link #COSTO_ENTRADA} además de los datos): los nodos son chicos
 * (~40 B en promedio) y con un {@code LinkedHashMap<Long, byte[]>} cada uno
 * arrastraba ~100 B de entrada, clave y cabecera que no se contaban — el
 * presupuesto de RAM se pasaba ~3 veces (medido: 145 MB "usados", ~450 MB
 * reales).
 *
 * NO es thread-safe por sí sola — quien la use debe sincronizar el acceso
 * externamente (o envolverla) si se accede desde más de un hilo, algo
 * esperable dado que generation/ corre en su propio thread pool.
 */
public final class BoundedRegionCache {

    private final long presupuestoBytes;
    private long bytesUsados = 0;

    private final Long2ObjectLinkedOpenHashMap<byte[]> mapa;

    /**
     * Lo que ocupa una entrada además de sus datos: cabecera del {@code byte[]}
     * (16 B) y su parte del mapa (clave, valor y enlaces, ~20 B, con la tabla
     * a 3/4 de carga ~27 B), redondeado.
     */
    public static final int COSTO_ENTRADA = 48;

    public BoundedRegionCache(long presupuestoBytes) {
        if (presupuestoBytes <= 0) {
            throw new IllegalArgumentException("El presupuesto de bytes debe ser positivo, fue: " + presupuestoBytes);
        }
        this.presupuestoBytes = presupuestoBytes;
        // Orden de inserción + mover al final en cada lectura = orden LRU: la
        // primera entrada es siempre la menos usada recientemente.
        this.mapa = new Long2ObjectLinkedOpenHashMap<>();
    }

    public static BoundedRegionCache desdeMb(int presupuestoMb) {
        return new BoundedRegionCache((long) presupuestoMb * 1024 * 1024);
    }

    /**
     * Mezclador de MurmurHash3 (biyectivo). El mapa de fastutil ya mezcla las
     * claves {@code long} por su cuenta; queda para quien necesite repartir
     * claves empaquetadas (dimensión, región y nodo en campos de bits).
     */
    static long mezclar(long clave) {
        clave ^= clave >>> 33;
        clave *= 0xff51afd7ed558ccdL;
        clave ^= clave >>> 33;
        clave *= 0xc4ceb9fe1a85ec53L;
        clave ^= clave >>> 33;
        return clave;
    }

    /** Inversa de {@link #mezclar} (cada paso del mezclador es invertible). */
    static long desmezclar(long mezclada) {
        long clave = mezclada;
        clave ^= clave >>> 33;
        clave *= 0x9cb4b2f8129337dbL; // inversa de 0xc4ceb9fe1a85ec53 módulo 2^64
        clave ^= clave >>> 33;
        clave *= 0x4f74430c22a54005L; // inversa de 0xff51afd7ed558ccd
        clave ^= clave >>> 33;
        return clave;
    }

    /**
     * Entradas que el desalojo deja para el final (lo cercano al jugador, ver
     * RegionFileStore#ponerCentro): al llenarse se va primero lo lejano aunque
     * se haya usado hace poco. Recibe la clave original (sin mezclar).
     */
    private java.util.function.LongPredicate protegida = clave -> false;
    /** Protegidas que se saltean por desalojo como mucho; pasado eso, LRU puro (todo es cercano). */
    static final int MAX_SALTOS = 4096;

    public void protegerSi(java.util.function.LongPredicate protegida) {
        this.protegida = protegida;
    }

    /** null si la clave no está cacheada (hay que leerla de disco). */
    public byte[] obtener(long claveNodo) {
        return mapa.getAndMoveToLast(claveNodo); // leerla la vuelve la más recientemente usada
    }

    public boolean contiene(long claveNodo) {
        return mapa.containsKey(claveNodo);
    }

    private static long costo(byte[] datos) {
        return datos.length + COSTO_ENTRADA;
    }

    public void poner(long claveNodo, byte[] datos) {
        byte[] anterior = mapa.putAndMoveToLast(claveNodo, datos);
        if (anterior != null) {
            bytesUsados -= costo(anterior);
        }
        bytesUsados += costo(datos);

        desalojarHastaEntrarEnPresupuesto();
    }

    /** Saca una entrada (quedó vieja: se guardó una versión nueva del nodo). */
    public void quitar(long claveNodo) {
        byte[] anterior = mapa.remove(claveNodo);
        if (anterior != null) {
            bytesUsados -= costo(anterior);
        }
    }

    private void desalojarHastaEntrarEnPresupuesto() {
        int saltos = 0;
        while (bytesUsados > presupuestoBytes && !mapa.isEmpty()) {
            long masViejo = mapa.firstLongKey(); // el primero en orden LRU = el menos usado recientemente
            if (saltos < MAX_SALTOS && protegida.test(masViejo)) {
                // Protegida: pasa al final; el próximo desalojo no la vuelve a recorrer. Pasadas
                // MAX_SALTOS (todo lo que quedaba era cercano), LRU puro: el presupuesto manda siempre.
                mapa.getAndMoveToLast(masViejo);
                saltos++;
                continue;
            }
            bytesUsados -= costo(mapa.removeFirst());
        }
    }

    /** Bytes que ocupan las entradas en el heap (datos + {@link #COSTO_ENTRADA} cada una). */
    public long bytesUsados() {
        return bytesUsados;
    }

    public long presupuestoBytes() {
        return presupuestoBytes;
    }

    public int cantidadEntradas() {
        return mapa.size();
    }

    public void limpiar() {
        mapa.clear();
        bytesUsados = 0;
    }
}
