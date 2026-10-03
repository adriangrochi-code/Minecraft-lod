# 02 — Datos

**Todo lo de licencias y tamaños: verificar en la página oficial antes de usar.**

## Elevación (tierra + fondo del mar en un solo dato)

| Fuente | Resolución | ≈ bloques por muestra a 1:8 | Tamaño crudo (int16) | Licencia (verificar) |
|---|---|---|---|---|
| ETOPO 2022 60″ (NOAA) | ~1,85 km | ~230 | ~0,47 GB | dominio público (gobierno de EE.UU.), citar |
| ETOPO 2022 30″ | ~0,93 km | ~116 | ~1,9 GB | ídem |
| ETOPO 2022 15″ | ~0,46 km | ~58 | ~7,5 GB | ídem |
| GEBCO 15″ | ~0,46 km | ~58 | ~7,5 GB | uso libre con atribución |
| Copernicus GLO-30 (solo tierra) | ~30 m | ~4 | decenas a cientos de GB | libre con atribución |

**Decisión:** ETOPO 2022 30″ como dato base (tierra y mar en una grilla, con
superficie de hielo). 15″ opcional para quien quiera más detalle. Copernicus
queda como mejora futura por regiones (no global).

A 1:8, una muestra cada ~116 bloques: el detalle a escala de bloque **se
sintetiza** (`03-generador.md`), el dato da la forma grande.

## Clima / biomas

| Fuente | Resolución | Licencia (verificar) | Uso |
|---|---|---|---|
| Köppen-Geiger (Beck et al., 1 km) | 1 km | CC BY 4.0 | clase de clima → familia de bioma |
| Cobertura del suelo (ESA WorldCover 10 m, o una global de 500 m-1 km) | 10 m–1 km | CC BY 4.0 / ver | bosque, pasto, desierto, hielo, ciudad, humedal |

**Decisión:** Köppen + cobertura agregada a 1 km, combinadas en una sola
grilla de "clase de bioma" (1 byte). La tabla Köppen×cobertura → bioma de
Minecraft va en un archivo de datos editable (`tierra/biomas.json`).

## Formato propio (`.lodt`, preprocesado una sola vez)

- Herramienta de preparación (comando del servidor o tarea de gradle,
  `tierra/PreparadorDatos`): lee GeoTIFF/NetCDF y escribe teselas.
- Teselas de 256×256 muestras: elevación en metros (int16) + clase de bioma
  (uint8), Deflate por tesela, índice al principio (misma idea que
  `storage/RegionHeader`: lectura parcial).
- Carpeta: `.minecraft/minecraftlodmod/tierra/` (fuera del mundo: se comparte
  entre mundos). Esperado con 30″ comprimido: bastante menos que los ~1,9 GB crudos (el mar es suave; medirlo en H1).
- Lectura: caché LRU de teselas con tope de bytes (mismo patrón que
  `storage/BoundedRegionCache`), lectura por `FileChannel` sin bloquear.
- **Datos de prueba en el repo:** un recorte chico (por ejemplo 10°×10°, ~3 MB
  a 30″) en `src/test/resources/tierra/` para los tests, con su atribución.
- Descarga: no dentro del juego al principio. Instrucciones + la herramienta;
  después, si vale la pena, botón con progreso en la pantalla de crear mundo.

## Atribución

Pantalla de crear mundo + `CREDITS` del jar: NOAA ETOPO, Beck et al.
(Köppen), fuente de cobertura. Requisito de las licencias CC BY.
