package com.example.minecraftlodmod.cubico;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Vecinos de los estados de un bloque (o fluido) sin una tabla por estado.
 *
 * <p>Vanilla le arma a cada estado un {@code ArrayTable} (propiedad, valor) →
 * estado con sus propios índices: ~26 mil tablas, unos 20 MB. Los estados de
 * un bloque son el producto cartesiano de los valores de sus propiedades, así
 * que con un índice en base mixta (un "dígito" por propiedad) el vecino que
 * cambia una propiedad es {@code índice + (nuevo - actual) × paso}: una sola
 * tabla de estados por bloque, compartida. Idea de FerriteCore (malte0811,
 * MIT), escrita de nuevo.
 *
 * @param <S> tipo de estado
 */
public final class TablaEstados<S> {

    private final Property<?>[] propiedades;
    private final List<Object2IntOpenHashMap<Comparable<?>>> indiceValor = new ArrayList<>();
    private final int[] pasos;
    private final Object[] estados;

    public TablaEstados(Collection<Property<?>> propiedades) {
        this.propiedades = propiedades.toArray(new Property<?>[0]);
        this.pasos = new int[this.propiedades.length];
        int paso = 1;
        for (int i = 0; i < this.propiedades.length; i++) {
            Object2IntOpenHashMap<Comparable<?>> indices = new Object2IntOpenHashMap<>();
            indices.defaultReturnValue(-1);
            int n = 0;
            for (Comparable<?> valor : this.propiedades[i].getPossibleValues()) {
                indices.put(valor, n++);
            }
            indiceValor.add(indices);
            pasos[i] = paso;
            paso = Math.multiplyExact(paso, n);
        }
        this.estados = new Object[paso];
    }

    /** Ubica un estado según sus valores; devuelve su índice (o -1 si los valores no encajan en la tabla). */
    public int poner(Map<Property<?>, Comparable<?>> valores, S estado) {
        int indice = indice(valores);
        if (indice >= 0) {
            estados[indice] = estado;
        }
        return indice;
    }

    /** Índice de un juego de valores, o -1 si falta una propiedad o un valor no es posible. */
    public int indice(Map<Property<?>, Comparable<?>> valores) {
        if (valores.size() != propiedades.length) {
            return -1;
        }
        int indice = 0;
        for (int i = 0; i < propiedades.length; i++) {
            Comparable<?> valor = valores.get(propiedades[i]);
            int v = valor == null ? -1 : indiceValor.get(i).getInt(valor);
            if (v < 0) {
                return -1;
            }
            indice += v * pasos[i];
        }
        return indice;
    }

    /** true si todos los lugares de la tabla tienen estado (si no, no sirve como reemplazo). */
    public boolean completa() {
        for (Object e : estados) {
            if (e == null) {
                return false;
            }
        }
        return true;
    }

    /**
     * El estado que difiere del de {@code indice} solo en {@code propiedad} = {@code valor},
     * o null si la propiedad no es de este bloque o el valor no es posible.
     */
    @SuppressWarnings("unchecked")
    public S vecino(int indice, Property<?> propiedad, Comparable<?> actual, Object valor) {
        for (int i = 0; i < propiedades.length; i++) {
            if (propiedades[i] == propiedad) {
                Object2IntOpenHashMap<Comparable<?>> indices = indiceValor.get(i);
                int nuevo = valor instanceof Comparable<?> c ? indices.getInt(c) : -1;
                if (nuevo < 0) {
                    return null;
                }
                return (S) estados[indice + (nuevo - indices.getInt(actual)) * pasos[i]];
            }
        }
        return null;
    }
}
