# 03 — Generador

## Idea central

**No** un `ChunkGenerator` nuevo: el `NoiseBasedChunkGenerator` de vanilla
con `noise_settings` propios cuyo `final_density` usa una
**función de densidad propia** (`minecraftlodmod:tierra_superficie`,
registrada con su codec). Así se reutilizan, sin tocarlos:

- cuevas, acuíferos, menas, carvers, reglas de superficie, features y
  estructuras de vanilla;
- todo `cubico/` (la franja engancha `NoiseBasedChunkGenerator` y
  `NoiseChunk`), la compresión de secciones;
- el LOD aproximado (`generation/GeneradorAproximado`, que evalúa
  `finalDensity` e `initialDensityWithoutJaggedness`).

## Piezas (paquete `tierra/`, con autorización del usuario)

- `Proyeccion` (interfaz) + `ProyeccionCilindrica` + `ProyeccionAzimutal`:
  bloque (x, z) ↔ (lat, lon). Lógica pura, con tests de ida y vuelta.
- `FuenteTierra`: elevación en metros y clase de bioma en (lat, lon), con
  interpolación bicúbica y caché de teselas. Lógica pura + E/S de archivos.
- `AlturaTierra`: altura en bloques de una columna (x, z) = 63 +
  (elevación interpolada + detalle) / escala. **Esta es la única fuente de
  verdad de la superficie** para el generador y para el LOD.
- `DensidadTierra` (DensityFunction): `(alturaTierra(x, z) - y) * k`,
  positiva bajo la superficie. Con caché por columna (marcador de caché 2D
  de vanilla) porque se evalúa en todas las esquinas de celda.
- `FuenteBiomasTierra` (BiomeSource con codec propio): bioma por columna
  desde la grilla de clase + tabla `biomas.json` + altura (pisos térmicos:
  nieve sobre la línea de nieve según latitud).
- Datos del mundo (dentro del mod): `dimension_type`, `noise_settings`,
  `world_preset` por variante y escala.

## `noise_settings`

- `noise`: `min_y`/`height` de `01-decisiones.md`; tamaño de celda vanilla.
- `final_density`: `min(DensidadTierra, cuevas)` donde las cuevas de vanilla
  se **re-referencian a la profundidad bajo la superficie** (las de vanilla
  dependen de `y` absoluto en -64..320; hito propio, `06-hitos.md`). Primera
  versión: sin cuevas de ruido, solo carvers.
- `initial_density_without_jaggedness`: la misma `DensidadTierra` → la
  superficie estimada es **exacta** (la usan la franja vertical y el LOD).
- `sea_level`: 63; fluido por defecto: agua (los océanos salen solos).
- `surface_rule`: las de vanilla por bioma (arena en costas, nieve, etc.),
  salvo el piso: **lecho de roca macizo de `min_y` al fondo de la fosa**
  (`AlturaTierra.esLechoDeRoca`, `01-decisiones.md`) en vez del degradé.
- `aquifers_enabled`: sí; `ore_veins_enabled`: sí.

## Detalle a escala de bloque

El dato tiene una muestra cada ~116 bloques (30″ a 1:8):
- bicúbica de la elevación (sin escalones);
- **ruido de detalle** cuya amplitud depende de la rugosidad local (desvío de
  las muestras vecinas) y de la pendiente: llanuras quedan llanas, montañas
  ganan crestas; nunca cambia el signo cerca de la costa (no crea islas ni
  lagos falsos);
- costas: el signo (tierra/mar) se respeta con una máscara más fina cuando
  exista (15″ o costa vectorial, hito posterior).

## Rendimiento

Objetivo: `alturaTierra` en < 0,5 µs con la tesela en caché (es lo que más
se llama). Medir con el script del servidor dedicado de la otra sesión
(ver `04-integracion-lod.md`, "Cómo medir").

Se mide desde H2 (bicúbica sola, microbenchmark en tests) para saber cuánto
presupuesto queda para el ruido de detalle de H8. Ideas si no alcanza:
coeficientes bicúbicos precalculados por celda de muestra (cachés por
tesela), y evaluar el detalle una vez por columna con el caché 2D.

## Fluido y reglas que dependen de `y` absoluto

- **Fluido global:** agua en toda la altura (ver riesgo 1 de
  `01-decisiones.md`); sin esto los océanos profundos salen lava.
- **Pizarra profunda:** regla de superficie propia por profundidad bajo la
  superficie, no el gradiente vanilla de y 0–8 (H4).
- **Menas:** las `height_range` de vanilla son absolutas; se re-refieren a la
  profundidad en H9 junto con las cuevas.
