# 06 — Hitos

Cada hito cierra con build + tests completos + commit (checklist de
`CLAUDE.md`). Lo visual se anota en `NOTES.md` como Pista B.

| # | Hito | Se prueba | Se mide |
|---|---|---|---|
| H0 | Verificar datos/licencias; sección 33 de la arquitectura con estas decisiones | — | — |
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
  sección 33 de la arquitectura. 0,14 µs por columna (bicúbica).
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
