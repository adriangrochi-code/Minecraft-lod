package com.example.minecraftlodmod.cubico;

import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;

/**
 * Caja que encierra todo lo que el {@code Beardifier} de un chunk puede
 * modificar: fuera de ella su aporte a la densidad es exactamente 0.
 *
 * <p>Vanilla evalúa el ajuste del terreno a estructuras (aldeas, ciudades
 * antiguas) en cada punto de densidad de la columna entera, recorriendo todas
 * las piezas cercanas, aunque cada pieza solo aporta a menos de 12 bloques
 * (núcleo de "barba" de 24³, o distancia &lt; 6 al enterrar, con el alto a la
 * mitad). Con esta caja, los puntos lejanos (casi toda la altura de la
 * columna) devuelven 0 sin recorrer nada. Mismo resultado bit a bit.
 */
public final class LimitesBeardifier {

    /** Alcance del núcleo de barba en cada eje ({@code Beardifier.BEARD_KERNEL_RADIUS}). */
    static final int ALCANCE = Beardifier.BEARD_KERNEL_RADIUS;

    private int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
    private int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

    /** Una pieza rígida: en horizontal aporta a distancia &lt; 12 de su caja; en vertical, alrededor de su caja y de su suelo. */
    public void agregarPieza(Beardifier.Rigid pieza) {
        BoundingBox caja = pieza.box();
        int suelo = caja.minY() + pieza.groundLevelDelta();
        incluir(caja.minX() - ALCANCE, Math.min(caja.minY(), suelo) - ALCANCE, caja.minZ() - ALCANCE,
                caja.maxX() + ALCANCE, Math.max(caja.maxY(), suelo) + ALCANCE, caja.maxZ() + ALCANCE);
    }

    /** Una unión de jigsaw: aporta dentro del núcleo de 24³ alrededor de su origen. */
    public void agregarUnion(JigsawJunction union) {
        incluir(union.getSourceX() - ALCANCE, union.getSourceGroundY() - ALCANCE, union.getSourceZ() - ALCANCE,
                union.getSourceX() + ALCANCE, union.getSourceGroundY() + ALCANCE, union.getSourceZ() + ALCANCE);
    }

    private void incluir(int x0, int y0, int z0, int x1, int y1, int z1) {
        minX = Math.min(minX, x0);
        minY = Math.min(minY, y0);
        minZ = Math.min(minZ, z0);
        maxX = Math.max(maxX, x1);
        maxY = Math.max(maxY, y1);
        maxZ = Math.max(maxZ, z1);
    }

    /** true si el aporte en ese punto es seguro 0 (fuera de la caja, o no hay piezas ni uniones). */
    public boolean fuera(int x, int y, int z) {
        return x < minX || x > maxX || y < minY || y > maxY || z < minZ || z > maxZ;
    }
}
