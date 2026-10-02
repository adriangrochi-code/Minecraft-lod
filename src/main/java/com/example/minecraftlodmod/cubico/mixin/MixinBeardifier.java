package com.example.minecraftlodmod.cubico.mixin;

import com.example.minecraftlodmod.cubico.LimitesBeardifier;
import it.unimi.dsi.fastutil.objects.ObjectListIterator;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Atajo exacto del ajuste del terreno a estructuras ({@link LimitesBeardifier}):
 * fuera de la caja de las piezas y uniones del chunk, los dos bucles de
 * {@code compute} no recorren nada y el aporte queda en 0, como en vanilla.
 * Redirige los {@code hasNext()} en vez de cancelar el método para no crear
 * un objeto por punto de densidad (se llama millones de veces por chunk).
 */
@Mixin(Beardifier.class)
public abstract class MixinBeardifier {

    @Shadow
    @Final
    protected ObjectListIterator<Beardifier.Rigid> pieceIterator;

    @Shadow
    @Final
    protected ObjectListIterator<JigsawJunction> junctionIterator;

    @Unique
    private LimitesBeardifier lod$limites;

    @Inject(method = "<init>(Lit/unimi/dsi/fastutil/objects/ObjectListIterator;Lit/unimi/dsi/fastutil/objects/ObjectListIterator;)V",
            at = @At("RETURN"))
    private void lod$armarLimites(ObjectListIterator<Beardifier.Rigid> piezas, ObjectListIterator<JigsawJunction> uniones,
                                  CallbackInfo ci) {
        LimitesBeardifier limites = new LimitesBeardifier();
        while (pieceIterator.hasNext()) {
            limites.agregarPieza(pieceIterator.next());
        }
        pieceIterator.back(Integer.MAX_VALUE);
        while (junctionIterator.hasNext()) {
            limites.agregarUnion(junctionIterator.next());
        }
        junctionIterator.back(Integer.MAX_VALUE);
        lod$limites = limites;
    }

    @Redirect(method = "compute", at = @At(value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/objects/ObjectListIterator;hasNext()Z"))
    private boolean lod$hayMas(ObjectListIterator<?> iterador, DensityFunction.FunctionContext punto) {
        LimitesBeardifier limites = lod$limites;
        if (limites != null && limites.fuera(punto.blockX(), punto.blockY(), punto.blockZ())) {
            return false;
        }
        return iterador.hasNext();
    }
}
