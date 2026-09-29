package com.example.minecraftlodmod.config;

import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.lang.management.ManagementFactory;
import java.util.EnumSet;

/**
 * Persistencia de la config (sección 11) sobre {@link ModConfigSpec}, el
 * sistema de config que NeoForge ya trae: archivos TOML, validación de
 * rangos, recarga en caliente y config de servidor por mundo. La pantalla
 * in-game (Cloth Config si está instalado, la automática de NeoForge si no)
 * solo edita estos valores — ver {@link PantallaConfig}.
 *
 * Dos archivos:
 * - CLIENTE ({@code config/minecraftlodmod-client.toml}): calidad visual del
 *   jugador. En singleplayer también decide la generación del servidor
 *   integrado (misma máquina).
 * - SERVIDOR ({@code config/minecraftlodmod-server.toml}; NeoForge 21.1 ya no
 *   la pone por mundo salvo que exista {@code <mundo>/serverconfig/}):
 *   generación del servidor dedicado y límites de lo que se sirve por red.
 */
public final class ConfigLod {

    private ConfigLod() {
    }

    public static final class Cliente {
        public final ModConfigSpec.EnumValue<ParametrosCalidad.Seleccion> seleccion;
        public final ModConfigSpec.IntValue radioLodChunks;
        public final ModConfigSpec.DoubleValue umbralPx;
        public final ModConfigSpec.IntValue hilosGeneracion;
        public final ModConfigSpec.IntValue cacheRamMb;
        public final ModConfigSpec.IntValue colapsoDesdeNivel;
        public final ModConfigSpec.IntValue fpsObjetivo;
        public final ModConfigSpec.BooleanValue autoAjuste;
        public final ModConfigSpec.BooleanValue texturasLod;

        Cliente(ModConfigSpec.Builder b) {
            ParametrosCalidad medio = ParametrosCalidad.de(QualityPreset.MEDIO);
            seleccion = b.comment("Preset de calidad. AUTOMATICO lo elige según el hardware;",
                            "PERSONALIZADO usa los valores de la sección [personalizado].")
                    .defineEnum("preset", ParametrosCalidad.Seleccion.AUTOMATICO);
            fpsObjetivo = b.comment("FPS objetivo del auto-ajuste dinámico (solo en PERSONALIZADO;",
                            "los presets traen el suyo).")
                    .defineInRange("fpsObjetivo", medio.fpsObjetivo(),
                            ParametrosCalidad.FPS_MIN, ParametrosCalidad.FPS_MAX);
            autoAjuste = b.comment("Ajustar detalle, radio y generación en caliente para sostener el FPS objetivo.")
                    .define("autoAjuste", true);
            texturasLod = b.comment("Dibujar el LOD con las texturas del paquete de texturas activo (se simplifican solas",
                            "con la distancia). Apagado: colores planos, un poco más barato en GPU.")
                    .define("texturasLod", true);

            b.comment("Valores usados solo con preset = PERSONALIZADO (o guardados por la calibración).")
                    .push("personalizado");
            radioLodChunks = b.comment("Radio máximo de LOD, en chunks.")
                    .defineInRange("radioLodChunks", medio.radioLodChunks(),
                            ParametrosCalidad.RADIO_MIN, ParametrosCalidad.RADIO_MAX);
            umbralPx = b.comment("Error de pantalla tolerado, en píxeles: más alto = menos detalle, más rápido.")
                    .defineInRange("umbralPx", medio.umbralPx(),
                            ParametrosCalidad.UMBRAL_MIN, ParametrosCalidad.UMBRAL_MAX);
            hilosGeneracion = b.comment("Hilos de generación de LOD.")
                    .defineInRange("hilosGeneracion", medio.hilosGeneracion(),
                            ParametrosCalidad.HILOS_MIN, ParametrosCalidad.HILOS_MAX);
            cacheRamMb = b.comment("RAM para el cache de LOD y la cola de generación, en MB.")
                    .defineInRange("cacheRamMb", medio.cacheRamMb(),
                            ParametrosCalidad.CACHE_MIN_MB, ParametrosCalidad.CACHE_MAX_MB);
            colapsoDesdeNivel = b.comment("Nivel de LOD desde el que una sección uniforme se guarda como un solo",
                            "supervóxel (5 = nunca).")
                    .defineInRange("colapsoDesdeNivel", medio.colapsoDesdeNivel(),
                            ParametrosCalidad.COLAPSO_MIN, ParametrosCalidad.COLAPSO_MAX);
            b.pop();
        }

        ParametrosCalidad.Crudos personalizados() {
            return new ParametrosCalidad.Crudos(radioLodChunks.get(), umbralPx.get(), hilosGeneracion.get(),
                    cacheRamMb.get(), colapsoDesdeNivel.get(), fpsObjetivo.get());
        }
    }

    public static final class Servidor {
        public final ModConfigSpec.EnumValue<ParametrosCalidad.Seleccion> seleccion;
        public final ModConfigSpec.IntValue radioServidoMaximo;
        public final ModConfigSpec.IntValue nodosPorSegundo;
        public final ModConfigSpec.IntValue rafagaNodos;

        Servidor(ModConfigSpec.Builder b) {
            b.push("generacion");
            seleccion = b.comment("Preset de generación del servidor. AUTOMATICO: en singleplayer usa el del",
                            "cliente; en un servidor dedicado lo elige según el hardware.")
                    .defineEnum("preset", ParametrosCalidad.Seleccion.AUTOMATICO,
                            EnumSet.complementOf(EnumSet.of(ParametrosCalidad.Seleccion.PERSONALIZADO)));
            b.pop();

            b.comment("Lo que el servidor le sirve a los clientes en multiplayer.").push("red");
            radioServidoMaximo = b.comment("Radio máximo servido, en chunks alrededor del jugador (0 = el del preset).",
                            "Limita cuánto relieve lejano puede ver un cliente.")
                    .defineInRange("radioServidoMaximo", 0, 0, ParametrosCalidad.RADIO_MAX);
            nodosPorSegundo = b.comment("Nodos por segundo que puede pedir cada jugador.")
                    .defineInRange("nodosPorSegundo", 1024, 16, 65536);
            rafagaNodos = b.comment("Ráfaga máxima de nodos por jugador (pedidos acumulados).")
                    .defineInRange("rafagaNodos", 2048, 64, 1 << 20);
            b.pop();
        }
    }

    public static final Cliente CLIENTE;
    public static final ModConfigSpec SPEC_CLIENTE;
    public static final Servidor SERVIDOR;
    public static final ModConfigSpec SPEC_SERVIDOR;

    static {
        var cliente = new ModConfigSpec.Builder().configure(Cliente::new);
        CLIENTE = cliente.getLeft();
        SPEC_CLIENTE = cliente.getRight();
        var servidor = new ModConfigSpec.Builder().configure(Servidor::new);
        SERVIDOR = servidor.getLeft();
        SPEC_SERVIDOR = servidor.getRight();
    }

    public static void registrar(ModContainer contenedor) {
        contenedor.registerConfig(ModConfig.Type.CLIENT, SPEC_CLIENTE);
        contenedor.registerConfig(ModConfig.Type.SERVER, SPEC_SERVIDOR);
    }

    /** Calidad del cliente. Solo en el cliente, con la config ya cargada. */
    public static ParametrosCalidad calidadCliente() {
        return ParametrosCalidad.resolver(CLIENTE.seleccion.get(), CLIENTE.personalizados(),
                nucleosCpu(), ramTotalMb());
    }

    /** Calidad con la que genera un servidor. Llamar desde ServerAboutToStart o después. */
    public static ParametrosCalidad calidadServidor(MinecraftServer servidor) {
        ParametrosCalidad.Seleccion seleccion = SERVIDOR.seleccion.get();
        if (seleccion == ParametrosCalidad.Seleccion.AUTOMATICO && !servidor.isDedicatedServer()) {
            return calidadCliente();
        }
        return ParametrosCalidad.resolver(seleccion, null, nucleosCpu(), ramTotalMb());
    }

    /** Límites de red del servidor, resueltos contra la calidad con que genera. */
    public static LimitesRed limitesRed(ParametrosCalidad calidadServidor) {
        return LimitesRed.resolver(SERVIDOR.radioServidoMaximo.get(), calidadServidor,
                SERVIDOR.nodosPorSegundo.get(), SERVIDOR.rafagaNodos.get());
    }

    static int nucleosCpu() {
        return Runtime.getRuntime().availableProcessors();
    }

    static long ramTotalMb() {
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean so) {
            return so.getTotalMemorySize() / (1024 * 1024);
        }
        return Runtime.getRuntime().maxMemory() / (1024 * 1024);
    }
}
