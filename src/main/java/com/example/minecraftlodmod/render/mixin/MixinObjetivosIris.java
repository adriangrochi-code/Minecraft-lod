package com.example.minecraftlodmod.render.mixin;

import com.example.minecraftlodmod.render.DibujoVoxy;
import net.irisshaders.iris.shaderpack.properties.PackRenderTargetDirectives;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Iris 1.8 (el de 1.21.1) admite colortex0-15; los packs con contrato Voxy usan más
 * (Complementary con {@code VOXY}: colortex18 y 19). Todo lo demás de Iris sale del tamaño de
 * este conjunto y los buffers se crean recién cuando un programa los usa, así que con el contrato
 * prendido se admiten hasta {@link #OBJETIVOS}. Se decide una vez, al cargar la clase.
 */
@Mixin(value = PackRenderTargetDirectives.class, remap = false)
public class MixinObjetivosIris {

    private static final int OBJETIVOS = 32;

    @ModifyConstant(method = "<clinit>", constant = @Constant(intValue = 16))
    private static int minecraftlodmod$masObjetivos(int original) {
        return DibujoVoxy.activoEnConfig() ? Math.max(original, OBJETIVOS) : original;
    }
}
