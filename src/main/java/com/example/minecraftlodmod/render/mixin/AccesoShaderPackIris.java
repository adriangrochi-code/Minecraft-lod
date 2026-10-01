package com.example.minecraftlodmod.render.mixin;

import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Fuentes preprocesadas y carpetas por dimensión de un pack de Iris (render/ShadersVoxy). */
@Mixin(value = ShaderPack.class, remap = false)
public interface AccesoShaderPackIris {

    @Accessor("sourceProvider")
    Function<AbsolutePackPath, String> minecraftlodmod$fuentes();

    @Accessor("dimensionMap")
    Map<NamespacedId, String> minecraftlodmod$carpetaPorDimension();

    @Accessor("dimensionIds")
    List<String> minecraftlodmod$carpetas();
}
