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
- Mismo serializador para disco y red — en red se omite el header repetido (ambas partes ya conocen la región/nodo pedido).
- Cache en RAM como LRU chico (lo visible + margen); disco (SSD) como fuente de verdad de todo lo demás, con lectura asíncrona y escritura diferida (write-behind, batch cada 2-5s).

## 6. Render

- Enganche vía `RenderLevelStageEvent` de NeoForge (etapa posterior a bloques sólidos vanilla), como pasada de render separada del terreno cercano (compatible con Embeddium, que maneja el terreno vanilla normal).
- Buffers agrupados **por región**, no por nodo individual — minimiza draw calls, crítico en iGPU (Vega 8).
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

## 11. Configuración (Cloth Config)

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
config/        - Cloth Config, presets, persistencia
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
