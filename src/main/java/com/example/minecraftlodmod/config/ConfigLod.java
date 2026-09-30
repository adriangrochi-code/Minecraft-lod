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
        public final ModConfigSpec.BooleanValue lodActivo;
        public final ModConfigSpec.BooleanValue hudRendimiento;
        public final ModConfigSpec.BooleanValue logDepuracion;
        public final ModConfigSpec.BooleanValue ocultarTapado;
        public final ModConfigSpec.EnumValue<ModoEscalado> escalado;
        public final ModConfigSpec.IntValue fsrEscalaPorcentaje;
        public final ModConfigSpec.DoubleValue fsrNitidez;
        public final ModConfigSpec.BooleanValue invertirJitter;
        public final ModConfigSpec.BooleanValue pregenerar;
        public final ModConfigSpec.BooleanValue generacionAproximada;
        public final ModConfigSpec.IntValue radioPregeneracion;
        public final ModConfigSpec.BooleanValue texturasLod;
        public final ModConfigSpec.BooleanValue descartarCuevas;
        public final ModConfigSpec.BooleanValue oclusionAmbiental;
        public final ModConfigSpec.BooleanValue texturasComoTerreno;
        public final ModConfigSpec.BooleanValue nubesLejanas;
        public final ModConfigSpec.BooleanValue curvatura;
        public final ModConfigSpec.IntValue radioCurvaturaKm;
        public final ModConfigSpec.BooleanValue horizonteReal;
        public final ModConfigSpec.DoubleValue pixelesMaximos;

        Cliente(ModConfigSpec.Builder b) {
            ParametrosCalidad medio = ParametrosCalidad.de(QualityPreset.MEDIO);
            seleccion = b.comment("Preset de calidad. AUTOMATICO lo elige según el hardware;",
                            "PERSONALIZADO usa los valores de la sección [personalizado].")
                    .defineEnum("preset", ParametrosCalidad.Seleccion.AUTOMATICO);
            fpsObjetivo = b.comment("FPS objetivo del auto-ajuste dinámico (solo en PERSONALIZADO;",
                            "los presets traen el suyo).")
                    .defineInRange("fpsObjetivo", medio.fpsObjetivo(),
                            ParametrosCalidad.FPS_MIN, ParametrosCalidad.FPS_MAX);
            autoAjuste = b.comment("Auto-ajuste según el cuello de botella (CPU o GPU) para sostener el FPS objetivo: agrupado de caras, oclusión, escala del escalado, generación simultánea, detalle y radio.")
                    .define("autoAjuste", true);
            lodActivo = b.comment("Dibujar el LOD. Apagarlo sirve para comparar contra vanilla; la generación sigue.")
                    .define("lodActivo", true);
            generacionAproximada = b.comment("Horizonte aproximado: estimar el terreno lejano nunca generado directo del",
                            "generador del mundo (sin generar chunks) hasta el radio de LOD. Lo real lo reemplaza al",
                            "explorar o pregenerar. Solo singleplayer por ahora.")
                    .define("generacionAproximada", true);
            pregenerar = b.comment("Generar chunks vanilla del jugador hacia afuera (como Chunky) para llenar el LOD",
                            "sin recorrer el mundo. Usa CPU y disco: los chunks generados quedan guardados en el mundo.",
                            "Solo singleplayer por ahora.")
                    .define("pregenerar", false);
            radioPregeneracion = b.comment("Radio de la pregeneración, en chunks. 256 ≈ 200 mil chunks (del orden de",
                            "1-2 GB y decenas de minutos); 2048 ≈ 13 millones (cientos de GB, días).")
                    .defineInRange("radioPregeneracion", 256, 16, ParametrosCalidad.RADIO_MAX);
            ocultarTapado = b.comment("No armar ni dibujar el LOD escondido detrás de montañas (oclusión por relieve).")
                    .define("ocultarTapado", true);
            texturasLod = b.comment("Dibujar el LOD con las texturas del paquete de texturas activo (se simplifican solas",
                            "con la distancia). Apagado: colores planos, un poco más barato en GPU.")
                    .define("texturasLod", true);
            descartarCuevas = b.comment("No dibujar en el LOD caras sin ninguna luz (interiores de cuevas, caras enterradas).",
                            "Reduce mucho la geometría; puede dejar huecos al mirar dentro de una cueva lejana.")
                    .define("descartarCuevas", true);
            oclusionAmbiental = b.comment("Oscurecer rincones y bases de paredes del LOD (oclusión ambiental por vértice).",
                            "Sin costo en la GPU, pero fusiona menos caras: algo más de geometría.")
                    .define("oclusionAmbiental", true);
            texturasComoTerreno = b.comment("Vóxeles grandes (lejos): el costado de pasto, nieve, micelio, etc. lleva su franja",
                            "solo en la fila de arriba y abajo la textura de la tierra, como un corte del terreno. Apagado:",
                            "la textura del costado se repite en cada bloque del vóxel (una línea de pasto por bloque).")
                    .define("texturasComoTerreno", true);
            nubesLejanas = b.comment("Seguir dibujando las nubes más allá de donde las corta vanilla, hasta el alcance del LOD.")
                    .define("nubesLejanas", true);
            curvatura = b.comment("Curvar el terreno LOD con la distancia como la superficie de un planeta: lo lejano baja",
                            "y se esconde detrás del horizonte.")
                    .define("curvatura", false);
            radioCurvaturaKm = b.comment("Radio del planeta para la curvatura, en km (1 bloque = 1 m). 6371 = la Tierra 1:1.")
                    .defineInRange("radioCurvaturaKm", 6371, 1, 100_000);
            horizonteReal = b.comment("Con curvatura: el radio del LOD sale de la altura de los ojos y el radio del planeta",
                            "(hasta dónde se vería el horizonte de verdad) en vez del radio del preset. Tope: " + ParametrosCalidad.RADIO_MAX + " chunks.")
                    .define("horizonteReal", false);
            pixelesMaximos = b.comment("Tamaño máximo en pantalla de un vóxel del LOD, en píxeles. El nivel de detalle se elige",
                            "por cuánto ocupa en pantalla: con este tope, ni el preset ni el auto-ajuste dejan que un vóxel",
                            "se vea más grande que esto, por lejos o grande que sea. Más bajo = más nítido y más caro.")
                    .defineInRange("pixelesMaximos", 4.0, 1.0, 16.0);

            b.comment("Depuración y funciones experimentales.").push("experimental");
            escalado = b.comment("EXPERIMENTAL: dibujar el mundo a menor resolución y llevarlo a la pantalla con un",
                            "escalador. FSR1 = espacial (cualquier GPU); TEMPORAL = propio, junta varios cuadros (cualquier",
                            "GPU); XESS = Intel XeSS (Windows, libxess.dll); DLSS = NVIDIA DLSS (Windows, RTX).",
                            "Se desactiva solo con gráficos Fabulous, Iris o VulkanMod.")
                    .defineEnum("escalado", ModoEscalado.APAGADO);
            fsrEscalaPorcentaje = b.comment("Resolución del mundo al escalar, en % de la pantalla (77 = 'Calidad' de AMD,",
                            "67 = 'Equilibrado', 59 = 'Rendimiento', 50 = 'Rendimiento máximo').")
                    .defineInRange("fsrEscalaPorcentaje", 77, 50, 99);
            fsrNitidez = b.comment("Nitidez final (RCAS, en FSR1 y TEMPORAL): 0 = máxima; cada unidad la reduce a la mitad.")
                    .defineInRange("fsrNitidez", 0.25, 0.0, 2.0);
            invertirJitter = b.comment("XeSS/DLSS: pasar el desplazamiento sub-píxel con el signo contrario. Probar si la imagen",
                            "tiembla o queda borrosa quieta con XeSS o DLSS (depende de la convención de cada uno).")
                    .define("invertirJitter", false);
            hudRendimiento = b.comment("Mostrar arriba de la pantalla FPS, tiempos de cuadro, CPU, RAM y el trabajo del LOD.")
                    .define("hudRendimiento", true);
            logDepuracion = b.comment("Escribir logs/minecraftlodmod-depuracion.log: una línea por segundo con el",
                            "rendimiento, la posición y lo que hace el mod, más eventos (tirones, cambios de config).")
                    .define("logDepuracion", false);
            b.pop();

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
