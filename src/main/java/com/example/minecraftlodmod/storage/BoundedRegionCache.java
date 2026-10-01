package com.example.minecraftlodmod.storage;

import java.util.LinkedHashMap;
import java.util.Map;

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
 * Implementado sobre LinkedHashMap en modo "access order" (Java estándar,
 * sin dependencias externas) para obtener el orden LRU gratis; el control
 * de tamaño es manual porque el límite es por BYTES totales, no por
 * cantidad de entradas (las entradas tienen tamaños muy distintos: un nodo
 * homogéneo pesa mucho menos que uno mixto con muchas corridas RLE).
 *
 * NO es thread-safe por sí sola — quien la use debe sincronizar el acceso
 * externamente (o envolverla) si se accede desde más de un hilo, algo
 * esperable dado que generation/ corre en su propio thread pool.
 */
public final class BoundedRegionCache {

    private final long presupuestoBytes;
    private long bytesUsados = 0;

    private final LinkedHashMap<Long, byte[]> mapa;

    public BoundedRegionCache(long presupuestoBytes) {
        if (presupuestoBytes <= 0) {
            throw new IllegalArgumentException("El presupuesto de bytes debe ser positivo, fue: " + presupuestoBytes);
        }
        this.presupuestoBytes = presupuestoBytes;
        // true = access-order: cada get() mueve la entrada al final (más
        // recientemente usada); iterar el mapa de principio a fin da el
        // orden LRU exacto que necesitamos para desalojar.
        this.mapa = new LinkedHashMap<>(16, 0.75f, true);
    }

    public static BoundedRegionCache desdeMb(int presupuestoMb) {
        return new BoundedRegionCache((long) presupuestoMb * 1024 * 1024);
    }

    /**
     * Las claves empaquetan dimensión, región y nodo en campos de bits, y
     * {@code Long.hashCode} (mitad alta XOR mitad baja) superpone la región con
     * el nodo: muchas claves chocaban y el mapa se degradaba a árboles. Se
     * guardan pasadas por el mezclador de MurmurHash3 (biyectivo: no agrega
     * colisiones, solo reparte el hash).
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
        return mapa.get(mezclar(claveNodo)); // el propio get() ya actualiza el orden LRU
    }

    public boolean contiene(long claveNodo) {
        return mapa.containsKey(mezclar(claveNodo));
    }

    public void poner(long claveNodo, byte[] datos) {
        claveNodo = mezclar(claveNodo);
        byte[] anterior = mapa.remove(claveNodo);
        if (anterior != null) {
            bytesUsados -= anterior.length;
        }

        mapa.put(claveNodo, datos);
        bytesUsados += datos.length;

        desalojarHastaEntrarEnPresupuesto();
    }

    /** Saca una entrada (quedó vieja: se guardó una versión nueva del nodo). */
    public void quitar(long claveNodo) {
        byte[] anterior = mapa.remove(mezclar(claveNodo));
        if (anterior != null) {
            bytesUsados -= anterior.length;
        }
    }

    private void desalojarHastaEntrarEnPresupuesto() {
        if (bytesUsados <= presupuestoBytes) {
            return;
        }
        var iterador = mapa.entrySet().iterator();
        long[] salteadas = null;
        int saltos = 0;
        while (bytesUsados > presupuestoBytes && iterador.hasNext()) {
            Map.Entry<Long, byte[]> masViejo = iterador.next(); // el primero en orden LRU = el menos usado recientemente
            if (saltos < MAX_SALTOS && protegida.test(desmezclar(masViejo.getKey()))) {
                if (salteadas == null) {
                    salteadas = new long[64];
                } else if (saltos == salteadas.length) {
                    salteadas = java.util.Arrays.copyOf(salteadas, saltos * 2);
                }
                salteadas[saltos++] = masViejo.getKey();
                continue;
            }
            bytesUsados -= masViejo.getValue().length;
            iterador.remove();
        }
        if (bytesUsados > presupuestoBytes) {
            // Todo lo que quedaba estaba protegido: LRU puro (el presupuesto manda siempre).
            iterador = mapa.entrySet().iterator();
            while (bytesUsados > presupuestoBytes && iterador.hasNext()) {
                bytesUsados -= iterador.next().getValue().length;
                iterador.remove();
            }
            return;
        }
        // Las salteadas pasan al final del orden: el próximo desalojo no las vuelve a recorrer.
        for (int i = 0; i < saltos; i++) {
            mapa.get(salteadas[i]);
        }
    }

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
