# 06 — Hitos

Cada hito cierra con build + tests completos + commit (checklist de
`CLAUDE.md`). Lo visual se anota en `NOTES.md` como Pista B.

| # | Hito | Se prueba | Se mide |
|---|---|---|---|
| H0 | Verificar datos/licencias; sección 37 de la arquitectura con estas decisiones | — | — |
| H1 | Preparador de datos → `.lodt`, lector con caché, recorte de prueba en `src/test/resources` | tests de lectura/índice/caché | MB en disco, µs por consulta |
| H2 | `Proyeccion` (las dos) + `FuenteTierra` (bicúbica) + `AlturaTierra` (sin detalle) | ida y vuelta lat/lon↔bloque; puntos conocidos | µs por columna (presupuesto < 0,5 µs) |
| H3 | `SuperficieTierra` + `noise_settings` + `dimension_type` + `world_preset` (piedra y agua, sin biomas) + fluido global agua (sin océanos de lava) + lecho de roca macizo hasta el fondo de la fosa | servidor dedicado: crear mundo, forceload | ms/chunk, **elevación en puntos conocidos** |
| H4 | `FuenteBiomasTierra` + `biomas.json` + reglas de superficie (pizarra profunda por profundidad) | biomas en puntos conocidos (Sahara, Amazonia, Groenlandia) | — |
| H5 | Atajo `FuenteAltura` en `GeneradorAproximado` + niveles altos del planeta al crear el mundo + radio de curvatura (radio útil según horizonte) | test del atajo; horizonte en Xvfb | tiempo del horizonte completo |
| H6 | Opciones de `cubico/` por defecto en este mundo; franja exacta | — | ms/chunk y heap con/sin |
| H7 | Borde plano + farlands congeladas | prueba a ±2,5 M (precisión) | — |
| H8 | Detalle por rugosidad, costas finas; ríos y lagos (opcional) | — | µs por columna |
| H9 | Cuevas de ruido y menas referidas a la profundidad bajo la superficie | — | — |
| H10 | Circunnavegación este-oeste | cruce con entidades | — |

## Puntos conocidos (y esperada a 1:8, con nivel del mar 63)

| Lugar | lat, lon (aprox.) | Elevación | y esperada |
|---|---|---|---|
| Everest | 27,99 N, 86,93 E | 8 849 m (grilla 30″: 8 354) | ~1 169 (grilla: 1 107) |
| Mar Muerto (orilla) | 31,5 N, 35,5 E | -430 m | ~9 |
| Fosa de las Marianas, abismo Sirena (mínimo de la grilla 30″) | 11,971 N, 144,371 E | -10 775 m (grilla) | -1 284 (fondo, sobre el lecho de roca; min_y -1 296) |
| Fosa de las Marianas, Challenger Deep | 11,354 N, 142,429 E | -10 571 m (grilla) | -1 259 |
| Aconcagua | 32,65 S, 70,01 O | 6 961 m | ~933 |
| Nivel del mar (cualquier costa) | — | 0 m | 63 |

(Con 30″ el dato promedia ~1 km: los picos salen más bajos que la cifra
real. La prueba compara contra el valor de la grilla en ese punto, no contra
la cifra famosa.)

## Resultados

- **H0–H2 (2026-10-01):** ver `02-datos.md` (verificación y medidas) y la
  sección 37 de la arquitectura. 0,14 µs por columna (bicúbica).
- **H3 (2026-10-01):** servidor dedicado con `level-type=minecraftlodmod:tierra_real_8`
  y el `.lodt` global de 30″. `/tierra medir <lat> <lon>` (por RCON) en los
  puntos conocidos, **todos iguales a lo esperado**:

  | Lugar | esperada | generada |
  |---|---|---|
  | Marianas, abismo Sirena | -1284 | -1284, **lecho de roca, y debajo lecho de roca** |
  | Marianas, Challenger Deep | -1259 | -1259 |
  | Everest | 1106 | 1106 |
  | Mar Muerto | 9 | 9 (inundado hasta y 62, ver abajo) |
  | Aconcagua | 876 | 876 |
  | Atlántico 0°, 30° O | -415 | -415 |
  | Madrid | 122 | 122 |

  Ruido: **44 ms por chunk** (1 433 chunks, Alpes y Atlántico; columnas de
  2 672 de alto, sin franja). Heap tras GC con 450 chunks forzados: 884 MB.
  - **Hallazgo:** las depresiones bajo el nivel del mar sin salida al mar
    (Mar Muerto, Caspio, valle de la Muerte, Qattara) se llenan de agua
    hasta y 62: el fluido global no sabe qué es océano. Arreglo en H8 (ríos
    y lagos): una máscara de océano conectado en el `.lodt` y nivel de
    agua local por columna en el selector de fluido.
- **H4 (2026-10-01):** `FuenteBiomasTierra` (`minecraftlodmod:tierra`) +
  `ClasificadorBiomas` (Köppen, elevación, latitud: mares por latitud y
  profundidad, playas, pisos de nieve) y reglas de superficie de vanilla con
  el lecho de roca propio y la **pizarra profunda a más de 64 bloques bajo
  la superficie** (`stone_depth`, no y absoluto). Medido en el servidor:

  | Lugar | clima | bioma | superficie |
  |---|---|---|---|
  | Sahara (23 N, 13 E) | BWh | desert | arena |
  | Amazonia (3 S, 60 O) | Af | jungle | pasto, hojas de jungla |
  | Groenlandia (72 N, 40 O) | EF | frozen_peaks | nieve, hielo |
  | Londres | Cfb | forest | roble, abedul |
  | Siberia (60 N, 100 E) | Dfc | taiga | abeto |
  | Alpes (46,5 N, 8 E; 2 935 m) | ET | snowy_plains | pasto |
  | Atlántico ecuatorial / Ártico | — | deep_lukewarm / deep_frozen_ocean | arena / grava |

  - Las cuevas bajo tierra firme salían inundadas hasta y 62 (fluido global
    agua): ahora el fluido global es agua solo en columnas cuyo suelo está
    bajo el nivel del mar, con caché por columna (sin la caché el ruido
    subía de 44 a 120 ms por chunk: vanilla lo consulta en cada bloque de
    aire y agua).
  - Pendiente: los monumentos oceánicos se ubican a altura fija de vanilla
    (flotarían en mares de cientos de bloques); cobertura del suelo; menas
    por profundidad (H9).
- **H5 (2026-10-01):** atajo `generation/FuenteAltura` en
  `GeneradorAproximado`; curvatura con radio 6 371 km / escala y horizonte
  real siempre prendidos en Tierra real (el cliente lo deduce del
  `dimension_type`, sin paquete); relieve lejano de 2 km para el horizonte
  real. Los niveles del planeta entero al crear el mundo **no se hacen**
  (nunca se verían: `04-integracion-lod.md`). Medido con el cliente en Xvfb
  (render por software, 4 núcleos), cumbre del Cervino y Alpes:
  - con atajo: **0 evaluaciones de densidad**, 127 000-166 000 columnas por
    atajo cada 30 s, ~1 000 chunks + ~420 nodos por región cada 30 s
    (HUD: ~18 000 chunks aproximados/s en ráfagas);
  - sin atajo (`-Dminecraftlodmod.sinAtajoAltura=true`): 1,3-1,6 M
    evaluaciones de densidad cada 30 s, ~600 chunks + ~460 nodos. En Tierra
    real la densidad es una sola bicúbica (`superficie - y`), así que la
    búsqueda no es tan cara como en vanilla (~7 bicúbicas por columna
    contra 4 del atajo); con el render por software usando la CPU no se
    separa bien la diferencia. El atajo además da la superficie exacta del
    generador, y gana de verdad cuando la densidad sume detalle (H8) y
    cuevas (H9).
  - Horizonte real desde la cumbre (474 bloques sobre el mar): ~2 800
    chunks de radio (espiral por región de 94 nodos de nivel 5).
  - **Aviso de "ajustes experimentales"** al crear o abrir el mundo: vanilla
    lo muestra para todo Overworld no vanilla. Desactivado para Tierra real
    (`tierra/mixin/MixinEstabilidadTierra`, como el Amplificado); también
    los codecs propios van `.stable()`.
  - **Pendiente de Pista B:** en las capturas de Xvfb no se ve el terreno
    lejano (cielo bajo el horizonte desde el Cervino). Puede ser el render
    por software (Mesa) o la curvatura: verificarlo en una PC real.
- **H6 (2026-10-01):** `ConfigLod.cubico(nivel, opción)` + `cubicoPorDefecto`
  (Tierra real): generación por franja, `recortarArriba` y compresión de
  secciones lejanas prendidas solo en Tierra real; franja **exacta** con
  `FuenteAltura` (5×5 esquinas de celda, margen de abajo 2 secciones) y con
  tope nunca bajo el nivel del mar (si no, con `recortarArriba` el mar salía
  vacío arriba del fondo + 8 secciones). Medido en servidor dedicado, mismo
  forceload (Alpes + Atlántico, 1 283 chunks), mundos nuevos:

  | | sin (`-Dminecraftlodmod.tierraSinCubico=true`) | con H6 |
  |---|---|---|
  | ruido por chunk | 47,8 ms | **14,0 ms** |
  | secciones sin generar | 0 | 91 376 abajo, 84 586 arriba |
  | datos de bloques comprimidos | — | 92 744 → 5 873 KB |
  | heap tras GC | 929 MB | **814 MB** |
  | puntos medidos | iguales | iguales (mar con agua hasta y 62) |

  - `compartirSeccionesUniformes` **no** se prende: crash del servidor al
    fluir agua sobre una sección compartida (bug de `SeccionesCompartidas`,
    `copy()` de un contenedor de un valor comparte la paleta con el
    original; pedido a la sesión del LOD en `NOTES.md`).
- **H7 (2026-10-01):** borde de la Tierra plana (`05-borde.md`). Servidor
  dedicado, `tierra_plana_8`, `/tierra columna 0 <z>` a lo largo del borde
  (disco de radio 2 501 889):

  | Distancia al borde | Bioma | Columna |
  |---|---|---|
  | -100 | frozen_peaks | meseta en y 417, nieve sobre piedra |
  | +50 / +500 | farlands_congeladas | nieve en 417, hielo compacto / azul hasta el lecho de roca |
  | +900 | farlands_congeladas | en una grieta: suelo en y 252 |
  | +1 500 | farlands_congeladas | pared hasta y 1235, túneles de 9 cada ~24 |
  | +3 000 | farlands_congeladas | pared hasta y 1219, túneles |

  - Atajo del LOD contra lo generado: 0-2 bloques (interpolación de celdas).
  - Madrid en la Tierra plana: y 137 esperada = generada.
  - Borde del mundo: 5 013 780 bloques.
  - Ruido en las farlands: 62 ms por chunk (columna entera); forzar 650
    chunks de golpe atrasa el servidor ~157 s.
  - **Precisión a ±2,5 M:** del lado del servidor, la ida y vuelta lat/lon ↔
    bloque en el borde pierde menos de 1/100 de bloque (test); un bloque de
    diferencia se distingue. Lo del cliente (titileo de vértices, nubes,
    partículas a 2,5 M) queda **pendiente de Pista B**: `/tp 0 1300 2503900`.
- **H8 (2026-10-02):** detalle a escala de bloque (`DetalleTierra`, ver
  `03-generador.md`; imagen de comparación en los Alpes) y agua continental
  (`AguaContinental`, `MascaraAgua`, `.lodt` v2; ver `02-datos.md`). Servidor:

  | Lugar | Bioma | Suelo | Agua hasta |
  |---|---|---|---|
  | Caspio | lago (river) | y 7 | y 59 (-27 m) |
  | Lago Superior | lago | y 65 | y 85 (181 m) |
  | Mar Muerto | lago | y 9 | y 17 |
  | Baikal | lago | y 45 (cañón de vanilla) | y 121 (465 m) |
  | Valle de la Muerte / Qattara | desierto | y 52 / 53 | seco |
  | Atlántico | océano | y -411 | y 62 |

  - Ruido por chunk con detalle y agua: 12,6 ms (H6: 14,0); heap 802 MB.
  - Lo esperado por `AlturaTierra` y lo generado coinciden (±1 por la capa
    de nieve).
  - **No hecho:** costas finas (necesitan el dato de 15″, ~7,5 GB en 288
    teselas) y ríos (opcionales en el plan); pendientes. El LOD aproximado
    todavía dibuja el agua solo al nivel del mar (lagos altos: Pista B /
    después).
- **H9 (2026-10-02):** cuevas por profundidad (`CuevasTierra`) y alturas de
  `height_range` desde la superficie (`tierra/mixin/MixinAlturaRelativa`:
  menas, geodas, lagos de lava subterráneos; referencia y 72, la superficie
  típica de vanilla). `/tierra menas <radio>` cuenta menas y huecos por
  profundidad bajo el suelo. Servidor, 3×3 chunks:

  | Lugar (suelo) | carbón (0-32 / 32-64) | diamante (64-96 / 96-128 / 128-160) | oro (64-96) |
  |---|---|---|---|
  | Alpes (y ~430) | 729 / 304 | 34 / 57 / 32 | 136 |
  | Madrid (y ~137) | 473 / 456 | 18 / 49 / 42 | 143 |
  | Atlántico (fondo y -411) | 665 / 425 | 19 / 47 / 14 | 129 |

  La misma distribución en los tres (antes, bajo el mar no había menas y en
  los Alpes estaban 400 bloques más abajo). Cuevas, con la columna entera:
  huecos en todas las bandas hasta ~200 bloques y lava desde 160.
  - Con la franja vertical (por defecto) las cuevas a más de 32 bloques
    aparecen cuando un jugador baja (`cubico/CompletadoVertical`); por RCON
    no se ve porque los chunks se descargan enseguida: **probar jugando**.
  - Los carvers de vanilla (cuevas y cañones) siguen con y absoluto (-56 a
    180): suman cuevas en esa banda; no se tocaron.
  - Vetas grandes (`ore_veins`) y acuíferos siguen apagados.
- **H10 (2026-10-02):** circunnavegación este-oeste (`tierra/Costura`,
  `tierra/CosturaTierra`; detalle en `05-borde.md`). Servidor dedicado
  "Tierra real 1:8" + cliente en Xvfb conectado; costura en x = ±2 501 888,
  probada en Chukotka (66° N, z -916 078):
  - Superficie a los dos lados: misma altura columna por columna (y 91) de
    x = C/2 - 6 a C/2 + 6 y en las copias una vuelta antes; cambian menas,
    vetas y la capa de tierra (features y ruido de vanilla, por chunk).
  - Entidades: un chancho sin IA y un soporte de armadura invocados 40
    bloques pasada la costura aparecen en x - C (-2 501 847,5) al tick
    siguiente.
  - Jugador en carrito sobre rieles impulsores (8 bloques/s): cruza de
    2 501 905 a -2 501 851 sin bajarse, con el carrito; el cliente ve el
    terreno del otro lado. Primera versión: el carrito quedaba en un chunk que
    el cliente ya había descargado y el jugador veía solo cielo; se arregló
    mandándole el vehículo de nuevo.
  - A pie: ~1 s de cielo mientras llegan los chunks del otro lado (render por
    software), después el terreno.
  - Trabas: la primera prueba trabó el servidor 23 s al cruzar (el chunk de
    llegada todavía se generaba y NeoForge lo carga síncrono al mover al
    jugador); con la espera de la llegada cargada, **ninguna** ("Can't keep
    up" no aparece al cruzar). Aparte, con la franja vertical prendida
    `cubico/CompletadoVertical` traba el servidor 30-40 s al llegar a zonas
    nuevas (pedido en `NOTES.md`); H10 se probó con
    `-Dminecraftlodmod.tierraSinCubico=true`.
  - Sin probar (Pista B, en `NOTES.md`): bote y caballo (vehículos que maneja
    el cliente), élitros, horizonte del LOD a través de la costura.
- **Polos de la cilíndrica (2026-10-02):** borde de farlands congeladas en
  |z| = C/4 con barrera de hielo antes del polo norte (`05-borde.md`).
  Servidor, `/tierra columna` en x 3000 cruzando el polo norte: mar Ártico
  (fondo y -464) hasta 144 bloques antes del polo; frente de hielo (y -398,
  -129) en 16 bloques; barrera en y 65 hasta el polo; grietas (a 1 100
  bloques, y -7) y paredes hasta y ~1 265 (a 1 500 y 3 000). Polo sur
  (Antártida, y 417): sin barrera, mismas grietas y paredes. Atajo del LOD
  igual a lo generado en mar, frente y barrera; en las paredes, 0-2 bloques
  (como H7). Costura en las paredes: y 1 244 a un lado y 1 243 al otro.
