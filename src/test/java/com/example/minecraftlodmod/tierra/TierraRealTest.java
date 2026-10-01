package com.example.minecraftlodmod.tierra;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TierraRealTest {

    @Test
    void escalaDesdeElTipoDeDimension() {
        assertEquals(8, TierraReal.metrosPorBloque(ResourceLocation.fromNamespaceAndPath("minecraftlodmod", "tierra_8")));
        assertEquals(6, TierraReal.metrosPorBloque(ResourceLocation.fromNamespaceAndPath("minecraftlodmod", "tierra_6")));
        assertEquals(0, TierraReal.metrosPorBloque(ResourceLocation.withDefaultNamespace("overworld")));
        assertEquals(0, TierraReal.metrosPorBloque(ResourceLocation.fromNamespaceAndPath("otromod", "tierra_8")));
        assertEquals(0, TierraReal.metrosPorBloque("tierra_x"));
        assertEquals(0, TierraReal.metrosPorBloque("tierra_0"));
    }
}
