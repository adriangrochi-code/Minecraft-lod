package com.example.minecraftlodmod.render;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

import java.lang.reflect.Method;

/**
 * Si hay un shaderpack de Iris activo, leído por reflexión (Iris es opcional:
 * sin él estas consultas dan false y no se carga nada de Iris).
 *
 * Con un pack activo el LOD se dibuja distinto (ver {@link RenderLod}): con el
 * shader de terreno sólido de vanilla y el formato de bloque, que Iris
 * reemplaza por el gbuffers_terrain del pack, para que el pack lo ilumine
 * como al resto del terreno.
 */
public final class ShadersIris {

    private static final Logger LOG = LogUtils.getLogger();

    private static boolean iniciado;
    private static Object api;
    private static Method enUso;
    private static Method sombra;
    private static VertexFormat formatoTerreno;

    private ShadersIris() {
    }

    /** true si Iris está cargado y tiene un shaderpack activo. */
    public static boolean enUso() {
        return llamar(enUso);
    }

    /**
     * El formato de bloque extendido de Iris (IrisVertexFormats.TERRAIN), o null.
     * Con un pack activo Iris arma los buffers de formato BLOCK con este paso: el
     * LOD tiene que escribir sus vértices así o se leen corridos.
     */
    public static VertexFormat formatoTerreno() {
        iniciar();
        return formatoTerreno;
    }

    /** true mientras Iris dibuja la pasada de sombras (ahí el LOD no se dibuja). */
    public static boolean pasadaDeSombras() {
        return llamar(sombra);
    }

    private static boolean llamar(Method m) {
        iniciar();
        if (m == null) {
            return false;
        }
        try {
            return (Boolean) m.invoke(api);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.warn("LOD: no se pudo consultar a Iris; se sigue como sin shaders", e);
            enUso = sombra = null;
            return false;
        }
    }

    private static void iniciar() {
        if (iniciado) {
            return;
        }
        iniciado = true;
        if (!ModList.get().isLoaded("iris")) {
            return;
        }
        try {
            Class<?> clase = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            api = clase.getMethod("getInstance").invoke(null);
            enUso = clase.getMethod("isShaderPackInUse");
            sombra = clase.getMethod("isRenderingShadowPass");
            Object formato = Class.forName("net.irisshaders.iris.vertices.IrisVertexFormats")
                    .getField("TERRAIN").get(null);
            if (formato instanceof VertexFormat f
                    && f.getVertexSize() == GeometriaLod.BYTES_BLOQUE_IRIS) {
                formatoTerreno = f;
            } else {
                LOG.warn("LOD: el formato de terreno de Iris no es el esperado; el LOD usa el de vanilla");
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.warn("LOD: Iris cargado pero sin la API esperada; el LOD sigue como sin shaders", e);
        }
    }
}
