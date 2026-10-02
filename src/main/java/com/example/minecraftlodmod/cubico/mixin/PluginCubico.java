package com.example.minecraftlodmod.cubico.mixin;

import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** Recortes de memoria de contenedores: no si FerriteCore ya los hace, ni con -Dminecraftlodmod.sinRecortesPaleta=true. */
public class PluginCubico implements IMixinConfigPlugin {

    @Override
    public void onLoad(String paquete) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String clase, String mixin) {
        if (mixin.endsWith("MixinBeardifier")) {
            return LoadingModList.get().getModFileById("c2me") == null;
        }
        if (mixin.endsWith("MixinVecinosEstado")) {
            return !Boolean.getBoolean("minecraftlodmod.sinTablaEstados")
                    && LoadingModList.get().getModFileById("ferritecore") == null;
        }
        if (mixin.endsWith("MixinThreadingDetector") || mixin.endsWith("MixinDatosPaleta")) {
            return !Boolean.getBoolean("minecraftlodmod.sinRecortesPaleta")
                    && LoadingModList.get().getModFileById("ferritecore") == null;
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> propios, Set<String> otros) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String clase, ClassNode nodo, String mixin, IMixinInfo info) {
    }

    @Override
    public void postApply(String clase, ClassNode nodo, String mixin, IMixinInfo info) {
    }
}
