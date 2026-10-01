package com.example.minecraftlodmod.render.mixin;

import com.example.minecraftlodmod.render.ContratoVoxy;
import com.example.minecraftlodmod.render.FuentesPackIris;
import com.google.common.collect.ImmutableList;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.include.IncludeGraph;
import net.irisshaders.iris.shaderpack.include.IncludeProcessor;
import net.irisshaders.iris.shaderpack.include.ShaderPackSourceNames;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Iris arma el grafo de includes (y aplica las opciones del pack) solo desde
 * los nombres de programa que conoce; los archivos del contrato Voxy
 * ({@link ContratoVoxy#ARCHIVOS}) se suman a esa lista para que su código
 * salga preprocesado como el de cualquier programa del pack. Además guarda
 * con qué los preprocesa ({@link FuentesPackIris}).
 */
@Mixin(value = ShaderPack.class, remap = false)
public abstract class MixinShaderPackIris implements FuentesPackIris {

    private static final String CONSTRUCTOR = "<init>(Ljava/nio/file/Path;Ljava/util/Map;Lcom/google/common/collect/ImmutableList;Z)V";

    @Unique
    private IncludeProcessor minecraftlodmod$includes;
    @Unique
    private ImmutableList<StringPair> minecraftlodmod$definiciones;

    @Override
    public IncludeProcessor minecraftlodmod$includes() {
        return minecraftlodmod$includes;
    }

    @Override
    public ImmutableList<StringPair> minecraftlodmod$definiciones() {
        return minecraftlodmod$definiciones;
    }

    /**
     * Iris reasigna las definiciones del entorno dos veces; la última es la que usa al preprocesar.
     * Con el contrato Voxy prendido se suma {@code VOXY} para todos los programas del pack: así
     * deferred/composite leen la profundidad del LOD ({@code vxDepthTex*}, ver DibujoVoxy).
     */
    @ModifyVariable(method = CONSTRUCTOR, at = @At("STORE"), argsOnly = true)
    private ImmutableList<StringPair> minecraftlodmod$guardarDefiniciones(ImmutableList<StringPair> definiciones) {
        if (com.example.minecraftlodmod.render.DibujoVoxy.activoEnConfig()
                && definiciones.stream().noneMatch(d -> d.key().equals("VOXY"))) {
            definiciones = ImmutableList.<StringPair>builder().addAll(definiciones).add(new StringPair("VOXY", "")).build();
        }
        minecraftlodmod$definiciones = definiciones;
        return definiciones;
    }

    @Redirect(method = CONSTRUCTOR, at = @At(value = "NEW",
            target = "net/irisshaders/iris/shaderpack/include/IncludeProcessor"))
    private IncludeProcessor minecraftlodmod$guardarIncludes(IncludeGraph grafo) {
        minecraftlodmod$includes = new IncludeProcessor(grafo);
        return minecraftlodmod$includes;
    }

    @Redirect(method = CONSTRUCTOR,
            at = @At(value = "FIELD", opcode = Opcodes.GETSTATIC,
                    target = "Lnet/irisshaders/iris/shaderpack/include/ShaderPackSourceNames;POTENTIAL_STARTS:Lcom/google/common/collect/ImmutableList;"))
    private ImmutableList<String> minecraftlodmod$conArchivosVoxy() {
        return ImmutableList.<String>builder()
                .addAll(ShaderPackSourceNames.POTENTIAL_STARTS)
                .addAll(ContratoVoxy.ARCHIVOS)
                .build();
    }
}
