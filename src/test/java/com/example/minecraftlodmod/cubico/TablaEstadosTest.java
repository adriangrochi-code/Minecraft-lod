package com.example.minecraftlodmod.cubico;

import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TablaEstadosTest {

    private static final BooleanProperty ABIERTO = BooleanProperty.create("abierto");
    private static final IntegerProperty EDAD = IntegerProperty.create("edad", 0, 5);
    private static final BooleanProperty OTRA = BooleanProperty.create("otra");

    /** Todos los estados (como mapas de valores, el estado es el propio mapa) y su tabla. */
    private static Map<Map<Property<?>, Comparable<?>>, String> armar(TablaEstados<String> tabla) {
        Map<Map<Property<?>, Comparable<?>>, String> todos = new HashMap<>();
        for (boolean a : new boolean[]{false, true}) {
            for (int e = 0; e <= 5; e++) {
                Map<Property<?>, Comparable<?>> v = new LinkedHashMap<>();
                v.put(ABIERTO, a);
                v.put(EDAD, e);
                String nombre = a + "/" + e;
                todos.put(v, nombre);
                tabla.poner(v, nombre);
            }
        }
        return todos;
    }

    @Test
    void cadaVecinoEsElQueCambiaSoloEsaPropiedad() {
        TablaEstados<String> tabla = new TablaEstados<>(List.of(ABIERTO, EDAD));
        Map<Map<Property<?>, Comparable<?>>, String> todos = armar(tabla);
        assertTrue(tabla.completa());
        for (Map.Entry<Map<Property<?>, Comparable<?>>, String> e : todos.entrySet()) {
            int i = tabla.indice(e.getKey());
            boolean a = (Boolean) e.getKey().get(ABIERTO);
            int edad = (Integer) e.getKey().get(EDAD);
            assertEquals((!a) + "/" + edad, tabla.vecino(i, ABIERTO, a, !a));
            for (int n = 0; n <= 5; n++) {
                assertEquals(a + "/" + n, tabla.vecino(i, EDAD, edad, n));
            }
        }
    }

    @Test
    void valorOPropiedadAjenaDaNull() {
        TablaEstados<String> tabla = new TablaEstados<>(List.of(ABIERTO, EDAD));
        armar(tabla);
        Map<Property<?>, Comparable<?>> v = new HashMap<>();
        v.put(ABIERTO, true);
        v.put(EDAD, 3);
        int i = tabla.indice(v);
        assertNull(tabla.vecino(i, EDAD, 3, 9));
        assertNull(tabla.vecino(i, OTRA, false, true));
        assertNull(tabla.vecino(i, EDAD, 3, "tres"));
    }

    @Test
    void incompletaSiFaltanEstados() {
        TablaEstados<String> tabla = new TablaEstados<>(new ArrayList<>(List.of(ABIERTO, EDAD)));
        Map<Property<?>, Comparable<?>> v = new HashMap<>();
        v.put(ABIERTO, true);
        v.put(EDAD, 3);
        tabla.poner(v, "x");
        assertTrue(!tabla.completa());
    }
}
