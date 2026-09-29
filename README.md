# Minecraft LOD Mod — estado del proyecto

Esqueleto generado a partir del documento de arquitectura
(`arquitectura-minecraft-lod-mod.md`, incluido en este zip).

## Qué está implementado y por qué

Prioricé escribir completo **todo lo que es Java puro** (sin dependencias
de Minecraft/NeoForge), porque es lo único que se puede razonar y validar
con confianza sin un entorno real de compilación con las librerías del
juego. Lo que sí depende de esas librerías quedó como esqueleto comentado
con TODOs específicos, para no generar código "a ciegas" que probablemente
no compile contra las APIs reales.

### Completo y con tests (`core/`, `storage/`, `generation/`, `config/`)

- **`core/SuperVoxel.java`** — el supervóxel individual, serialización al
  formato binario de 8 bytes.
- **`core/OctreeNode.java`** — nodo del octree jerárquico.
- **`core/LodSelector.java`** — selector por error de pantalla
  (screen-space error), agnóstico a qué mod de zoom esté instalado.
- **`storage/RunLengthCodec.java`** — codificación RLE de supervóxeles.
- **`storage/OctreeNodeCodec.java`** — serialización de un nodo completo
  (header + RLE), formato compartido disco/red.
- **`storage/RegionHeader.java`** — header de región con tabla de offsets,
  para lectura parcial (aprovecha bien un SSD).
- **`generation/HierarchicalReducer.java`** — deriva un nivel de LOD desde
  el nivel inferior (promedio de color/altura, votación de material).
- **`generation/GreedyMesher.java`** + **`Quad.java`** — fusiona caras
  coplanares del mismo material/color en quads, para minimizar geometría.
- **`config/QualityPreset.java`** — los 4 presets (Bajo/Medio/Alto/Ultra)
  con sus parámetros, navegación entre escalones, y heurística simple de
  recomendación por hardware.

Todos estos tienen tests en `src/test/`. **No pude compilarlos ni correr
los tests en este entorno** (sin JDK completo — solo JRE — ni acceso a
red para bajar JUnit), así que aunque están escritos con cuidado, la
primera vez que los compiles de verdad puede aparecer algún detalle a
corregir.

### Esqueleto con TODOs (`render/`, `network/`, `benchmark/`)

Estos módulos dependen enteramente de clases reales de NeoForge/Minecraft
(`RenderLevelStageEvent`, `ViewportEvent.ComputeFov`, la Payload/Network
API, generación de dimensiones custom, shaders GLSL) que no pude verificar
sin compilar contra las dependencias reales. Cada archivo placeholder
(`RenderHookPlaceholder.java`, `NetworkProtocolPlaceholder.java`,
`BenchmarkPlaceholder.java`) documenta exactamente qué hay que implementar
y contra qué APIs, siguiendo el documento de arquitectura.

### Estructura de proyecto NeoForge

`build.gradle`, `gradle.properties`, `neoforge.mods.toml` — basados en el
MDK estándar, pero **sin verificar por compilación real**. Las versiones
de NeoForge cambian seguido; hay que compararlos contra el MDK oficial
descargado de neoforged.net antes de compilar.

## Cómo seguir (con Claude Code)

1. Abrí el proyecto en Claude Code (tiene acceso a internet y puede
   compilar contra las dependencias reales, cosa que yo no pude hacer acá).
2. Primer paso: `gradle build` y corregir lo que falle del setup inicial
   contra el MDK real de NeoForge 1.21.1.
3. Correr `gradle test` — validar que los módulos `core`, `storage`,
   `generation` y `config` compilan y sus tests pasan tal como los escribí,
   o corregir lo que haga falta.
4. Implementar `render/` (hito 2 de la arquitectura): enganchar
   `RenderLevelStageEvent`, un solo nivel de LOD fijo primero, sin selector
   dinámico todavía — validar que algo se dibuja en pantalla.
5. Implementar `generation` en modo LOCAL real (leer el `Level` de verdad,
   no solo la lógica pura ya escrita).
6. Conectar el `LodSelector` dinámico vía `ViewportEvent.ComputeFov`.
7. Auto-ajuste de rendimiento (sección 7 del documento).
8. Blend/dithering (sección 6).
9. `network/` — modo REMOTO para multiplayer.
10. `benchmark/` + integración de `QualityPreset` con Cloth Config real
    (pantalla in-game, botón de calibrar).
11. Testear en el hardware de referencia (Ryzen 3500U / Vega 8 / 12GB) y
    con Embeddium instalado y sin instalar.

## Estructura de carpetas

```
src/main/java/com/example/minecraftlodmod/
├── MinecraftLodMod.java          - clase principal (esqueleto)
├── core/                         - completo, con tests
│   ├── SuperVoxel.java
│   ├── OctreeNode.java
│   └── LodSelector.java
├── storage/                      - completo, con tests
│   ├── RunLengthCodec.java
│   ├── OctreeNodeCodec.java
│   └── RegionHeader.java
├── generation/                   - lógica pura completa, con tests
│   ├── HierarchicalReducer.java
│   ├── GreedyMesher.java
│   └── Quad.java
├── config/                       - completo, con tests
│   └── QualityPreset.java
├── render/                       - esqueleto con TODOs
│   └── RenderHookPlaceholder.java
├── network/                      - esqueleto con TODOs
│   └── NetworkProtocolPlaceholder.java
└── benchmark/                    - esqueleto con TODOs
    └── BenchmarkPlaceholder.java
```

## Cómo trabajar con Claude Code: dos pistas

**Pista A — Claude Code avanza solo (de día, sin supervisión):**
Todo lo que se valida con `gradle build` / `gradle test`, sin necesitar abrir
Minecraft ni mirar nada visual:

1. Compilar el esqueleto contra el MDK real de NeoForge 1.21.1 (resolver
   versiones/dependencias).
2. `generation/` real: leer `Level`/`ChunkSection`, conectar
   `GenerationTaskScheduler` ya escrito.
3. `storage/` real: I/O a disco sobre `RegionHeader`/`OctreeNodeCodec`/
   `BoundedRegionCache` ya escritos.
4. `network/` — protocolo y serialización de payloads (no la prueba con dos
   instancias, eso es Pista B).
5. `config/` — integración con Cloth Config, presets, persistencia.
6. `benchmark/` — los datos de la dimensión custom (JSON), puntos de
   teletransporte.
7. Conectar la tercera perilla de `PerformanceAutoTuner` a
   `GenerationTaskScheduler.ajustarLimiteConcurrencia`.

Instrucción sugerida para arrancar una sesión de Pista A: *"Trabajá en la
Pista A del README — todo lo que no necesita que yo abra el juego. Si te
trabás en algo que sí necesita verificación visual, anotalo y pasá al
siguiente ítem."*

**Pista B — sesión con supervisión (en casa, con la PC/laptop a mano):**

1. `render/` — hook de `RenderLevelStageEvent`, subir buffers, ver que algo
   se dibuje de verdad.
2. Shader de blend/dithering entre niveles de LOD.
3. Hook de FOV/zoom (`ViewportEvent.ComputeFov`) — probar con spyglass
   vanilla que el detalle reaccione al hacer zoom.
4. Ajuste fino de `AtmosphericPerspective` — es un efecto que solo se juzga
   viéndolo.
5. Testeo cruzado real en los dos equipos de referencia (Ryzen 3500U/A275 y
   GTX 1060 + i5-9400) — comparar contra los presets ya calibrados en
   `QualityPreset.java` y ajustar si hace falta.

Instrucción sugerida: *"Ahora enfoquémonos solo en render — voy a abrir el
juego y decirte qué veo."*
