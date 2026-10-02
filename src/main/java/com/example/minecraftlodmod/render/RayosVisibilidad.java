package com.example.minecraftlodmod.render;

/**
 * Prueba si una caja (una entidad, un bloque con entidad) se ve desde la
 * cámara o la tapan bloques opacos: rayos desde la cámara al centro y a las 8
 * esquinas de la caja, recorridos bloque por bloque (Amanatides-Woo). Con que
 * uno llegue, se ve. Conservadora: ante la duda (cámara adentro de la caja,
 * demasiado lejos para recorrer) dice que se ve. Lógica pura.
 */
final class RayosVisibilidad {

    /** Bloques opacos del mundo; se consulta desde un hilo aparte. */
    interface Opacidad {
        boolean opaco(int x, int y, int z);
    }

    /** Achica la caja para que los rayos a las esquinas no rocen bloques vecinos. */
    private static final double MARGEN = 0.05;

    private RayosVisibilidad() {
    }

    /**
     * @param maxBloques largo máximo de rayo a recorrer; más lejos, la caja se da por visible
     */
    static boolean visible(double camX, double camY, double camZ,
                           double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                           Opacidad opacidad, double maxBloques) {
        if (camX >= minX && camX <= maxX && camY >= minY && camY <= maxY && camZ >= minZ && camZ <= maxZ) {
            return true;
        }
        double cx = (minX + maxX) / 2, cy = (minY + maxY) / 2, cz = (minZ + maxZ) / 2;
        double dx = cx - camX, dy = cy - camY, dz = cz - camZ;
        if (dx * dx + dy * dy + dz * dz > maxBloques * maxBloques) {
            return true;
        }
        double ax = Math.min(minX + MARGEN, cx), bx = Math.max(maxX - MARGEN, cx);
        double ay = Math.min(minY + MARGEN, cy), by = Math.max(maxY - MARGEN, cy);
        double az = Math.min(minZ + MARGEN, cz), bz = Math.max(maxZ - MARGEN, cz);
        int bMinX = piso(minX), bMinY = piso(minY), bMinZ = piso(minZ);
        int bMaxX = piso(maxX), bMaxY = piso(maxY), bMaxZ = piso(maxZ);
        if (llega(camX, camY, camZ, cx, cy, cz, bMinX, bMinY, bMinZ, bMaxX, bMaxY, bMaxZ, opacidad)) {
            return true;
        }
        for (int i = 0; i < 8; i++) {
            double x = (i & 1) == 0 ? ax : bx;
            double y = (i & 2) == 0 ? ay : by;
            double z = (i & 4) == 0 ? az : bz;
            if (llega(camX, camY, camZ, x, y, z, bMinX, bMinY, bMinZ, bMaxX, bMaxY, bMaxZ, opacidad)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Recorre los bloques del segmento cámara → destino; llega si entra a un
     * bloque de la caja (los de la caja no cuentan: una entidad dentro de un
     * bloque no se tapa a sí misma) sin cruzar ninguno opaco. El bloque de la
     * cámara tampoco cuenta.
     */
    private static boolean llega(double ox, double oy, double oz, double tx, double ty, double tz,
                                 int bMinX, int bMinY, int bMinZ, int bMaxX, int bMaxY, int bMaxZ,
                                 Opacidad opacidad) {
        int x = piso(ox), y = piso(oy), z = piso(oz);
        int finX = piso(tx), finY = piso(ty), finZ = piso(tz);
        double dx = tx - ox, dy = ty - oy, dz = tz - oz;
        int pasoX = dx > 0 ? 1 : dx < 0 ? -1 : 0;
        int pasoY = dy > 0 ? 1 : dy < 0 ? -1 : 0;
        int pasoZ = dz > 0 ? 1 : dz < 0 ? -1 : 0;
        // t (0..1 sobre el segmento) del próximo borde de bloque en cada eje, y cuánto avanza t por bloque.
        double deltaX = pasoX == 0 ? Double.POSITIVE_INFINITY : 1 / Math.abs(dx);
        double deltaY = pasoY == 0 ? Double.POSITIVE_INFINITY : 1 / Math.abs(dy);
        double deltaZ = pasoZ == 0 ? Double.POSITIVE_INFINITY : 1 / Math.abs(dz);
        double tMaxX = pasoX == 0 ? Double.POSITIVE_INFINITY : (pasoX > 0 ? x + 1 - ox : ox - x) * deltaX;
        double tMaxY = pasoY == 0 ? Double.POSITIVE_INFINITY : (pasoY > 0 ? y + 1 - oy : oy - y) * deltaY;
        double tMaxZ = pasoZ == 0 ? Double.POSITIVE_INFINITY : (pasoZ > 0 ? z + 1 - oz : oz - z) * deltaZ;
        int pasos = Math.abs(finX - x) + Math.abs(finY - y) + Math.abs(finZ - z);
        for (int i = 0; i < pasos; i++) {
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                x += pasoX;
                tMaxX += deltaX;
            } else if (tMaxY < tMaxZ) {
                y += pasoY;
                tMaxY += deltaY;
            } else {
                z += pasoZ;
                tMaxZ += deltaZ;
            }
            if (x >= bMinX && x <= bMaxX && y >= bMinY && y <= bMaxY && z >= bMinZ && z <= bMaxZ) {
                return true;
            }
            if (opacidad.opaco(x, y, z)) {
                return false;
            }
        }
        return true;
    }

    private static int piso(double v) {
        return (int) Math.floor(v);
    }
}
