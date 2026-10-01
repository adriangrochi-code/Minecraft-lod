package com.example.minecraftlodmod.render;

import com.google.common.collect.ImmutableList;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.shaderpack.include.IncludeProcessor;

/**
 * Lo que un ShaderPack de Iris usa para preprocesar sus programas, guardado
 * por {@code MixinShaderPackIris}: los archivos Voxy se preprocesan igual pero
 * con {@code VOXY} definido, como lo esperan los packs (render/ShadersVoxy).
 */
public interface FuentesPackIris {

    IncludeProcessor minecraftlodmod$includes();

    ImmutableList<StringPair> minecraftlodmod$definiciones();
}
