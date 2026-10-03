# Arquitectura — Mod LOD para Minecraft (NeoForge 1.21.1)

Inspirado en Voxy, orientado a buen rendimiento en equipos de baja potencia (target de referencia: Ryzen 3500U / Vega 8 / 12GB RAM), con soporte multiplayer.

## 1. Objetivo y principios de diseño

- Terreno lejano representado como vóxeles simplificados ("supervóxeles"), con muchos niveles de LOD para transiciones graduales y poco notorias.
- Al hacer zoom (spyglass vanilla o modificado, o cualquier mod de zoom de terceros), los chunks lejanos deben ajustar su nivel de detalle según el FOV efectivo, sin romper compatibilidad con esos mods.
- En niveles de LOD altos (cerca / con zoom), debe seguir viendo como terreno con granularidad de chunk, no bloques homogéneos gigantes.
- Sin soporte Vulkan por ahora (descartado del alcance por complejidad/riesgo vs. beneficio en iGPU).
- Debe funcionar tanto en singleplayer como en servidores multiplayer (requiere mod companion server-side).

## 2. Modelo de datos: jerarquía LOD

- Unidad base: **sección vanilla de 16×16×16** (no columna completa), para poder descartar secciones vacías o colapsar secciones homogéneas sin procesar toda la altura del mundo.
- Octree por región, con niveles derivados jerárquicamente (cada nivel se construye a partir del anterior, no del original).
- Factor de reducción **progresivo, no solo potencias de 2**: pasos finos cerca (×1.25–1.5) y más grandes lejos (×2), para lograr muchos niveles con transiciones suaves.
- Cada supervóxel guarda: color promedio, altura local de superficie, tipo de material simplificado (sólido/agua/vegetación/aire), flags.
- **Colapso por homogeneidad** (una sección de un solo bloque → un supervóxel sin examinar los 4096 bloques): solo permitido **a partir de cierto nivel de LOD** (ej. desde LOD2–LOD4 según preset) — nunca en los niveles cercanos, para mantener la sensación de "muchos chunks" al acercarse o hacer zoom.
- Multi-dimensión: rango vertical parametrizado por dimensión (Overworld/Nether/End tienen alturas distintas).

## 3. Selector de LOD en tiempo real (screen-space error)

```
por cada nodo del octree visible en el frustum:
    error_mundo = tamaño_del_nodo × factor_simplificación
    error_pantalla = (error_mundo / distancia_a_cámara) × (altura_pantalla / (2 × tan(FOV/2)))
    si error_pantalla > umbral_px: subdividir → evaluar hijos
    si no: usar este nodo como hoja de render
```

- El FOV se lee vía el evento nativo de NeoForge `ViewportEvent.ComputeFov`, leído **después** de que otros mods de zoom ya lo modificaron (agnóstico a qué mod de zoom esté instalado, incluido spyglass vanilla o modificado).
- Esto hace que el ajuste por zoom sea automático: no hay lógica especial de "detectar zoom", el mismo criterio escala solo con el FOV efectivo.
- Recorrido del árbol se cachea entre frames; solo se re-evalúa si el jugador se movió lo suficiente o cambió el FOV.

## 4. Pipeline de generación

```
Chunk/sección vanilla se carga
   → Extractor (chequea: vacía → descartar | homogénea → supervóxel único | mixta → seguir)
   → Reductor jerárquico (deriva niveles superiores desde el nivel inferior ya calculado)
   → Greedy mesher (fusiona caras coplanares del mismo color/material)
   → Serializador → cache en disco / respuesta de red
```

- Corre en thread pool separado del hilo principal, nunca en el hilo de render.
- **Modo LOCAL** (lee del `Level` directamente): usado por el servidor, o por el cliente en singleplayer.
- **Modo REMOTO** (pide datos al servidor vía red): usado por el cliente en multiplayer.
- Generación perezosa: solo se generan los niveles que el selector de LOD realmente pide en cada región, no todos por adelantado.

## 5. Formato binario (compartido disco y red)

**Supervóxel (8 bytes, tamaño fijo):**
```
color: 3 bytes | altura_local: 1 byte | material: 1 byte | flags: 1 byte | reservado: 2 bytes
```

**Nodo del octree:**
```
nivel_lod: 1 byte | flag_homogeneo: 1 byte | count: 2 bytes | data: RLE-encoded (SuperVoxel + run_length)
```

**Header de región (disco) / paquete (red):**
```
magic: 4 bytes | version: 1 byte | region_x,z: 4 bytes c/u | dimension_id: 1 byte
| hash_fuente: 8 bytes | tabla_offsets: N × 8 bytes (permite lectura parcial)
```

- RLE por nodo (aprovecha corridas largas de terreno natural); Deflate/GZIP solo a nivel de archivo completo, no por nodo, para no penalizar la lectura parcial.
- **Índice en disco append-only (0.25.5):** `r.X.Z.idx` = generación (8 bytes) + el header de arriba como foto completa + bloques de diario `cantidad: 4 | cantidad × (clave 8 | offset 8 | tamaño 8) | crc32: 4`. Cada lote de escritura agrega un bloque en vez de reescribir el índice (antes, 1,5 MB por región y por lote en regiones grandes); cuando el diario supera la mitad de los nodos se reescribe la foto (move atómico), así el costo queda amortizado. Un bloque cortado o con CRC mal termina el diario y se recorta en la próxima escritura. Un índice sin bloques es el formato anterior.
- Mismo serializador para disco y red — en red se omite el header repetido (ambas partes ya conocen la región/nodo pedido).
- Cache en RAM como LRU chico (lo visible + margen); disco (SSD) como fuente de verdad de todo lo demás, con lectura asíncrona y escritura diferida (write-behind, batch cada 2-5s).

## 6. Render

- Enganche vía `RenderLevelStageEvent` de NeoForge (etapa posterior a bloques sólidos vanilla), como pasada de render separada del terreno cercano (compatible con Embeddium, que maneja el terreno vanilla normal).
- Buffers agrupados **por región**, no por nodo individual — minimiza draw calls, crítico en iGPU (Vega 8).
- **Descarte por campo de visión (0.26.11, `render/CampoVision`):** cada celda
  (caja con la altura del mundo, bajada de la curvatura incluida) se prueba
  contra los 4 planos laterales de proyección × vista antes de dibujarla; sin
  near ni far, para no depender de la convención de profundidad de cada
  camino (OpenGL, Vulkan, shaderpacks). Medido: ~3× menos vértices por cuadro.
- **Blend entre niveles de LOD:** dithering por alpha con patrón Bayer fijo (screen-door transparency) vía `discard` en el fragment shader — no geometría interpolada (transvoxel), por ser mucho más barato en GPU integrada.
  - Duración: ~6-10 frames por transición.
  - Límite de nodos en blend simultáneo (ej. 20-30) para no duplicar demasiados draw calls a la vez con movimiento rápido.

## 7. Auto-ajuste de rendimiento (frame budget)

```
cada 1s:
    si frame_time_promedio > objetivo: umbral_px += paso (bajar detalle)
    si frame_time_promedio < objetivo × 0.8: umbral_px -= paso (subir detalle, con piso mínimo)
```

- Objetivo de presupuesto de frame para la pasada LOD completa: ~1.5-2ms en el peor caso (hardware de referencia).
- Puede pausar temporalmente el thread pool de generación si el frame time del hilo principal sube, priorizando fluidez del juego sobre precarga lejana.

## 8. Perfiles de calidad (presets)

| | Mínimo | Bajo | Medio | Alto | Ultra | Horizonte |
|---|---|---|---|---|---|---|
| Radio LOD (chunks) | 32 | 96 | 160 | 224 | 320 | 1024 |
| Umbral px inicial | 6.0 | 4.0 | 2.5 | 1.5 | 1.0 | 0.75 |
| Hilos de generación | 1 | 1 | 2 | 3 | 4 (núcleos-1) | 5 |
| Cache RAM (MB) | 100 | 250 | 500 | 900 | 1500 | 4000 |
| Colapso homogéneo desde | LOD1 | LOD1 | LOD2 | LOD3 | LOD4 | LOD5 |
| FPS objetivo sugerido | 24 | 30 | 40 | 50 | 60 | 45 |

Preset "Medio" calibrado como referencia para Ryzen 3500U / Vega 8 / 12GB RAM.
Preset "Mínimo" pensado para hardware pre-Zen como la Lenovo ThinkPad A275
(AMD PRO A12-8830B, 16GB) — ver sección 21. Preset "Horizonte" pensado para
hardware con GPU dedicada (ej. GTX 1060 + i5-9400) — ver sección 17 para el
objetivo visual que motiva este preset y por qué no aplica al 3500U ni a la
A275. Ni "Mínimo" (requiere detectar arquitectura de CPU, no solo núcleos)
ni "Horizonte" (requiere detectar GPU dedicada) se recomiendan de forma
completamente confiable por la heurística automática — ambos casos quedan
sujetos a elección manual o a la calibración real por benchmark (sección 9).

## 9. Calibración por benchmark

- **Mundo aparte** (`minecraftlodmod-benchmark`, decisión 2026-09-29 — antes
  era una dimensión custom) generado con seed fija y generador vanilla normal
  (terreno real, no sintético), con 4 puntos representativos (llanura, bosque
  denso, montaña, cueva) — ver `benchmark/PuntosBenchmark.java`. Al ser un
  mundo propio, la seed fija hace determinista toda la generación (terreno,
  estructuras, árboles) sin mixins, y se puede calibrar desde el menú
  principal sin tener un mundo del jugador.
- **Actualización 0.26.27:** 6 puntos (llanura, bosque, montaña, cueva,
  vista alta = 90 bloques sobre el terreno, vuelo a 20 b/s). Por punto quieto
  espera 8-60 s hasta que el LOD está listo (2 muestras del monitor sin mallas
  en cola ni generación) y mide 10 s; el vuelo, 3 s y 15 s en movimiento, y no
  decide el escalón (la primera vez genera terreno). Métricas por punto
  (`CalibradorBenchmark.MetricasPunto`): promedio, 1% peor, peor, carga del LOD
  y, del `MonitorRendimiento`, GPU, CPU, RAM, servidor, vértices, llamadas,
  VRAM. Pasa si promedio <= 90% del objetivo y 1% peor <= 2,5 × objetivo.
  Informe en `.minecraft/minecraftlodmod/benchmark/` (`InformeBenchmark`).
  "Medir rendimiento" = un solo escalón con la config actual. Al calibrar,
  `RecomendacionesBenchmark`: en el piso con la GPU >= 85% del cuadro, FSR 1;
  en el escalón más alto con <= 60% del objetivo y GPU con margen, oclusión en
  costados. El auto-ajuste queda congelado durante la sesión.
- Se genera una sola vez, cacheada igual que cualquier mundo; el cache de LOD
  se invalida solo si cambia el algoritmo (`GeneradorLocal.VERSION_ALGORITMO`).
- Flujo del botón "Calibrar" (solo desde el menú principal):
  1. Jugador elige preset inicial como punto de partida.
  2. Se crea (si no existe) y se abre el mundo de benchmark.
  3. Prueba por escalones alrededor del preset elegido (hacia arriba si sobra rendimiento, hacia abajo si falta), midiendo frame_time en cada punto representativo. Escalones: los presets + 2 intermedios por tramo; pasa si el promedio ≤ 90% del frame time objetivo.
  4. Guarda el resultado como config Personalizada.
  5. Cierra el mundo de benchmark y vuelve al menú principal.

## 10. Multiplayer

- Servidor con el mod instalado: corre el pipeline de generación completo (modo LOCAL), mantiene su propio cache en disco.
- Protocolo de red custom (NeoForge Payload/Network API): cliente pide nodos según su selector de LOD, servidor responde con datos ya generados/cacheados (mismo formato binario del punto 5).
- Si el servidor no tiene el mod: modo de compatibilidad, LOD generado localmente solo de zonas ya visitadas como chunks reales (igual que singleplayer sin companion).
- Invalidación de cache server-side ante cambios de bloques (evento → marcar sucio → regenerar en próxima pasada del thread pool).

## 11. Configuración (pantalla propia estilo Sodium desde 0.17.0; antes Cloth Config)

Desde la 0.17.0 la pantalla es propia (`config/PantallaLod` + `config/OpcionesLod`),
con el estilo de las opciones de Sodium: pestañas General / Calidad /
Generación / Depuración-Experimental, filas con nombre y control (casilla,
ciclo, deslizador o botón), panel con la descripción e impacto en el
rendimiento, y Deshacer / Aplicar / Hecho. Cloth Config dejó de usarse (una
dependencia menos). Desde la 0.18.0 reemplaza a Opciones > Video, como Sodium:
las primeras pestañas (Video, Gráficos) son las opciones de video vanilla,
armadas desde sus `OptionInstance` (`config/OpcionesVideo`), y después vienen
las del LOD. Si otro mod ya reemplaza esa pantalla (Sodium, Embeddium,
VulkanMod o el Vulkan integrado) no se la pisa y queda un botón "LOD" en ella.
El árbol original:

```
Config general
├── Preset de calidad (Bajo/Medio/Alto/Ultra/Personalizado)
├── [Botón: Calibrar desde este preset]
├── Sliders individuales (activos en modo Personalizado o post-calibración)
├── FPS objetivo (usado por calibración y auto-ajuste dinámico)
└── Auto-ajuste dinámico (toggle)
```

## 12. Módulos de código

```
core/          - octree, supervóxel, selector de screen-space error
generation/    - extractor, reductor jerárquico, greedy mesher, thread pool (LOCAL/REMOTO)
storage/       - serialización compartida, cache en disco, invalidación
render/        - buffers GPU por región, hook de render, blend/dithering
network/       - protocolo cliente-servidor, payloads
benchmark/     - dimensión custom, lógica de calibración
config/        - pantalla de opciones (estilo Sodium), presets, persistencia
```

## 13. Orden de implementación sugerido

1. `core` + `storage` (generación y guardado sin render, validar con dump a imagen o visor externo)
2. `render` básico sin selector dinámico (un solo nivel fijo, validar pipeline de GPU)
3. `generation` con thread pool real (modo LOCAL)
4. Selector de LOD dinámico (`core` + FOV vía `ViewportEvent.ComputeFov`)
5. Auto-ajuste de rendimiento
6. Blend/dithering entre niveles
7. `network` + modo REMOTO (soporte multiplayer)
8. `benchmark` (dimensión custom) + `config` (Cloth Config + presets + calibración)
9. Testing en hardware de referencia (Ryzen 3500U) y con Embeddium instalado/sin instalar

## 14. Puntos abiertos / a decidir en el camino

- Método exacto de heurística de preset recomendado por default (núcleos de CPU + RAM total, sin forzar).
- Tabla exacta de escalones de calibración (cuántas combinaciones intermedias entre presets).
- Manejo fino del trade-off de "información revelada" del relieve lejano en multiplayer (similar al de Distant Horizons).

## 15. Referencia: FarPlaneTwo (PorkStudios) — qué se adopta y qué no

Se usó como referencia para modernizar el enfoque, con las siguientes decisiones:

**Se adopta (conceptualmente, no el código):**
- *"Rough generators"*: en vez de generar terreno a calidad completa y reducirlo
  jerárquicamente (nuestro enfoque actual en `generation/`), estimar directamente
  el terreno aproximado explotando los internals del generador — evita el costo
  de correr generación de mundo completa para zonas nunca visitadas. No es
  necesario para el radio objetivo actual (160-320 chunks), pero es la mejora
  de mayor impacto si en el futuro se buscan radios mucho mayores. Queda como
  posible módulo futuro opcional, no parte del alcance inicial.
- Métrica de rendimiento objetivo expresada como "% máximo de degradación
  aceptable" en vez de solo un número de FPS — más robusto entre distintos
  hardware.

**NO se adopta (tensión directa con el objetivo de máxima compatibilidad):**
- Árbol de render off-heap y llamadas directas a OpenGL — FP2 prioriza
  rendimiento crudo por sobre compatibilidad ("compatible en la medida de lo
  razonable"). Este proyecto prioriza lo inverso: compatibilidad amplia con
  Embeddium/VulkanMod ante todo (sección 6 — solo `VertexConsumer`/`RenderType`,
  nunca GL crudo), aceptando el costo de rendimiento que eso implique.
- Dependencia de Cubic Chunks (mundos de altura infinita) — no aplica; este
  proyecto trabaja sobre el rango de altura vanilla parametrizado por dimensión
  (sección 2), que ya es más simple de resolver.

**Nota de contexto:** FP2 es un mod de Forge para Minecraft 1.12.2, sin puerto
confirmado a NeoForge 1.21.1 al momento de revisar el repositorio — no hay
código directamente reusable, solo ideas de arquitectura.

## 16. Optimizaciones de memoria/CPU adoptadas de FarPlaneTwo (sin costo de compatibilidad)

Separación clave: el layout de memoria en CPU (cómo se guardan los datos) es
independiente de cómo se dibuja en GPU (sección 6, sin cambios). Esto permite
adoptar la idea de "off-heap" de FP2 sin tocar la decisión de compatibilidad
con Embeddium/VulkanMod.

- **`core/PackedVoxelBuffer.java`** (implementado): almacenamiento off-heap
  de supervóxeles en un buffer plano de bytes (`ByteBuffer.allocateDirect`),
  8 bytes contiguos por vóxel, sin overhead de objeto Java ni dispersión en
  el heap. Capa opcional de alto rendimiento — convive con `SuperVoxel[]`,
  no lo reemplaza. Usar en los puntos calientes de `generation/`/`storage/`
  cuando el volumen de datos lo justifique (radios grandes).
- **Generación aproximada ("rough generation", inspirado en FP2)**: para
  regiones lejanas nunca visitadas, evaluar samplear el generador de mundo
  vanilla a baja resolución directamente en vez de generar a resolución
  completa y reducir después (lo que hace hoy `HierarchicalReducer`). Pendiente
  de investigar la API de `ChunkGenerator` de esta versión para validar
  viabilidad — no bloquea el resto del pipeline, que sigue funcionando con
  generación completa + reducción como fallback.

## 17. Meta visual: horizonte realista — implicaciones y trade-off

Objetivo: que el terreno lejano se vea como un horizonte real (varios
kilómetros de distancia percibida, desvanecido atmosférico), no solo "LOD
que anda bien". Esto tiene dos consecuencias de diseño importantes:

**1. Radio necesario, muy por encima de los presets calibrados (sección 8).**
Los presets Bajo/Medio/Alto/Ultra (96-320 chunks) alcanzan para terreno LOD
de buen rendimiento, pero no para un horizonte con sensación de distancia
real (del orden de decenas de miles de bloques). El COSTO DE RENDERIZAR ese
radio extra no crece mucho — el propio selector de screen-space error ya
colapsa el terreno muy lejano a poquísimos supervóxeles — pero el COSTO DE
GENERAR/ALMACENAR datos para un área tan grande sí crece significativamente.

**2. Tensión real con el objetivo de rendimiento en el Ryzen 3500U.**
Es la primera vez en el diseño que dos requisitos compiten de forma directa:
horizonte realista (radio muy grande) vs. buen rendimiento en hardware débil
(CPU de 4 núcleos). Esto **eleva "rough generation" (secciones 15-16) de
mejora futura opcional a requisito real** — sin estimar terreno lejano
directamente (sin pasar por generación vanilla completa), el radio necesario
para un horizonte creíble no es sostenible en este hardware. Este trade-off
queda anotado como decisión consciente, a resolver en la implementación real
investigando la API de generación de esta versión de Minecraft.

**3. Perspectiva atmosférica (implementado: `render/AtmosphericPerspective.java`).**
Mezcla el color del terreno lejano hacia el color de cielo/niebla actual
según la distancia (curva tipo exp2, igual de suave que la niebla vanilla).
Es la pieza que más aporta a la sensación de "vida real" en el horizonte —
más que la distancia cruda en sí — porque imita cómo el aire real dispersa
la luz. También sirve para disimular el borde del radio de renderizado
(ver "niebla en el borde" ya mencionado): con la densidad bien calibrada,
el factor llega a 1.0 antes del límite real del mundo generado, ocultando
el corte dentro de la niebla en vez de exponerlo.

## 18. Actualización del trade-off de horizonte realista: dos hardware objetivo distintos

La sección 17 anotaba una tensión entre horizonte realista y rendimiento en
el Ryzen 3500U. Esa tensión se resuelve al separar el objetivo por hardware:

- **Ryzen 3500U / Vega 8 / 12GB** → objetivo: buen LOD con buen rendimiento
  (presets Bajo/Medio/Alto/Ultra). El horizonte realista NO es un objetivo
  para este hardware.
- **GTX 1060 / i5-9400** (GPU dedicada, 6 núcleos) → objetivo: horizonte
  realista (preset Horizonte, sección 8). Con 6 núcleos reales y VRAM
  dedicada, la generación completa + reducción jerárquica (ya implementada)
  alcanza para ser viable sin necesitar "rough generation" todavía — esa
  optimización pasa a ser una mejora deseable para estirar el radio aún más,
  no una condición de viabilidad como se había anotado antes para hardware
  más débil.

Esto no cambia nada del código ya escrito (`core`, `storage`, `generation`,
`config`) — solo agrega el preset `HORIZONTE` en `QualityPreset.java` y dejar
constancia de que el techo de la heurística automática de recomendación
sigue siendo `ULTRA`, ya que no detecta GPU dedicada.

## 19. Horizonte dinámico según altura (implementado: `core/DynamicHorizonRadius.java`)

Reconcilia "maximizar rendimiento" con "horizonte dinámico según altura" en
vez de tensionarlos:

- El radio efectivo **nunca supera el techo del preset de calidad actual**
  (`radioBaseChunks` = `QualityPreset.radioLodChunks` o el resultado de la
  calibración) — esto es lo que garantiza que nunca se le pide al hardware
  más de lo que ya se determinó que puede sostener.
- A baja altura (jugador al nivel del terreno, en un valle, o bajo tierra),
  el radio usado es una fracción del techo (35% por defecto) — ahorro real
  de generación/memoria, porque a esa altura el terreno lejano estaría tapado
  por el relieve cercano de todos modos (mismo principio que el culling por
  heightmap ya anotado en la lista de mejoras).
- A gran altura, el radio se acerca asintóticamente (curva de saturación
  exponencial, misma familia que la niebla atmosférica de la sección 17) al
  techo completo — ahí es donde se paga el costo completo del horizonte,
  porque ahí es donde realmente se aprecia.

**Pendiente de integración real (hito de implementación):** `alturaSobreTerreno`
requiere conocer la altura máxima del terreno alrededor del jugador (un
heightmap simplificado por región, ya mencionado en la lista de mejoras de
culling) — no es simplemente `playerY`, sino `playerY - alturaMaximaCercana`,
para que "altura" refleje elevación relativa real (un jugador parado en la
cima de una montaña alta pero rodeado de terreno igual de alto no debería
obtener el radio máximo). El resultado de este cálculo alimenta tanto la
prioridad de generación/streaming como, opcionalmente, el radio que usa el
propio `LodSelector` al recorrer el octree.

## 20. Posicionamiento frente a Distant Horizons y Voxy — la tensión de fondo

Investigado el estado real de Distant Horizons (DH) antes de fijar esta meta:

- **DH no usa el renderer normal de Minecraft** — tiene pipeline propio,
  independiente de vanilla. Es la razón por la que alcanza radios de
  256-512+ chunks con buen FPS, pero también por la que solo funciona con
  shaders específicamente adaptados a DH (Iris 1.7+, "shaders pensados para
  DH"), no con shaders genéricos ni necesariamente con reemplazos de
  renderer como VulkanMod.
- Es la misma estrategia que FarPlaneTwo (sección 15): priorizar rendimiento
  crudo por sobre compatibilidad amplia.
- Voxy (inspiración original de este proyecto) está más alineado con la
  filosofía de compatibilidad de este documento, pero paga ese costo en
  radio máximo alcanzable frente a DH.

**Conclusión de diseño:** no es posible, con un único backend de render,
maximizar compatibilidad Y superar el techo de rendimiento de un mod que
deliberadamente sacrifica compatibilidad para lograrlo (DH). Es una elección
real de prioridad entre calidad visual, rendimiento máximo y compatibilidad
máxima — no una que se resuelva "optimizando más".

**Decisión adoptada: backend de render intercambiable (implementado como
interfaz: `render/RenderBackend.java`):**

- **Backend compatible (default):** `VertexConsumer`/`RenderType` de
  Minecraft — máxima compatibilidad con shaders y reemplazos de renderer
  razonables. Es lo que se venía diseñando desde la sección 6, sin cambios.
- **Backend de alto rendimiento (opt-in, futuro):** bypass del renderer
  vanilla al estilo DH/FP2, activable explícitamente por el usuario que
  prioriza radio/FPS por sobre compatibilidad de shaders — mismo trade-off
  que esos mods, comunicado con claridad en la config (nunca activado por
  defecto).

Esto permite, en el modo de alto rendimiento, apuntar a superar a DH en su
propio terreno cuando el usuario lo elige — sin que el modo por defecto dejе
de ser el más compatible de los tres mods comparados, que es en sí mismo una
forma de "superarlos" en el eje que este proyecto prioriza por defecto.
`core`/`generation`/`storage` no cambian: ambos backends consumen los mismos
`Quad` de `GreedyMesher` y la misma luz de `VertexLightSampler`.

## 21. Rango de hardware: de Lenovo ThinkPad A275 a gama muy alta, piso de 30fps

**Hardware investigado:** la Lenovo ThinkPad A275 usa un AMD PRO A12-9800B
(arquitectura Excavator, pre-Zen, considerablemente más débil por núcleo que
el Ryzen 3500U) con gráficos Radeon R7 Bristol Ridge. Es un escalón real por
debajo de todo lo calibrado hasta ahora (sección 8, presets Bajo-Horizonte).

**Límite honesto:** no se puede garantizar 30fps en este hardware ni sin el
mod instalado — Minecraft 1.21.1 en un CPU de esa generación ya es ajustado
de por sí. La meta de diseño realista es que el mod aporte **cero overhead
perceptible** cuando el margen no alcanza, en vez de prometer un piso de FPS
que no depende solo del mod.

**Cambio de diseño: auto-ajuste de dos perillas (implementado:
`core/PerformanceAutoTuner.java`)**, extendiendo la sección 7:

1. `umbralPx` (como ya existía): se ajusta primero — menos intrusivo
   visualmente.
2. `radioActivo` (nuevo): separado del techo del preset (`radioMaximo`).
   Cuando ni el umbral más permisivo alcanza, se reduce el radio realmente
   en uso — hasta un mínimo configurable, potencialmente 0 (LOD
   efectivamente apagado, solo terreno vanilla). Recién ahí el mod deja de
   poder ayudar más — eso es "cero overhead posible" en ese hardware.

Simétricamente, en hardware con margen de sobra (PC de gama alta), primero
se recupera el radio hasta el techo del preset, y recién después se mejora
el detalle — así en gama alta el sistema prioriza extender el radio (más
impactante visualmente) antes que afinar detalle por sección.

`enPisoAbsoluto()` señala cuándo el sistema llegó al mínimo de ambas
perillas — útil para mostrarle al jugador en la UI que está en el límite de
lo que el mod puede hacer por su hardware, en vez de fallar silenciosamente.

**Integración pendiente (hito de implementación):** el bucle que llama a
`ajustar()` cada ~1s ya estaba anotado en la sección 7; ahora alimenta esta
clase de dos perillas en vez de solo `umbralPx`. El `radioMaximo` que se le
pasa es el techo del preset de calidad activo (`QualityPreset.radioLodChunks`
o el resultado de la calibración), no un valor fijo global.

## 22. Ajuste fino: objetivo de 24fps para la A275, no 30

Se agrega el preset `MINIMO` (sección 8, implementado en `QualityPreset.java`)
como escalón explícito por debajo de `BAJO`, con radio de LOD bajo (32
chunks) y objetivo de FPS sugerido de 24 en vez de 30 — reconociendo que en
este hardware (Excavator pre-Zen) ni 30fps es una meta base realista, y que
forzarla llevaría al `PerformanceAutoTuner` (sección 21) a recortar radio
más de lo necesario o a quedar permanentemente en el piso.

El campo `objetivoFpsSugerido` por preset es un punto de partida para la
calibración (sección 9), no un mínimo garantizado por el mod — el propio
frame budget objetivo que alimenta `PerformanceAutoTuner.ajustar()` debería
tomar este valor como default al elegir el preset "Mínimo", en vez del 30fps
que aplicaría a los demás presets.

## 23. Reparto de carga entre todos los hilos (implementado: `generation/GenerationTaskScheduler.java`)

Reemplaza la idea original de "cola compartida + N hilos consumiéndola" por
**work-stealing** (`java.util.concurrent.ForkJoinPool`, estándar de Java, sin
dependencias de Minecraft):

- Cada hilo del pool tiene su propia cola de tareas; cuando se queda sin
  trabajo, roba tareas de la cola de otro hilo que todavía tiene pendientes.
  Soluciona el problema real de que las tareas de generación tienen costo
  muy dispar (sección vacía/homogénea vs. terreno mixto complejo) — con una
  cola FIFO compartida, un hilo puede quedar "colgado" en una tarea pesada
  mientras otro se queda ocioso; con work-stealing, el reparto se equilibra
  solo.
- **Límite de concurrencia ajustable en caliente**, separado del tamaño del
  pool: un semáforo cuyo límite puede subir o bajar en tiempo real sin
  recrear el pool (evita el costo de destruir/crear threads). Este es el
  punto de integración con `PerformanceAutoTuner` (sección 21) — se puede
  sumar como una TERCERA perilla (además de `umbralPx` y `radioActivo`):
  cuántas tareas de generación corren en simultáneo, bajándolo antes de
  tocar el radio si hace falta throttlear generación sin sacrificar área
  visible todavía cacheada.
- Reducir el límite es intencionalmente "eventual" (no cancela tareas en
  curso, solo retiene permisos para las siguientes) — throttling suave, no
  interrupción abrupta, que es lo correcto para no perder trabajo de
  generación ya en curso.

**Pendiente de integración real:** conectar esta tercera perilla a
`PerformanceAutoTuner`, y decidir el orden de prioridad entre las tres
(sugerido: primero bajar concurrencia de generación, después subir
`umbralPx`, recién al final bajar `radioActivo` — porque reducir generación
es lo menos perceptible visualmente de las tres).

## 24. Estabilidad y uso de RAM: cache con límite real + backpressure

Dos huecos que quedaban abiertos en el diseño, ahora cerrados:

**1. `storage/BoundedRegionCache.java` (implementado):** cache LRU en memoria
con límite REAL de bytes — hasta ahora `QualityPreset.cacheRamMb` era un
número en el preset sin nada que lo hiciera cumplir. Sobre `LinkedHashMap` en
modo access-order (Java estándar), con control manual de tamaño por bytes
totales (no por cantidad de entradas, porque el tamaño de un nodo serializado
varía mucho según cuántas corridas RLE tenga). Lo desalojado no se pierde:
sigue en disco (sección 5), así que un miss de este cache cuesta una lectura
de SSD, no regenerar desde cero.

**Actualización 0.26.6:** sobre `Long2ObjectLinkedOpenHashMap` (fastutil)
en vez de `LinkedHashMap<Long, byte[]>`, y el límite cuenta datos +
`COSTO_ENTRADA` (48 B) por entrada: los nodos promedian ~43 B y el costo
fijo no contado hacía que el cache ocupara ~3× su presupuesto (medido 145 MB
"usados" vs ~450 MB reales; heap del juego 1133 → 856 MB).

**2. Backpressure en `generation/GenerationTaskScheduler.java` (implementado):**
`maxTareasEnCola` acota cuántas tareas pueden estar pendientes/en ejecución a
la vez. Sin este tope, un jugador moviéndose rápido podría hacer que el
selector de LOD encole generación más rápido de lo que el pipeline puede
procesar, acumulando contexto de generación en RAM sin límite. Con el tope,
`enviar()` bloquea al llamador cuando la cola está llena — presión hacia
atrás natural, sin necesitar lógica adicional en quien pide la generación.

**Integración pendiente:** `maxTareasEnCola` y el tamaño de
`BoundedRegionCache` deberían derivarse de `QualityPreset.cacheRamMb` (más
un estimado de tamaño promedio de tarea) al construir el scheduler/cache
para cada preset, no quedar como valores libres.

## 25. Análisis de Voxy y FarPlaneTwo: rendimiento sin perder calidad (2026-09-29)

**Licencias:** Voxy (MCRcortex) es "All rights reserved, do not
redistribute" → solo se toman IDEAS, nunca código. FarPlaneTwo
(DaPorkchop_) es MIT → se puede adaptar código con crédito explícito
(autor + enlace al repo) en el archivo que lo use.

Medición de partida (preset Medio, 1080p, antes del descarte de cuevas):
~34M vértices / 17M triángulos / ~1,4 GB de VRAM — inviable en iGPU.

Ideas adoptadas, en orden de implementación sugerido:

1. **Formato de vértice compacto (idea de Voxy) — implementado: 12 B/vértice
   (`GeometriaLod.escribirCompacto`, tabla de sprites en `PaletaTexturas`).** Voxy guarda un quad en
   64 bits (posición 5+5+5, tamaño 4+4, cara 3, estado, bioma, luz) y
   reconstruye los vértices en el shader. Nuestro equivalente compatible
   (sin GL crudo): `VertexFormat` propio con elementos empaquetados
   (posición relativa a la celda en shorts, color+luz en un int, UV
   derivada de posición+cara en el shader) → ~8-12 B/vértice contra ~36
   actuales, 3-4× menos VRAM y ancho de banda. Sin pérdida de calidad.
2. **Mip por bloque representativo + paleta (idea de Voxy) — implementado
   el bloque representativo en `HierarchicalReducer`; color en cliente y
   paleta pendientes (ver NOTES.md).** En vez de
   promediar color, el nivel superior elige el hijo más representativo
   (más opaco, preferencia al de arriba) y guarda `estado`; el color se
   resuelve en el cliente con `ColoresBloque`/resource pack activo. Mejora
   nitidez lejana (sin "barro" promediado), arregla colores en
   multiplayer y permite almacenamiento por paleta (índices de 1-2 bytes
   + Deflate) → nodos en disco/RAM mucho más chicos.
3. **Agrupación de quads por dirección de cara (Voxy) — implementado: un
   `VertexBuffer` por dirección y celda, dibujados como el terreno vanilla.** Seis rangos por
   malla; se omite el dibujo de las caras que miran en sentido contrario
   a la cámara (hasta ~50% menos triángulos procesados) usando rangos de
   `VertexBuffer` por dirección — sin GL crudo.
4. **Culling de caras entre secciones vecinas (Voxy) — implementado con
   `GreedyMesher.Vecinos`; costados de teselas pendientes.** Máscara de
   opacidad del borde de la sección vecina al mallar: elimina caras
   internas en los límites de sección/tesela (hoy solo se omiten caras
   laterales cubiertas a nivel celda).
5. **Oclusión ambiental horneada por vértice — implementado en
   `GreedyMesher`/`GeometriaLod`, opción `oclusionAmbiental`.** Voxy usa SSAO de
   postproceso; lo nuestro compatible es AO por vértice al mallar (0 costo
   en GPU, gran aporte de profundidad visual). **Desde 0.23.0 también SSAO**
   (`render/AcabadoLod`, opción `oclusionPantalla`): pasada de pantalla con
   `ShaderInstance` sobre la profundidad del LOD, antes de que se limpie; el
   radio crece con la distancia. En la misma pasada va la niebla del LOD
   (borde igual al de vanilla corrido + neblina atmosférica exp2), que
   hasta ahí no existía. Sin GL crudo; no corre con VulkanMod ni shaderpacks.
   **Luz de bloque aparte (idea de Voxy) — implementado en 0.24.0:** bits
   2-3 de `flags` (`SuperVoxel.luzBloque`, 0-3, máximo al reducir), bits
   14-15 del short de sprite en el vértice compacto (sprites hasta 16383);
   el shader toma `max(luz del cielo a esta hora, luz cálida de bloque)`.
   Datos viejos quedan en 0 (sin romper formato). Mismo hito: tinte de
   bioma mezclado como el biome blend de vanilla (`LectorSeccionMinecraft`,
   biomas de los chunks vecinos cargados).
6. **Exclusión exacta del área vanilla (FP2, "vanilla renderability").**
   Máscara por chunk de qué renderiza vanilla realmente, en vez de un radio
   fijo → sin geometría LOD duplicada bajo el terreno cercano ni huecos.
7. **Generación aproximada por funciones de densidad (FP2, MIT) — implementado:
   `GeneradorAproximado` + `TerrenoAproximado` (niveles 3-4 por chunk, claves propias).** Para el
   horizonte de varios km: samplear `NoiseRouter.finalDensity` de
   1.21 a baja resolución para regiones nunca visitadas, sin generar
   chunks. Es lo que hace sostenible el preset Horizonte (secciones 17-18).
8. **Texturas horneadas de modelos no cúbicos (Voxy) — implementado en
   0.22.0: `render/AtlasLod` + `PaletaTexturas`.** Los modelos que no son
   cubos simples se rasterizan por software (vista de arriba y de costado,
   con prueba de profundidad y recorte por alfa) a teselas de un atlas propio
   del LOD con mipmaps por tesela; los huecos del follaje van oscurecidos.

**NO adoptado en el backend por defecto:** HiZ occlusion + recorrido
jerárquico en GPU por compute + multidraw indirecto de Voxy (requiere GL
4.5/4.6, int64 en shaders, llamadas GL directas) y árbol de render
off-heap con GL crudo de FP2. Ambos rompen la regla de compatibilidad
(secciones 6, 15, 20) y el hardware de la A275 no los soporta bien; quedan
como candidatos para el backend de alto rendimiento opt-in (sección 20).

## 26. Escalado AMD FSR 1 (experimental, opt-in) — primera excepción a "solo eventos"

Pedido: bajar el costo de GPU en hardware donde la GPU es el límite (A275,
3500U a 1080p). FSR 1 (AMD FidelityFX, MIT) escala una imagen de menor
resolución con EASU (reconstrucción de bordes) + RCAS (nitidez).

**Decisión:** implementado en `render/EscaladoFsr.java`, APAGADO por defecto,
con dos mixins en `render/mixin/` (el único paquete de mixins del mod):
`Minecraft.getMainRenderTarget()` devuelve un framebuffer chico mientras
`GameRenderer.render` dibuja el mundo, y después se escala al framebuffer
real; interfaz, contorno de entidades y efectos de pantalla quedan a
resolución completa. Las pasadas usan solo abstracciones de Minecraft
(RenderTarget, ShaderInstance, BufferUploader).

Es la primera vez que el mod entra al cuadro de Minecraft con mixins (hasta
acá, solo eventos de NeoForge — secciones 6 y 20). Por eso es opt-in y se
desactiva solo donde choca: gráficos Fabulous (framebuffers de transparencia
del tamaño de la ventana) e Iris/Oculus (reemplazan este tramo). Con
Embeddium debería convivir: falta probarlo en hardware real (Pista B), igual
que la ganancia de FPS y la calidad visual.


## 27. Vulkan, shaders y escaladores temporales (FSR 2 / XeSS / DLSS) — 2026-09-30

Pedido: poder usar Vulkan (con interruptor), compatibilidad con shaders, y
como objetivos XeSS (segunda prioridad) y DLSS (si se puede).

**1. Vulkan = VulkanModNeoForge, no un renderer propio.** (Actualizado en
0.15.0: el código de VulkanMod pasó a estar integrado, ver al final de este punto.)
El que usa el jugador es VulkanModNeoForge 0.5.5-dev+3.1 (yiyuyan, fork de
VulkanMod de xCollateral, LGPL-3.0). Reemplaza el renderer entero al
arrancar: no se puede prender/apagar en caliente, y meterlo como jar-in-jar
lo dejaría siempre prendido. Decisión: no se incluye; el LOD se adapta si
está y el interruptor del menú (`config/ConmutadorVulkan`) lo activa o
desactiva para el próximo arranque renombrando el jar (`.jar.disabled`, la
convención de Modrinth). Apagarlo mientras corre el juego lo hace un
proceso aparte cuando el juego cierra (en Windows un jar cargado no se
puede renombrar).

Qué rompía con VulkanMod (reproducido con lavapipe en Xvfb):
- Shaders propios (`lod_textura`, FSR): su conversor GLSL→SPIR-V no los
  toma y quedan sin pipeline → NullPointerException al primer dibujo (el
  crash de la 0.9.0). Con VulkanMod no se registran; el LOD usa el camino
  de colores planos con el shader vanilla `position_color`, que VulkanMod
  trae portado. FSR 1 queda apagado.
- `VertexBuffer.draw()` no vuelve a subir uniforms: el truco de un shader
  por pasada + `ChunkOffset` por buffer no sirve ahí (sí `drawWithShader`).
- Profundidad: si su "depthFix" (necesita `jdk.attach`, que el Java de
  Modrinth no trae) funciona, las proyecciones salen en 0..1; si no, en
  -1..1. `PlanCeldas.conPlanosDeProfundidad` detecta la convención de la
  matriz que llega y la respeta.

Texturas con VulkanMod (0.13.0): mismo formato compacto de 12 B, descripto
para VulkanMod como dos elementos UV SHORT×2 (arma el atributo de Vulkan por
uso y tipo, sin mirar la cantidad) y una variante del shader
(`lod_textura_vk`) que su conversor línea por línea acepta: sin `flat`, sin
`%`, samplers declarados solo en el vértice, sin `ChunkOffset` (dibujo con
`drawWithShader`). Se crea con el constructor de `ShaderInstance` que recibe
String, el único que VulkanMod intercepta. Pendiente: FSR con VulkanMod.

Buffer de índices de VulkanMod (0.14.0): su `AutoIndexBuffer` de quads es
compartido (65536 vértices al arrancar) y al crecer libera el anterior aunque
otros VBO lo sigan usando → índices basura ("espigas"). Con VulkanMod el LOD
parte las mallas en piezas de hasta 65536 vértices, así nunca lo hace crecer.

**VulkanMod integrado (0.15.0, pedido del usuario: no depender de otro mod).**
El código del fork (LGPL-3.0) vive en el paquete `vulkanmod/`, con los
paquetes renombrados. Reemplaza la decisión anterior de "no se incluye":
- Interruptor al arrancar: `config/ConmutadorVulkan` guarda el pedido en
  `config/minecraftlodmod-vulkan.properties`; el `MixinPlugin` de VulkanMod
  no aplica ningún mixin si Vulkan está apagado (el juego queda en OpenGL
  sin cambios). Prenderlo apaga `earlyWindowControl` en `fml.toml`.
- Librerías: lwjgl-vulkan/vma/shaderc (Windows y Linux) dentro del jar; los
  módulos de Fabric que usa su armado de bloques (FRAPI), como jar-in-jar.
- El VulkanMod suelto ya no puede instalarse junto (paquetes de LWJGL
  repetidos); el LOD lo sigue reconociendo si está (`RenderLod.conVulkanMod`).

Escalado según la GPU (0.14.0): `config/CompatibilidadEscalado` decide qué
modos se ofrecen (DLSS solo RTX, XeSS con DP4a o Intel Xe, ambos solo en
Windows; con VulkanMod, ninguno) y el menú muestra solo esos.

**2. Shaders (Iris) — implementado el paso (a).** Con un shaderpack activo
(`render/ShadersIris`, API de Iris por reflexión, Iris opcional):
- El LOD se arma en el formato de bloque extendido de Iris
  (`IrisVertexFormats.TERRAIN`, 52 B: el `BLOCK` de vanilla + mc_Entity,
  mc_midTexCoord, at_tangent, at_midBlock; `GeometriaLod.escribirBloque`) y se
  dibuja con `GameRenderer.getRendertypeSolidShader()`, que Iris cambia por el
  `gbuffers_terrain` del pack: el pack lo ilumina como al resto del terreno.
  Con el `BLOCK` de 32 B Iris lee los vértices corridos (espigas).
- Color sin luz ni sombra por cara, luz horneada en el lightmap, normal por
  cara, textura blanca 1×1 (el color ya es el promedio de la textura).
- Proyección de vanilla sin tocar (el pack reconstruye posiciones con ella),
  con el far estirado hasta el alcance del LOD (mixin de
  `GameRenderer#getDepthFar`) y sin limpiar la profundidad.
- Límite conocido: el uniform `far` de Iris sale de la distancia de render
  vanilla, así que la "niebla de borde" de muchos packs tapa el LOD más allá
  de esa distancia (en Complementary se puede apagar esa niebla).
- Verificado en Xvfb con un shaderpack mínimo propio; Complementary en render
  por software sale todo niebla, no se pudo juzgar (Pista B).

Los shaderpacks ya definen contratos para mods de LOD, que serían el paso (b):
- Distant Horizons: programas `dh_terrain`/`dh_water`/`dh_shadow`,
  `dhDepthTex0/1` y matrices `dhProjection`; Iris los alimenta desde la API
  de DH (específico de DH).
- Voxy: `voxy.json` + `voxy_opaque.glsl`/`voxy_translucent.glsl` en el pack
  (Complementary r5.9 los trae): el pack aporta el código de fragmento y la
  lista de uniforms/samplers; el mod de LOD lo compila con su propia entrada
  de vértices y le da `vx*` (proyección, profundidad propia). Implementarlo
  (solo leyendo archivos del pack, sin código de Voxy) daría el LOD sin la
  niebla de borde y con iluminación completa en los packs que ya soportan
  Voxy. Requiere acceso a los render targets y uniforms de Iris.
  **Etapa A implementada (0.25.3):** `render/ContratoVoxy` (lógica pura: json
  laxo, GLSL de vértice y fragmento) + `render/ShadersVoxy` (lado Iris,
  Iris como dependencia solo de compilación). Mixins sobre Iris
  (`MixinShaderPackIris`, aplicados solo si Iris está, `PluginMixins`): los
  archivos voxy se suman al grafo de includes de Iris y se preprocesan con
  sus mismas definiciones más `VOXY`. Tipos de uniforms: primero las
  declaraciones de los programas normales del pack, después los que registra
  Iris. Compila y enlaza (0.25.3).
  **Etapa B (0.26.8, opción experimental `contratoVoxy`):** `VOXY` para todo el
  pack (deferred/composite leen el LOD); uniforms `vx*` y samplers
  `vxDepthTexOpaque/Trans` agregados a los de Iris (`MixinUniformesIris`,
  `MixinSamplersIris`); `render/DibujoVoxy` arma el programa como el
  `IrisLodRenderProgram` de Iris (uniforms, samplers e imágenes del pack) y
  dibuja en `AFTER_SKY` sobre los colortex de `opaqueDrawBuffers` con una
  profundidad propia (la de translúcidos es copia), así vanilla tapa al LOD
  después. Las matrices `vx*` también se suben directo al programa (los
  uniforms propios de Iris se actualizan una vez por cuadro) y la
  profundidad vale para el cuadro de Iris en que se dibujó. Iris 1.8 admite
  16 colortex y Complementary con `VOXY` usa 18 y 19: con el contrato se
  amplía a 32 (`MixinObjetivosIris`); si el pipeline igual falla, el pack se
  recarga sin `VOXY` (`MixinPipelineIris`).
  **0.26.9:** formato de vértice propio del contrato (`RenderLod.FORMATO_VOXY`,
  16 B: el compacto con el color sin sombra por cara + un int con estado de
  bloque, luz de cielo horneada y marca de agua). `customId` = tabla R32I
  estado → id del `block.properties` del pack (`WorldRenderingSettings` de
  Iris), `lightMap` con la luz de cielo real. El agua va a un grupo aparte
  (`GeometriaLod.GRUPO_AGUA`, solo la superficie) y se dibuja con
  `voxy_translucent` sobre una copia de la profundidad opaca, con la mezcla
  del json por salida; sin ese archivo, con el opaco. Pendiente: sombras del
  LOD (NOTES.md).

Con Sodium/Embeddium, `LevelRenderer#isSectionCompiled` da false aun para
secciones a la vista: ahí el LOD le cede un chunk a vanilla cuando lleva 2 s
cargado en el cliente (`RenderLod.vanillaLoDibujo`).

**3. Escaladores temporales.** FSR 2/3, XeSS y DLSS necesitan lo mismo, y es
~80% del trabajo (según el proyecto de referencia minecraft-dlss, MIT):
proyección con jitter por cuadro, vectores de movimiento (Minecraft no los
genera: reproyección de cámara desde la profundidad + entidades aparte),
profundidad y máscara reactiva (agua, partículas). Por hardware:
- **FSR 2/3 (AMD, MIT):** corre en todo (GTX 1060, Vega 8, R7 de la A275).
  Es el primero a implementar: se puede portar a shaders de cómputo GL sin
  Vulkan.
- **XeSS (Intel, licencia propia, DLL solo Windows):** tiene backend Vulkan,
  no OpenGL. En GPUs no Intel necesita DP4a/SM 6.4: GTX 1060 sí; Vega 8 y
  R7 (A275) probablemente no (a verificar). Camino: dispositivo Vulkan
  interno + interop GL↔Vulkan (`GL_EXT_memory_object_win32` y semáforos),
  llamando a `libxess.dll` desde Java con LWJGL (sin C++).
- **DLSS (NVIDIA):** solo RTX (Turing o más nuevas): ninguno de los equipos
  de referencia lo puede usar. Mismo interop que XeSS; la DLL de NVIDIA no
  se redistribuye, la aporta el usuario. Última prioridad.

**4. Implementado (0.12.0) — base temporal, puente Vulkan, XeSS y DLSS.**
- `config/ModoEscalado` reemplaza el on/off de FSR: APAGADO, FSR1, TEMPORAL
  (propio), XESS, DLSS. Los tres últimos comparten la base temporal de
  `render/Escalado`:
  - Jitter de Halton 2/3 (`SecuenciaJitter`, fases como FSR 2) aplicado en
    `GameRenderer#getProjectionMatrix` (mixin): mundo, mano y frustum
    coherentes.
  - Profundidad de la escena y matrices capturadas en `AFTER_LEVEL`, antes
    de que la mano limpie la profundidad; los píxeles de la mano llevan
    movimiento cero.
  - Vectores de movimiento reconstruidos desde la profundidad
    (`MovimientoCamara`, shader `escalado_movimiento`), solo cámara: las
    entidades que se mueven quedan con el de la cámara (estelas posibles).
  - TEMPORAL: acumulación con historial RGBA16F, recorte por varianza en
    YCoCg, velocidad del vecino más cercano (`escalado_temporal`) + RCAS.
- `render/InteropVulkan`: dispositivo Vulkan propio en la misma GPU (UUID
  de `GL_EXT_memory_object`), imágenes con memoria exportable importadas en
  OpenGL (fd en Linux, handles en Windows), sincronización glFinish + fence.
  lwjgl-vulkan va reubicado dentro del mod (`com.example.minecraftlodmod.lwjglvk`,
  plugin Shadow) para no chocar con el de VulkanMod. Verificado en Xvfb con
  llvmpipe + lavapipe y el escalador de prueba `EscaladorBlit`
  (-Dminecraftlodmod.pruebaVulkan=true).
- `render/EscaladorXess`: libxess.dll del usuario en
  `.minecraft/minecraftlodmod/`, API C de xess_vk.h por JNI de LWJGL
  (structs en `XessParametros`, headers MIT).
- `render/EscaladorDlss`: Streamline 2.14 (MIT) en
  `.minecraft/minecraftlodmod/streamline/`, hookeo manual + slSetVulkanInfo,
  structs en `StreamlineParametros`, llamadas de 5 argumentos por libffi.
- XeSS y DLSS no se pueden probar acá (DLL solo Windows, GPU real): falta
  Pista B. Si fallan al cargar o al ejecutar, el escalado cae solo al
  TEMPORAL y lo dice en el log. `invertirJitter` (experimental) por si la
  convención del signo del jitter resulta la contraria.

## 28. Auto-ajuste según el cuello de botella (CPU o GPU) — 2026-09-30

Pedido: detectar en tiempo real si el límite es el CPU o la GPU y pasar
trabajo al que está más libre, para más FPS y sobre todo más estabilidad.

**Medición (sin GL crudo):** tiempo de cuadro y `TimerQuery` de Minecraft
para el tiempo de GPU del cuadro (con F3 abierto se lee el de Minecraft).
GPU ocupada casi todo el cuadro = límite GPU; si no, límite CPU. Con
VulkanMod no hay medición de GPU: se ajusta con el orden genérico.

**Qué se puede mover de un lado al otro:** no hay tareas con dos
implementaciones (generar mallas en GPU con compute rompería la regla de
compatibilidad, secciones 6/15/20). Sí hay perillas que cambian CPU por GPU:
- distancia de agrupado de caras (buffer por dirección = más llamadas y
  menos triángulos de espaldas, un buffer = lo contrario);
- radio de los oclusores del relieve (CPU por plan contra celdas dibujadas);
- escala del escalado (GPU), si hay escalado;
- generación simultánea (CPU; es la que causa tirones).

**Orden (`core/BalanceadorCpuGpu`, una perilla por segundo):**
- GPU al límite: agrupado ↓, oclusión ↑, escala ↓, recién después detalle ↓ y radio ↓.
- CPU al límite: generación ↓, agrupado ↑, oclusión ↓, después detalle y radio.
- Tirones con promedio bueno: generación ↓.
- Con margen (2 ciclos seguidos por debajo del 80% del objetivo): se
  recupera generación, radio, detalle y escala; las perillas invisibles de
  reparto quedan donde están.
Reemplaza en la práctica a `PerformanceAutoTuner` (secciones 7, 21, 23),
que nunca llegó a conectarse al juego.


## 29. Vóxeles grandes como terreno, nubes lejanas y curvatura — 2026-09-30

**Texturas de vóxeles grandes.** La textura se repite una vez por bloque
(misma escala que vanilla), así que el costado de un vóxel de pasto de 16
bloques mostraba 16 líneas de pasto. Ahora el vértice compacto lleva el
tamaño del vóxel (log2, bits 3-7 del byte de alfa, junto a la cara) y la
tabla de sprites un cuarto texel con el sprite "de abajo": en los costados
con franja (`ColorTextura.tieneFranja`: el cuarto de arriba distinto de la
mitad de abajo, y esa mitad parecida a la cara de abajo del bloque — pasto,
nieve, micelio, podzol), el shader pone la franja solo en la fila de arriba
de cada vóxel y la textura de abajo (tierra) en el resto. El resto de los
bloques (piedra, troncos) sigue repitiéndose por bloque, como el terreno
cercano. Opción `texturasComoTerreno`.

**Nubes lejanas (`render/NubesLejanas`).** Vanilla arma sus nubes en un
cuadrado de ~700 bloques y las corta con su plano lejano (4× la distancia de
render). Capa plana propia en la pasada del LOD (shader `lod_nubes`): misma
textura, altura y desplazamiento que vanilla (ticks del LevelRenderer por AT),
hueco donde vanilla dibuja, 4 muestras por píxel y paso a la cobertura media
cuando los texeles son más chicos que un píxel (sin mipmaps titilaría), y
fundido al color de niebla hacia el alcance del LOD. Sin VulkanMod ni
shaderpacks (tienen sus nubes).

**Curvatura (`core/HorizonteCurvo`).** El LOD baja `(d - d0)² / 2R` con d0 = borde
de vanilla (vanilla no se curva; la pendiente arranca en cero, sin escalón) y
R configurable (Tierra 1:1 = 6371 km por defecto; Marte, Luna y planetas de
juguete). Por vértice en `lod_textura` y en las nubes; por celda entera donde
el shader no es nuestro (colores planos, VulkanMod, shaderpacks).
**Horizonte real:** el radio del LOD sale de
`sqrt(2Rh) + sqrt(2R·32)` (horizonte desde los ojos, h sobre el nivel del mar,
más una colina de 32 bloques detrás), con tope `RADIO_MAX`; el auto-ajuste lo
recorta en la misma proporción que al radio del preset, y la generación
aproximada genera hasta ahí (`GeneradorLocal.radioHorizonteCliente`).

## 30. Superficie a la altura real, niveles hasta 10 y horizonte por región — 2026-09-30

Pedido: pasar de 2048 chunks, vóxeles "más suaves según la forma" en vez de
cubos de 2/4/8/16, vóxeles de 16 recién lejos, niveles más grandes para lo
muy lejano, y detalle por píxeles en pantalla.

**Por qué no tamaños que no sean potencia de 2:** el octree (sección 2) anida
cada nivel en el anterior y la sección de 16 bloques solo se divide en
potencias de 2; tamaños intermedios romperían el formato de disco y red
(sección 5) y la derivación jerárquica. En cambio:

- **Relleno por vóxel** (`SuperVoxel.relleno`, el byte `alturaLocal`): qué
  parte del alto del vóxel está llena. El mesher dibuja la superficie a esa
  altura (recortes en bloques enteros) y los costados hasta ahí. Así la
  forma vertical queda a resolución de bloque en todos los niveles y el
  tamaño del vóxel solo se nota a lo ancho, donde el tope de píxeles lo
  acota. Es lo que hace "según la forma" la transición entre niveles.
- **Detalle por píxeles** (ya existía, sección 3): ahora con un tope duro
  (`pixelesMaximos`) que ni el preset ni el auto-ajuste pasan. Los vóxeles de
  16 solo aparecen donde ocupan menos que eso.
- **Niveles 9 y 10** (vóxeles de 512 y 1024; la tesela de nivel 10 mide
  16 384 bloques, el máximo que entra en los shorts del vértice compacto).
- **Radio 8192** con el horizonte aproximado por región (nodo entero de nivel
  5/6/7 por tarea), porque chunk por chunk serían ~200 millones de chunks.

**Fundido con tramado entre niveles (sección 6) — implementado en 0.25.0**
(`render/FundidoNiveles` + uniform `Fundido` de `lod_textura`): la malla vieja
pasa a "saliente", se dibuja entera hasta que las celdas que la tapan están
armadas (tope 3 s) y después se cruza con ellas con un Bayer 4×4
complementario durante 0,4 s; hasta 32 salientes a la vez. En el mismo hito,
la aproximación cercana se arma en niveles 1 y 2 (`TerrenoAproximado`,
claves de nivel 15) porque los vóxeles de 8 bloques de cerca eran lo que más
se notaba.

## 31. Cubic chunks por etapas: sincronización vertical + LOD vertical — 2026-10-01

Pedido: ganar memoria y CPU con mundos gigantes (a futuro, alturas mucho
mayores, hasta un mundo 1:1) sin reescribir todo, como experimento medible.

**Por qué no cubic chunks completo de una:** carga por columnas (tickets,
`ChunkMap`), etapas de generación, mapas de altura, luz del cielo, formato
Anvil, red y cliente asumen columnas; también Sodium, Iris, VulkanMod y los
mods de generación. Vanilla además limita la altura de una dimensión a 4064.

**Etapa 1 — sincronización vertical (implementada en 0.26.0, `cubico/`):**
idea de Vertigo (Builderb0y, MIT; solo Fabric), escrita de nuevo para
NeoForge. El servidor genera y carga columnas enteras como siempre; a cada
cliente le manda de cada columna solo las secciones a ±N secciones de él
(mixins: `PlayerChunkSender`, `ClientboundLevelChunkPacketData`,
`ChunkHolder#broadcast`) y, al moverse, paquetes propios de cargar y vaciar
secciones sueltas (bloques + luz) y el rango de cada columna (`RangoSecciones`,
histéresis de una sección). Opt-in desde el cliente (opción experimental) y
permitido por el servidor. **LOD vertical:** el cliente guarda el rango de
cada columna (`ClienteVertical`) y `RenderLod` deja de tratar esas columnas
como "de vanilla": arma solo sus secciones fuera del rango, con caras
laterales por sección según el rango del vecino. Las islas flotantes y el
relieve fuera del rango se siguen viendo. Gana memoria, red y mallas del
cliente; no CPU ni memoria del servidor.

**Franja vertical de vanilla (0.26.10):** vanilla y Sodium no dibujan las
secciones a más de *distancia de render × 16* bloques en vertical de la cámara
(`SectionOcclusionGraph#getRelativeFrom`). Cuando la superficie de una columna
queda fuera de esa franja (`RangoSecciones.verticalVisible`, achicada una
sección de cada lado), el LOD la trata como parcial con esa franja, o como
toda suya si la superficie no entra; con la superficie adentro no cambia nada
(no hay rearmados al caminar). Sin esto, volando alto el suelo cercano no lo
dibujaba nadie.

**Etapa 2 (pendiente):** no generar ni cargar en el servidor lo lejano en
vertical (empezando por la generación); con formato de guardado propio para
pasar de 4064 de alto.

## 32. Cubic chunks, etapa 2: generar con ruido solo una franja vertical — 2026-10-01

Pedido: en mundos muy altos, que el servidor no gaste CPU ni memoria en lo
que está lejos en vertical de los jugadores, empezando por la generación.

**Dónde se gasta:** el paso NOISE evalúa la densidad final en cada esquina de
celda (4×8×4 bloques) de toda la altura de la columna; en un mundo de 2048 de
alto es ~5× lo de uno vanilla aunque casi todo sea piedra o aire. Ese rango lo
fija `NoiseSettings.clampToHeightAccessor(chunk)` en dos lugares:
`NoiseChunk.forChunk` (paso BIOMES, el `NoiseChunk` queda guardado en el
`ProtoChunk`) y `NoiseBasedChunkGenerator.fillFromNoise` (paso NOISE).

**Parte 1 (implementada en 0.26.1, `cubico/GeneracionVertical`, opción de
servidor `cubico.generacionVertical`, apagada):**
- Al crear el `NoiseChunk` de un chunk que todavía no pasó por NOISE se
  decide su franja (`VentanaVertical`): superficie estimada en 5 puntos con
  la densidad sin "jaggedness" y el umbral de la superficie preliminar de
  vanilla, menos un margen; las alturas de los jugadores cercanos con su
  distancia; arriba, hasta el techo salvo `recortarArriba`. Se guarda en un
  adjunto del chunk (`ventana_generacion`, persistente, pasa al LevelChunk):
  es la marca de "secciones pendientes" para la parte 2, y hace que el
  `NoiseChunk` y el llenado usen siempre el mismo rango.
- Los dos `clampToHeightAccessor` devuelven la franja. El acuífero conserva
  la altura completa (los carvers lo consultan en todo su rango).
- Después del llenado, lo de abajo pasa a secciones de un solo valor (el
  bloque por defecto, sin costo). Sobre ese relleno siguen corriendo las
  reglas de superficie (pizarra, lecho de roca), los carvers y las menas;
  faltan cuevas de ruido, acuíferos y vetas grandes.
- Solo dimensiones con cielo y sin techo: en el Nether la "superficie" sería
  el techo y se perdería el piso.
- Medido (mundo -1024..1023, 1024 chunks, servidor dedicado): ruido por chunk
  171 ms → 103 ms con la franja → 28 ms con `recortarArriba`; estimar la
  franja, 1,4-1,7 ms.

**Parte 2 (implementada en 0.26.2, `cubico/CompletadoVertical`):** cada 10
ticks, las columnas cargadas a la vista de un jugador cuya franja no llega a
`distanciaJugador` (+2) secciones de él se encolan, de la más cercana a la más
lejana. En un hilo de fondo, la banda que falta se genera en un `ProtoChunk`
aparte con la banda como franja: biomas, ruido (acuífero y superficie
preliminar con la altura completa, `MixinNoiseChunkSuperficie`) y reglas de
superficie (con tres secciones de relleno encima de una banda de abajo, para
que no la traten como superficie). En el hilo del servidor se mezcla con el
chunk real vía `LevelChunk#setBlockState` (luz, mapas de altura, fluidos):
abajo, aire y fluidos del aparte sobre roca, y la roca del aparte solo donde el
real tiene la del relleno; arriba, lo del aparte donde el real es aire. Al
terminar la luz (`lightChunk(chunk, true)` como barrera) se reenvía el chunk.
Medido: 99,2% de bloques iguales a la generación completa (falta la
vegetación de cuevas, que viene de features).

**Parte 3, "no cargar lo lejano en vertical" (0.26.3):** medido con un
histograma del heap en el mundo alto: 840 mil `PalettedContainer`, el 74% de
un solo valor pero ~250 B fijos cada uno (candados, detector de hilos), y
~200 MB en `long[]` de datos de bloques, casi todo roca profunda mixta (menas,
tufa, diorita: ~2 KB por sección). Las columnas siguen cargadas enteras (carga,
luz, guardado, red y otros mods lo asumen); se achica lo que guarda cada sección:
- `SeccionesComprimidas` + `AlmacenComprimido`: un `BitStorage` que guarda los
  datos con Deflate y se descomprime solo al leer (lecturas desde cualquier
  hilo con la versión descomprimida en una local; escribir y comprimir, solo el
  hilo del servidor; `getRaw` para guardar/paquetes descomprime en temporal).
  Barrido con tope de 1 ms/tick sobre secciones a más de `distanciaCompresion`
  de todos los jugadores, sin ticks aleatorios. Access transformer para el
  campo `data` y el record `Data` de `PalettedContainer`. Medido: heap 818 →
  720 MB, datos de esas secciones 94 → 7,8 MB, mismos bloques al guardar.
- `SeccionesCompartidas`: contenedores de un solo valor compartidos, copia al
  escribir en `LevelChunk#setBlockState`, guarda contra escrituras directas.
  ~10 MB (solo el aire es uniforme de verdad).

- Luz (0.26.4, `MixinDataLayer`): `get(int)` y `copy()` reescritos para leer
  `data` una sola vez; las capas visibles (`getDataLayerData`, que el motor de
  luz nunca escribe: copia antes, `getDataLayerToWrite`) se comprimen desde
  el barrido. `isEmpty`/`isDefinitelyHomogenous`/`isDefinitelyFilledWith`
  dan false si está comprimida (si no, no se guardaba); `getData` devuelve
  una copia temporal (guardar, paquetes) y `set` descomprime e invalida. Solo
  chunks cargados hace más de 10 s (las capas en cola sí se escriben directo).
  Medido: ~17 MB de luz con datos → ~1 MB; luz guardada igual que sin
  compresión.

- Costo fijo por contenedor (0.26.5, `MixinThreadingDetector`,
  `MixinDatosPaleta`; cliente y servidor, cualquier mundo): el detector de
  hilos de cada `PalettedContainer` ya no arma `Semaphore` ni `ReentrantLock`
  (el dueño va en un campo bajo el monitor del detector; mismo error de
  vanilla) y `Configuration` se comparte por valor. No se aplica con
  FerriteCore (`PluginCubico`). Medido: heap 712 → 621 MB.

- Features sobre lo completado (0.26.7, `DecoracionVertical`): mismo orden y
  siembra que `ChunkGenerator#applyBiomeDecoration` (accesor a
  `featuresPerStep`), pasos LOCAL_MODIFICATIONS (sin geodas),
  UNDERGROUND_STRUCTURES, UNDERGROUND_DECORATION, FLUID_SPRINGS,
  VEGETAL_DECORATION y TOP_LAYER_MODIFICATION, sobre el `ServerLevel` vivo;
  `MixinLevelFiltro` descarta las escrituras fuera de la banda y del chunk y
  quita el aviso a vecinos. Medido: vegetación de cuevas como la generación
  completa (musgo 2989/2866, lianas 291/291), 3,6 ms por columna.

**Pendiente:** lo que queda fijo por contenedor
(`PalettedContainer` 40 B, detector 40 B, `Data`, paleta de un valor: ~150 B);
compresión de secciones lejanas en el cliente (hoy todo es servidor).

## 33. Optimizaciones del juego fuera del LOD — 2026-10-02

Pedido: acelerar el juego en general (chunks y FPS), no solo el LOD. En orden:

1. **Modo híbrido (`config/DistanciaVanilla`, opción `vanillaReducida`):**
   con el LOD activo, la distancia de vanilla se acota según el preset (5 a
   12 chunks) en `Options#getEffectiveRenderDistance` y en la del servidor
   integrado. Medido: 4-5 → 9-10 FPS pidiendo 16 chunks.
2. **Entidades tapadas (`render/OcultamientoEntidades` + `RayosVisibilidad`):**
   lo que vanilla quiso dibujar (ya pasó el frustum) se prueba en un hilo
   aparte con rayos al centro y las esquinas de la caja (DDA bloque por
   bloque, bloques `isSolidRender`); el cuadro siguiente saltea lo tapado.
   Conservador: lo nuevo, una prueba vieja o hecha con la cámara en otro
   lugar cuentan como visibles; nada en espectador, en la pasada de sombras
   de Iris ni para lo que brilla. Idea de EntityCulling (si está, no corre).
   Medido: 105 → 51 entidades dibujadas con la imagen idéntica.
3. **Límites:** distancia de entidades y bloques con entidad, distancia de
   partículas (vanilla 32) y tope total de partículas vivas.
4. **Ritmo de chunks (`msCargaChunks`):** el cliente pide los chunks que
   entran en N ms por tick (vanilla 7, `ChunkBatchSizeCalculator`). En Xvfb no
   cambió nada (el límite era el servidor): queda en 7, a probar en hardware.
5. **Generación en paralelo (`cubico/GeneracionParalela`, opción de servidor
   experimental `generacionParalela`):** vanilla corre superficie, carvers y
   features de a un chunk por vez desde el mailbox de worldgen. Superficie y
   carvers (solo escriben su chunk) van al pool de fondo; features, con
   candados rayados por chunk sobre los 3×3 que escribe, tomados en orden.
   Idea de C2ME (si está, no corre).
6. **Vecinos de estados de bloque (`cubico/TablaEstados`):** una tabla por
   bloque con índice en base mixta en vez de un `ArrayTable` por estado
   (~26 mil tablas). Idea de FerriteCore (MIT; si está, no corre).

## 34. Sin huecos cerca y terreno real después de vanilla (como Voxy) — 2026-10-02

- **Luz del terreno aproximado:** el subsuelo de una columna se guarda con
  luz 0 y el descarte de caras sin luz (sección 25) borraba las paredes de
  acantilados. Los chunks aproximados se mallan con sus vecinos laterales al
  mismo nivel (reales o aproximados) y sus costados restantes llevan luz
  plena (`GeometriaLod#costadosAlAire`); en las teselas, solo los interiores
  (los bordes siguen dependiendo de la luz para no dibujar paredes enterradas).
- **Bordes entre niveles:** una celda oculta los costados de su borde contra
  el chunk vecino solo si la celda de al lado tiene el mismo nivel (firma de
  vecinas en `EstadoCelda`, rearmado si cambia).
- **Anillo real (`anilloReal`):** el pregenerador (`PregeneradorChunks`) con
  radio vista + N, re-centrado cada radio/8 chunks: lo primero después de
  vanilla es terreno real, como en Voxy (que solo muestra chunks reales).
- **Anillo cercano (`GeneradorAproximado#revisarAnilloCercano`):** cada
  segundo, de vanilla hacia afuera hasta `DISTANCIA_NIVEL1`, lo que falta
  pasa adelante de la ventana (tope 256); las celdas cercanas incompletas se
  rearman cada 2 s.

## 35. Chunks en RAM para cargar antes — 2026-10-02

Vanilla deserializa cada chunk (`ChunkSerializer.read`) en el hilo del
servidor recién cuando entra a la distancia de vista, y lo suelta al salir.
Moverlo a otro hilo (como C2ME) toca `PoiManager` y la luz, que no son
seguros entre hilos: descartado. En cambio, `generation/ChunksEnRam` usa RAM:
ticket propio de nivel 33 (completo, sin ticks ni envío) para un colchón de
`chunksEnRam` chunks más allá de la vista (de a 32 por pasada, prioridad de
vista, no con MSPT > 40 ms) y retención LRU de lo que queda atrás hasta
`ramChunksMb` (~96 KB por chunk medido). Solo singleplayer.

**0.26.17:** el presupuesto sale de `cacheRamMb` ("RAM para LOD"), con tope
en un cuarto del heap; la mitad para el colchón (el más ancho de 2 a 32
chunks que entre, `ChunksEnRam.margenPara`), el resto para la retención.

**0.26.18 — primero la vista:** colchón y anillo real solo piden chunks
nuevos con la vista completa (`ChunksEnRam.faltanEnVista`, una vez por tick):
sus tickets de nivel 33 tienen la misma prioridad que el borde de la vista y
en terreno nuevo la ahogaban (607/625 faltantes a los 80 s → completa en 37-39 s).
Perfil del vuelo por terreno nuevo: casi todo el CPU es ruido
(`wgen_fill_noise`) y el hilo `worldgen`; el del servidor casi no aparece.

**0.26.19 — generación en paralelo por defecto:** medido con la misma prueba
(vista 12, terreno nuevo): 35-37 s con `generacionParalela` contra 47-48 s
sin ella. Pasa a estar prendida por defecto; sigue sin aplicarse con C2ME.

**0.26.20 — extracción por paleta:** perfilando el vuelo, la mitad del CPU
propio era `Block.getId` (búsqueda en mapa por bloque) dentro de
`ColoresBloque.rgb`, más los mapas de forma y material. `LectorSeccionMinecraft`
arma `InfoEstado` (estado, id, material, forma) una vez por entrada de la
paleta de la sección (hasta 256; si no, memo por estado) y la fila de la
sección de arriba una vez por columna: 228-235 → 105-116 µs por sección.

**0.26.21 — atajo exacto del `Beardifier`:** `cubico/LimitesBeardifier` +
`MixinBeardifier`: caja de alcance de piezas (±12 en horizontal; en vertical
de su caja y su suelo) y uniones (núcleo 24³); fuera, los `hasNext()` de los
dos bucles de `compute` dan false (redirect, sin objeto por punto) y el aporte
queda en 0. Test contra el `Beardifier` de vanilla. Vuelo: 6,4 % → 0,5 % del CPU.

## 36. Carga de GPU del LOD — 2026-10-02

Medido en Xvfb (llvmpipe: proporciones, no números absolutos): el LOD es ~85%
del trabajo de GPU; las texturas, ~38% de eso; SSAO, nubes lejanas y fundido,
casi nada. Por nivel, los vóxeles de 1 bloque (nivel 0) son ~85% de los
vértices de las mallas.

**0.26.22 — piso de píxeles (`pixelesMinimos`, 2 px por defecto):** en
`PlanCeldas.nivelPara` el umbral queda entre el piso y el techo
(`pixelesMaximos`; si chocan, manda el techo). Con umbrales bajos (Alto 1,5,
Ultra 1,0, Horizonte 0,75, × 0,6 cerca) el nivel 0 llegaba a 1-1,7 km a
1080p con vóxeles de menos de un píxel. Medido con umbral 1,0, radio 160:
14,8 M → 9,2 M vértices dibujados, GPU −31%, imagen igual. En Medio no
cambia nada (el umbral ya pasa los 2 px donde termina el nivel 0).

**0.26.23 — sin cambio visual:**
- `lod_textura` sin `discard` (apagaba el early-Z); el tramado del fundido va
  en `lod_textura_fundido` (mismo color por `include/lod_textura_color.glsl`),
  dibujado en una pasada aparte solo para las mallas que entran o salen
  (`RenderLod.Filtro`). Datos por cara `flat`. En llvmpipe no cambia el
  tiempo (ya prueba profundidad antes); la ganancia es en GPU real (Pista B).
- `LectorSeccionMinecraft.voxel`: un objeto por vóxel, tinte de bioma pedido
  solo por los bloques que lo usan (`ColoresBloque.FuenteTinte`, un canal),
  luz de los 6 vecinos en una pasada, emisión en `InfoEstado`. 287 → 145 ns
  por vóxel, 54 M vóxeles iguales al código anterior.
- Descartado: tabla de oclusión ambiental por capa en `GreedyMesher` (mismos
  quads, 5% más lento: arma la capa entera para pocas caras).

**0.26.24 — acuífero (`cubico/mixin/MixinAcuifero`, `@Overwrite` de
`NoiseBasedAquifer#computeSubstance`):** los 12 centros vecinos dependen solo
de la celda de acuífero; se guardan desempaquetados los de la última celda
(mismo orden, desempates y siembra que vanilla). 40 M llamadas iguales a
vanilla, 328 → 261 ns (el acuífero es ~19% del CPU de `wgen_fill_noise`). No
con C2ME. Memoria: el heap en vuelo es sobre todo la cache del LOD (`byte[]`)
y los chunks retenidos a propósito (`long[]`); compartir contenedores de un
solo valor por defecto se descartó (≤ 65 MB, error si otro mod escribe directo).

## 37. Más calidad con poco costo — 2026-10-02

**Detalle extra (0.26.25, `BalanceadorCpuGpu.detalleExtra`):** perilla del
auto-ajuste que multiplica umbral y piso de píxeles (1 → 0,5; piso nunca bajo
1 px, `RenderLod.PISO_DETALLE_EXTRA_PX`). Se suma último en `recuperar()` (con
todo en el preset y 2 ciclos bajo el 80% del objetivo) y se saca primero en
cuanto el cuadro pasa el objetivo, sin esperar a confirmar el lado; después
queda bloqueada `CICLOS_BLOQUEO_EXTRA` (30) ciclos. Solo con el auto-ajuste.

**Oclusión en costados (0.26.25, opción `oclusionCostados`, apagada):**
`GreedyMesher.mallar(..., enCostados, ...)` calcula la AO por esquina en las
6 caras (antes solo +Y); el mapeo (u, v) de la AO a los vértices ya era el
mismo por eje. Medido en la vista de prueba: malla 24,6 → 31,7 M vértices,
dibujados 8,5 → 10,3 M, GPU +18%: corta fusiones de caras. La SSAO
(`oclusionPantalla`, prendida) ya cubre valles y pies de montaña; la de
costados suma el detalle de las grietas en laderas de roca.

**Agua translúcida (0.26.26, opción `aguaTranslucida`, prendida):** con el
shader propio (`TipoMalla.TEXTURA`) el agua se separa al armar
(`GeometriaLod.separarAgua`, como el contrato Voxy) y se dibuja en
`RenderLod.pasadasTextura(..., soloAgua)` al final, con mezcla alfa y
`ColorModulator.a` = `ALFA_AGUA` (0,72), escribiendo profundidad (niebla y
SSAO la toman como superficie). Al separar, se descartan solo los costados de
agua en el borde de la grilla y las caras de abajo; los de adentro (cascadas)
se dibujan. Medido: misma geometría, GPU +3%, +372 llamadas en la vista de
prueba. Bordes entre niveles: revisados con cada nivel teñido, continuos.

**0.26.27 — marcas vs. nodos:** la marca vieja de "chunk extraído"
(`GeneradorLocal.claveMarcaVieja`, nivel 15, Y=0) comparte clave con el nodo
aproximado fino de nivel 2 en la sección 0 (`TerrenoAproximado.claveNodo`, nivel
15). En mundos de antes de 0.26.14 el armado lo leía como nodo (0 o 1 byte) y la
celda fallaba. `OctreeNodeCodec.esNodo` (al menos la cabecera) en todos los
lectores. Escalado "solo si gana" pasó a "apagar solo si pierde" más de 3%
(`PruebaEscalado.PERDIDA_MAXIMA`); con Iris, apagado solo con shaderpack activo.


## 38. Bloques finos y de entidad en el LOD — 2026-10-02

Pedido: que plantas, camas y otros bloques 3D se vean en el LOD casi igual que
de cerca, sin tocar la zona vanilla. Descartado: reemplazar en vanilla los
bloques con entidad por cajas (lo cercano tiene que quedar igual).

**0.26.28 — sin geometría nueva:**
- **Cobertura vegetal:** la decoración sin colisión (pasto, flores, caña) se
  sigue omitiendo, pero tiñe el vóxel de abajo (`LectorSeccionMinecraft.voxel`)
  con su color × `ColorTextura.pesoCobertura` (fracción opaca de su textura
  × 1,5, tope 0,75). La cobertura se calcula por sprite en `PaletaTexturas`
  (`Paleta.cobertura`) y solo para `MapColor.PLANT` (un cartel, riel o
  estandarte no tiñe el suelo). Medido en Xvfb: cambio sutil de tono, igual costo.
- **Camas y cofres:** su modelo de bloque solo tiene la partícula (tablones);
  `PaletaTexturas.coloresDeEntidad` usa el promedio de la región visible de su
  textura de entidad (`Sheets.BED_TEXTURES`, `CHEST_*`, base 64×64), color plano.
- `VERSION_ALGORITMO` no sube: regenerar todo el cache por un tono no vale;
  se aplica a lo que se extrae desde ahora.

**0.26.29 — siluetas en cruz (opción `siluetasPlantas`, prendida):**
- `LectorSeccionMinecraft.esCruz` (caña, bambú, `DoublePlantBlock` no
  sumergidas) → `SuperVoxel.Material.CRUZ` (código 4) con su color, luz y
  estado. `sinVolumen()`: para el mesher (caras, superficie a la altura real,
  oclusión), la luz por vértice, el relieve y el reductor cuenta como aire, así
  que de nivel 1 para arriba desaparece y el suelo queda igual que antes.
- `GeometriaLod.agregarCruces` (solo nivel 0 y con texturas): dos planos por
  planta, X y Z, una columna de la misma planta en un solo par;
  `GRUPO_CRUZ` aparte. Las posiciones del formato compacto son enteras: bit 7
  del byte de alfa (`MARCA_CRUZ`) y el shader corre el plano 0,5 al centro.
- Silueta: la vista de costado horneada del modelo con su alfa
  (`AtlasLod.recortable`, huecos con el promedio y alfa 1 para que los mipmaps
  de vanilla no oscurezcan los bordes), tabla `TablaTexturas.cruces`.
- Pasada propia después de lo opaco, sin culling, con `lod_textura_recorte`
  (discard por alfa < 0,5); el fundido entre niveles hace el mismo recorte.
  Lo opaco sigue sin discard. Medido: 4,2 → 4,3 M vértices (+2%).

## 39. Pregeneración al crear un mundo — 2026-10-02

Pedido: en un mundo nuevo, tomarse un minuto o minuto y medio al cargar para
generar todos los chunks que se pueda.

**0.26.30 (`generation/PregeneracionInicial` + `cubico/mixin/MixinPrepararNiveles`,
opción `pregeneracionInicial`, 60 s por defecto, 0-180):** inyectado en
`MinecraftServer#prepareLevels` antes de `ChunkProgressListener#stop` (la
pantalla de carga sigue abierta). Solo singleplayer y solo si
`WorldData#getLoadedPlayerTag()` es null (el jugador nunca entró). Espiral desde
el spawn con los tickets y las marcas de LOD de `PregeneradorChunks`, hasta
núcleos × 4 en vuelo, frenando si la extracción se atrasa; el bucle es el de
vanilla para el spawn (`nextTickTimeNanos` + `waitUntilNextTick`, 10 ms de
tareas por vuelta), y como no hay ticks, los pendientes de luz se reintentan
ahí (`GeneradorLocal.reintentarPendientes`). El avance se dibuja sobre
`LevelLoadingScreen` (`PantallaConfig`, `ScreenEvent.Render.Post`). Medido en
Xvfb: 2527 chunks en 60 s (~42/s, anillo 25), contra ~17/s jugando.
El mundo del benchmark, la primera vez que se crea, también pasa por esto.

**0.26.31 — cada vez que se entra, alrededor del jugador (pedido del usuario):**
sin la condición de mundo nuevo; el centro sale de los datos del jugador en
level.dat (`getLoadedPlayerTag`: `Pos` y `Dimension`, en esa dimensión), o del
spawn si nunca entró (`PregeneracionInicial.dondeQuedo`). Lo ya generado con
marca de LOD se saltea, así que en un mundo explorado el minuto se usa en lo
que falta más afuera. Medido: jugador en 3000, −3000 → 2257 chunks en 60 s.
