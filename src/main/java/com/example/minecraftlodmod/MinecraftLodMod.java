package com.example.minecraftlodmod;

import com.example.minecraftlodmod.config.ConmutadorVulkan;
import com.example.minecraftlodmod.vulkanmod.Initializer;
import com.example.minecraftlodmod.config.ConfigLod;
import com.example.minecraftlodmod.config.PantallaConfig;
import com.example.minecraftlodmod.generation.GeneradorLocal;
import com.example.minecraftlodmod.network.ProtocoloLod;
import com.example.minecraftlodmod.render.PaletaTexturas;
import com.example.minecraftlodmod.render.RenderLod;
import com.example.minecraftlodmod.render.BalanceCpuGpu;
import com.example.minecraftlodmod.render.Escalado;
import com.example.minecraftlodmod.render.MonitorRendimiento;
import com.example.minecraftlodmod.render.NubesLejanas;
import com.example.minecraftlodmod.benchmark.SesionCalibracion;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Punto de entrada del mod.
 *
 * TODO (próximos hitos, ver el documento de arquitectura):
 *  - Render en multiplayer: guardar en el cliente lo que llega por red.
 *
 * Ya registrado: la config ({@link ConfigLod}, con pantalla en el cliente),
 * la generación en modo LOCAL ({@link GeneradorLocal}), que corre en todo
 * servidor con el mod — dedicado o integrado de singleplayer — y el
 * protocolo de red que sirve esos nodos ({@link ProtocoloLod}) y, en el
 * cliente, el render de LOD ({@link RenderLod}).
 */
@Mod(MinecraftLodMod.MOD_ID)
public class MinecraftLodMod {

    public static final String MOD_ID = "minecraftlodmod";

    public MinecraftLodMod(IEventBus modEventBus, ModContainer contenedor) {
        ConfigLod.registrar(contenedor);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            PantallaConfig.registrar(contenedor);
            if (ConmutadorVulkan.activoEnEstaSesion()) {
                // VulkanMod integrado (sus opciones quedan en Opciones > Video, como en el suelto).
                new Initializer().onInitializeClient();
            }
        }

        GeneradorLocal generador = new GeneradorLocal(ConfigLod::calidadServidor);
        ProtocoloLod protocolo = new ProtocoloLod(generador);
        NeoForge.EVENT_BUS.register(generador);
        NeoForge.EVENT_BUS.register(protocolo);
        modEventBus.addListener(protocolo::registrar);
        modEventBus.addListener(com.example.minecraftlodmod.cubico.SincroVertical::registrar);
        NeoForge.EVENT_BUS.register(com.example.minecraftlodmod.cubico.SincroVertical.class);
        com.example.minecraftlodmod.cubico.GeneracionVertical.registrar(modEventBus);
        NeoForge.EVENT_BUS.register(com.example.minecraftlodmod.cubico.GeneracionVertical.class);
        NeoForge.EVENT_BUS.register(com.example.minecraftlodmod.cubico.SeccionesCompartidas.class);
        NeoForge.EVENT_BUS.register(com.example.minecraftlodmod.cubico.SeccionesComprimidas.class);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            PaletaTexturas paleta = new PaletaTexturas();
            modEventBus.addListener(paleta::alCoserAtlas);
            modEventBus.addListener(RenderLod::registrarShaders);
            modEventBus.addListener(NubesLejanas::registrarShader);
            modEventBus.addListener(com.example.minecraftlodmod.render.AcabadoLod::registrarShaders);
            modEventBus.addListener(Escalado::registrarShaders);
            NeoForge.EVENT_BUS.addListener(Escalado::alEtapa);
            NeoForge.EVENT_BUS.addListener(paleta::alTerminarTick);
            BalanceCpuGpu balance = new BalanceCpuGpu(generador);
            NeoForge.EVENT_BUS.register(balance);
            RenderLod render = new RenderLod(generador, balance);
            NeoForge.EVENT_BUS.register(render);
            SesionCalibracion.asignarAplicador(render::aplicarCalidad);
            MonitorRendimiento monitor = new MonitorRendimiento(render, generador, balance);
            NeoForge.EVENT_BUS.register(monitor);
            modEventBus.addListener(monitor::registrarCapa);
            modEventBus.addListener(monitor::alRecargarConfig);
            NeoForge.EVENT_BUS.register(com.example.minecraftlodmod.cubico.ClienteVertical.class);
            // Cambió la config del cliente (pantalla o archivo): la preferencia vertical al servidor.
            modEventBus.addListener((net.neoforged.fml.event.config.ModConfigEvent.Reloading e) ->
                    net.minecraft.client.Minecraft.getInstance().execute(
                            com.example.minecraftlodmod.cubico.ClienteVertical::enviarPreferencia));
        }
    }
}
