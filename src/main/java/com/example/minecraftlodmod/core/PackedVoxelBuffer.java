package com.example.minecraftlodmod.core;

import java.nio.ByteBuffer;

/**
 * Almacenamiento de alto rendimiento para grandes cantidades de
 * {@link SuperVoxel}: un buffer plano off-heap (fuera del heap de Java,
 * gestionado por {@link ByteBuffer#allocateDirect}) con acceso directo a
 * cada campo por offset, en vez de un array de objetos.
 *
 * Por qué importa a esta escala: un array de objetos SuperVoxel tiene
 * overhead de cabecera de objeto de Java por cada elemento (~16 bytes)
 * además de sus 8 bytes de datos reales, y los objetos quedan dispersos en
 * el heap — malo para la localidad de cache de CPU al recorrer millones de
 * vóxeles (el caso típico de generation/ y storage/ a radios grandes).
 * Este buffer guarda exactamente 8 bytes por vóxel, contiguos, replicando
 * el mismo layout que {@link SuperVoxel#escribirEn}.
 *
 * Es una capa OPCIONAL de alto rendimiento: convive con SuperVoxel/SuperVoxel[]
 * sin reemplazarlos — el resto del pipeline ya escrito y testeado sigue
 * funcionando igual; esta clase se usa en los puntos calientes (bucles sobre
 * muchos vóxeles) cuando se quiera exprimir rendimiento extra.
 *
 * No afecta en absoluto al módulo render/: sigue dibujando con
 * VertexConsumer/RenderType como está decidido por compatibilidad con
 * Embeddium/VulkanMod — esto es puramente almacenamiento del lado CPU.
 */
public final class PackedVoxelBuffer {

    private final ByteBuffer buffer;
    private final int capacidad; // cantidad de supervóxeles que caben

    public PackedVoxelBuffer(int capacidad) {
        this.capacidad = capacidad;
        this.buffer = ByteBuffer.allocateDirect(capacidad * SuperVoxel.BYTES);
    }

    public int capacidad() {
        return capacidad;
    }

    public void set(int indice, SuperVoxel v) {
        chequearIndice(indice);
        int offset = indice * SuperVoxel.BYTES;
        buffer.put(offset, v.r());
        buffer.put(offset + 1, v.g());
        buffer.put(offset + 2, v.b());
        buffer.put(offset + 3, v.alturaLocal());
        buffer.put(offset + 4, v.material().codigo);
        buffer.put(offset + 5, v.flags());
        buffer.putShort(offset + 6, v.estado());
    }

    public SuperVoxel get(int indice) {
        chequearIndice(indice);
        int offset = indice * SuperVoxel.BYTES;
        return new SuperVoxel(
                buffer.get(offset),
                buffer.get(offset + 1),
                buffer.get(offset + 2),
                buffer.get(offset + 3),
                SuperVoxel.Material.fromCodigo(buffer.get(offset + 4)),
                buffer.get(offset + 5),
                buffer.getShort(offset + 6)
        );
    }

    /** Acceso directo al material sin construir un SuperVoxel completo — para chequeos rápidos en bucles calientes. */
    public SuperVoxel.Material materialEn(int indice) {
        chequearIndice(indice);
        return SuperVoxel.Material.fromCodigo(buffer.get(indice * SuperVoxel.BYTES + 4));
    }

    /** Acceso directo a la luz horneada sin construir un SuperVoxel completo. */
    public int luzHorneadaEn(int indice) {
        chequearIndice(indice);
        byte flags = buffer.get(indice * SuperVoxel.BYTES + 5);
        return (flags >> 4) & 0x0F;
    }

    /** Copia el contenido de un SuperVoxel[] tradicional a un buffer nuevo. */
    public static PackedVoxelBuffer desde(SuperVoxel[] voxeles) {
        PackedVoxelBuffer buf = new PackedVoxelBuffer(voxeles.length);
        for (int i = 0; i < voxeles.length; i++) {
            buf.set(i, voxeles[i]);
        }
        return buf;
    }

    /** Vuelca este buffer a un SuperVoxel[] tradicional (para interoperar con el resto del pipeline ya escrito). */
    public SuperVoxel[] aArrayDeObjetos() {
        SuperVoxel[] resultado = new SuperVoxel[capacidad];
        for (int i = 0; i < capacidad; i++) {
            resultado[i] = get(i);
        }
        return resultado;
    }

    private void chequearIndice(int indice) {
        if (indice < 0 || indice >= capacidad) {
            throw new IndexOutOfBoundsException("Índice " + indice + " fuera de rango [0, " + capacidad + ")");
        }
    }
}
