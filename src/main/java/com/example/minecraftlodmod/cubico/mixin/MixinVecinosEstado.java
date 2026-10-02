package com.example.minecraftlodmod.cubico.mixin;

import com.example.minecraftlodmod.cubico.TablaEstados;
import it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap;
import net.minecraft.world.level.block.state.StateHolder;
import net.minecraft.world.level.block.state.properties.Property;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/**
 * Vecinos de cada estado de bloque/fluido desde una {@link TablaEstados}
 * compartida por bloque en vez de un {@code ArrayTable} por estado (~20 MB
 * menos de RAM, cliente y servidor). Si la tabla no encaja (un mod que arma
 * estados raros), queda la de vanilla. No se aplica con FerriteCore.
 */
@Mixin(StateHolder.class)
public abstract class MixinVecinosEstado<O, S> {

    @Unique
    private static Map<?, ?> lod$ultimoMapa;
    @Unique
    private static TablaEstados<?> lod$ultimaTabla;

    @Shadow
    @Final
    protected O owner;

    @Shadow
    @Final
    private Reference2ObjectArrayMap<Property<?>, Comparable<?>> values;

    @Unique
    private TablaEstados<S> lod$tabla;
    @Unique
    private int lod$indice;

    @Inject(method = "populateNeighbours", at = @At("HEAD"), cancellable = true)
    private void lod$poblar(Map<Map<Property<?>, Comparable<?>>, S> todos, CallbackInfo ci) {
        TablaEstados<S> tabla = lod$tablaPara(todos, values);
        if (tabla == null) {
            return;
        }
        int indice = tabla.indice(values);
        if (indice >= 0) {
            lod$tabla = tabla;
            lod$indice = indice;
            ci.cancel();
        }
    }

    /** Una tabla por definición de estados: todos sus estados se pueblan seguidos con el mismo mapa. */
    @Unique
    @SuppressWarnings("unchecked")
    private static synchronized <S> TablaEstados<S> lod$tablaPara(Map<Map<Property<?>, Comparable<?>>, S> todos,
                                                                 Map<Property<?>, Comparable<?>> valores) {
        if (lod$ultimoMapa != todos) {
            TablaEstados<S> tabla = new TablaEstados<>(valores.keySet());
            for (Map.Entry<Map<Property<?>, Comparable<?>>, S> e : todos.entrySet()) {
                tabla.poner(e.getKey(), e.getValue());
            }
            lod$ultimoMapa = todos;
            lod$ultimaTabla = tabla.completa() ? tabla : null;
        }
        return (TablaEstados<S>) lod$ultimaTabla;
    }

    @Inject(method = "setValue", at = @At("HEAD"), cancellable = true)
    private void lod$cambiar(Property<?> propiedad, Comparable<?> valor, CallbackInfoReturnable<S> cir) {
        if (lod$tabla == null) {
            return;
        }
        Comparable<?> actual = values.get(propiedad);
        if (actual == null) {
            throw new IllegalArgumentException("Cannot set property " + propiedad + " as it does not exist in " + owner);
        }
        cir.setReturnValue(lod$vecino(propiedad, actual, valor));
    }

    @Inject(method = "trySetValue", at = @At("HEAD"), cancellable = true)
    private void lod$intentarCambiar(Property<?> propiedad, Comparable<?> valor, CallbackInfoReturnable<S> cir) {
        if (lod$tabla == null) {
            return;
        }
        Comparable<?> actual = values.get(propiedad);
        cir.setReturnValue(actual == null ? lod$esto() : lod$vecino(propiedad, actual, valor));
    }

    @Unique
    private S lod$vecino(Property<?> propiedad, Comparable<?> actual, Comparable<?> valor) {
        if (actual.equals(valor)) {
            return lod$esto();
        }
        S vecino = lod$tabla.vecino(lod$indice, propiedad, actual, valor);
        if (vecino == null) {
            throw new IllegalArgumentException("Cannot set property " + propiedad + " to " + valor + " on " + owner
                    + ", it is not an allowed value");
        }
        return vecino;
    }

    @Unique
    @SuppressWarnings("unchecked")
    private S lod$esto() {
        return (S) (Object) this;
    }
}
