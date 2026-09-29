package com.example.minecraftlodmod.core;

/**
 * Nodo del octree de LOD. Cada nodo cubre un cubo de mundo cuyo tamaño
 * depende de su nivel (ver sección 2 del documento de arquitectura:
 * factor de reducción progresivo, no solo potencias de 2).
 *
 * Este nodo puede estar:
 *   - vacío (sin contenido, se descarta antes de llegar acá — ver generation/),
 *   - colapsado por homogeneidad (un único SuperVoxel representa todo el nodo),
 *   - mixto (contiene un array de supervóxeles, comprimido con RLE en el
 *     formato serializado — acá en memoria se mantiene descomprimido para
 *     acceso rápido durante el render/selector).
 */
public final class OctreeNode {

    private final int nivelLod;
    private final int origenX, origenY, origenZ; // esquina del cubo en coords de mundo
    private final int tamanoMundo;                // longitud de la arista del cubo, en bloques

    private final boolean homogeneo;
    private final SuperVoxel voxelHomogeneo;   // solo válido si homogeneo == true
    private final SuperVoxel[] voxeles;        // solo válido si homogeneo == false

    private OctreeNode[] hijos; // 8 hijos si está subdividido, null si es hoja

    private OctreeNode(int nivelLod, int origenX, int origenY, int origenZ, int tamanoMundo,
                        boolean homogeneo, SuperVoxel voxelHomogeneo, SuperVoxel[] voxeles) {
        this.nivelLod = nivelLod;
        this.origenX = origenX;
        this.origenY = origenY;
        this.origenZ = origenZ;
        this.tamanoMundo = tamanoMundo;
        this.homogeneo = homogeneo;
        this.voxelHomogeneo = voxelHomogeneo;
        this.voxeles = voxeles;
    }

    public static OctreeNode homogeneo(int nivelLod, int ox, int oy, int oz, int tamanoMundo, SuperVoxel voxel) {
        return new OctreeNode(nivelLod, ox, oy, oz, tamanoMundo, true, voxel, null);
    }

    public static OctreeNode mixto(int nivelLod, int ox, int oy, int oz, int tamanoMundo, SuperVoxel[] voxeles) {
        return new OctreeNode(nivelLod, ox, oy, oz, tamanoMundo, false, null, voxeles);
    }

    public int nivelLod() {
        return nivelLod;
    }

    public int tamanoMundo() {
        return tamanoMundo;
    }

    /** Centro del nodo en coordenadas de mundo — usado por el selector de LOD para calcular distancia. */
    public double centroX() {
        return origenX + tamanoMundo / 2.0;
    }

    public double centroY() {
        return origenY + tamanoMundo / 2.0;
    }

    public double centroZ() {
        return origenZ + tamanoMundo / 2.0;
    }

    public boolean esHomogeneo() {
        return homogeneo;
    }

    public boolean esHoja() {
        return hijos == null;
    }

    public OctreeNode[] hijos() {
        return hijos;
    }

    public void asignarHijos(OctreeNode[] hijos) {
        if (hijos != null && hijos.length != 8) {
            throw new IllegalArgumentException("Un OctreeNode tiene exactamente 8 hijos");
        }
        this.hijos = hijos;
    }

    public SuperVoxel voxelHomogeneo() {
        if (!homogeneo) throw new IllegalStateException("Este nodo no es homogéneo");
        return voxelHomogeneo;
    }

    public SuperVoxel[] voxeles() {
        if (homogeneo) throw new IllegalStateException("Este nodo es homogéneo, usar voxelHomogeneo()");
        return voxeles;
    }
}
