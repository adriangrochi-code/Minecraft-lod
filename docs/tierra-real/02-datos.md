# 02 — Datos

**Verificado el 2026-10-01 en las páginas oficiales (H0). Ver "Verificación"
al final; lo que no se pudo confirmar está marcado.**

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

**Hecho en H4 (2026-10-01):** por ahora la clase de bioma es **solo
Köppen** (1..30, 0 = mar), del mapa 1991-2020 de 1 km de Beck et al. (V3):
es la **misma grilla** que ETOPO 30″ (43200×21600 desde -180°, 90°), así que
entra muestra por muestra (`PreparadorDatos --clima`; con otra grilla toma
el píxel que contiene el centro de cada muestra). Suma 6 MB al `.lodt`
global (663 MB). La cobertura del suelo (ciudades, humedales, cultivos)
queda para después. La tabla clave → bioma no es un archivo aparte: va en el
`world_preset` (campo `biomas` de la fuente `minecraftlodmod:tierra`), así
se cambia con un datapack. Descarga: el zip de GeoTIFF (130 MB) está en
figshare (`koppen_geiger_tif.zip`, archivo 61012822); figshare responde 202
sin descargar a clientes sin `Accept: */*` y un User-Agent de navegador.

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

## Verificación (H0, 2026-10-01)

| Fuente | Confirmado | Corrección al paquete |
|---|---|---|
| ETOPO 2022 | 15″ en 288 teselas de 15°×15° (+62 teselas "bed"); **30″ y 60″ como archivo global único**, GeoTIFF y NetCDF, versiones `_surface` (superficie del hielo) y `_bed`. Alturas en metros sobre el geoide EGM2008 (≈ nivel del mar). Uso libre privado, académico y comercial (salvo navegación); el catálogo lo da como CC0-1.0. Citar DOI 10.25921/fd45-gt74 | Usar la versión **`_surface`** (la superficie del hielo es lo que se camina). Los tamaños de archivo no figuran en la página: medirlos al descargar en H1 (los GeoTIFF podrían ser float32, el doble que int16; el `.lodt` igual guarda int16) |
| GEBCO | Ahora GEBCO_2026 (anual, en julio), 15″, NetCDF 4 GB comprimido / 7,0 GB; **dominio público**, citar al GEBCO Compilation Group | "Uso libre con atribución" → dominio público con cita |
| Köppen-Geiger (Beck et al., V3, 2023, *Scientific Data* 10, 724) | CC BY 4.0 (texto de la página y metadatos de figshare), GeoTIFF uint8 con LZW, 1 km = 0,00833333° (la grilla de ETOPO 30″), períodos 1901–2099; trae `legend.txt` | Usar el período actual (1991–2020) |
| ESA WorldCover | CC BY 4.0, 10 m, **~117 GB** el mapa global | Demasiado grande para agregarlo en la máquina del jugador: la agregación a 1 km se hace una sola vez de nuestro lado o se usa otra cobertura global de 300 m–1 km (**sin verificar**; elegir en H4). Atribución exacta: "© ESA WorldCover project 2021 / Contains modified Copernicus Sentinel data (2021) processed by ESA WorldCover consortium" |
| Copernicus GLO-30 | no verificado (sigue como mejora futura) | — |

Medido en H1 (2026-10-01):
- Archivos de NOAA: 30″ GeoTIFF global **1,5 GB**, 60″ **444 MB**, teselas de
  15″ ~30 MB. Todos float32, teselas internas de 256×256, Deflate, predictor
  de punto flotante, celda centrada (`PixelIsArea`). `tierra/GeoTiff` los lee
  sin dependencias.
- La grilla de 30″ es exactamente el promedio 2×2 de la de 15″ (comprobado
  contra el OPeNDAP de NCEI al metro): `PreparadorDatos --reduccion 2`
  reproduce la de 30″ desde las teselas de 15″.
- `.lodt`: la tesela de 15″ del Himalaya (3600×3600) pasa de 29 MB a
  **9,15 MB**; el recorte de prueba de 10°×10° a 30″ ocupa **1,27 MB**
  (`src/test/resources/tierra/himalaya-bengala-30s.lodt`). Es la zona más
  rugosa: el global, con ~70% de mar, debería quedar bastante por debajo.
  Preparar: ~1,8 s por 1,4 M de muestras (Deflate máximo, un hilo); el
  global de 30″ (933 M) son ~20 min, una sola vez. Paralelizar si molesta.
- **Global de 30″ preparado (H3):** 43200×21600 muestras, 14 365 teselas,
  **657 MB**, 5 min con Deflate en paralelo (4 núcleos). Elevación
  -10 775..8 354 m: el mínimo es el abismo Sirena (fosa de las Marianas,
  11,971° N, 144,371° E; confirmado contra el OPeNDAP de NCEI: -10 775,46 m).
- Datos de prueba: ETOPO 2022 (NOAA NCEI), DOI 10.25921/fd45-gt74, uso libre.

Fuentes: ncei.noaa.gov/products/etopo-global-relief-model, guía de usuario
de ETOPO 2022 (ngdc.noaa.gov), gebco.net, gloh2o.org/koppen,
esa-worldcover.org/en/data-access.
