package com.example.minecraftlodmod.cubico;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LimitesBeardifierTest {

    @Test
    void fueraDeLaCajaElAporteDeVanillaEsCero() {
        Random r = new Random(7);
        TerrainAdjustment[] tipos = {TerrainAdjustment.BURY, TerrainAdjustment.BEARD_THIN,
                TerrainAdjustment.BEARD_BOX, TerrainAdjustment.ENCAPSULATE};
        int fueras = 0;
        for (int caso = 0; caso < 40; caso++) {
            ObjectArrayList<Beardifier.Rigid> piezas = new ObjectArrayList<>();
            ObjectArrayList<JigsawJunction> uniones = new ObjectArrayList<>();
            LimitesBeardifier limites = new LimitesBeardifier();
            for (int i = 0; i < 1 + r.nextInt(6); i++) {
                int x = r.nextInt(40) - 20, y = 40 + r.nextInt(60), z = r.nextInt(40) - 20;
                Beardifier.Rigid p = new Beardifier.Rigid(new BoundingBox(x, y, z, x + r.nextInt(12), y + r.nextInt(10),
                        z + r.nextInt(12)), tipos[r.nextInt(tipos.length)], r.nextInt(5) - 2);
                piezas.add(p);
                limites.agregarPieza(p);
            }
            for (int i = 0; i < r.nextInt(4); i++) {
                JigsawJunction u = new JigsawJunction(r.nextInt(40) - 20, 50 + r.nextInt(40), r.nextInt(40) - 20, 0,
                        StructureTemplatePool.Projection.RIGID);
                uniones.add(u);
                limites.agregarUnion(u);
            }
            Beardifier vanilla = new Beardifier(piezas.iterator(), uniones.iterator());
            for (int n = 0; n < 4000; n++) {
                int x = r.nextInt(100) - 50, y = r.nextInt(300) - 64, z = r.nextInt(100) - 50;
                if (limites.fuera(x, y, z)) {
                    fueras++;
                    assertEquals(0.0, vanilla.compute(new DensityFunction.SinglePointContext(x, y, z)),
                            "caso " + caso + " en " + x + "," + y + "," + z);
                }
            }
        }
        assertTrue(fueras > 10000, "pocos puntos probados fuera: " + fueras);
    }

    @Test
    void sinPiezasTodoQuedaFuera() {
        assertTrue(new LimitesBeardifier().fuera(0, 64, 0));
    }
}
