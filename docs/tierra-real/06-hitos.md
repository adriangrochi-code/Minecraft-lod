# 06 — Hitos

Cada hito cierra con build + tests completos + commit (checklist de
`CLAUDE.md`). Lo visual se anota en `NOTES.md` como Pista B.

| # | Hito | Se prueba | Se mide |
|---|---|---|---|
| H0 | Verificar datos/licencias; sección 33 de la arquitectura con estas decisiones | — | — |
| H1 | Preparador de datos → `.lodt`, lector con caché, recorte de prueba en `src/test/resources` | tests de lectura/índice/caché | MB en disco, µs por consulta |
| H2 | `Proyeccion` (las dos) + `FuenteTierra` (bicúbica) + `AlturaTierra` (sin detalle) | ida y vuelta lat/lon↔bloque; puntos conocidos | µs por columna (presupuesto < 0,5 µs) |
| H3 | `DensidadTierra` + `noise_settings` + `dimension_type` + `world_preset` (piedra y agua, sin biomas) + fluido global agua (sin océanos de lava) + lecho de roca macizo hasta el fondo de la fosa | servidor dedicado: crear mundo, forceload | ms/chunk, **elevación en puntos conocidos** |
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
| Fosa de las Marianas (mínimo de la grilla 30″) | 11,354 N, 142,429 E | -10 571 m (grilla) | -1 259 (fondo, sobre el lecho de roca; min_y -1 264) |
| Aconcagua | 32,65 S, 70,01 O | 6 961 m | ~933 |
| Nivel del mar (cualquier costa) | — | 0 m | 63 |

(Con 30″ el dato promedia ~1 km: los picos salen más bajos que la cifra
real. La prueba compara contra el valor de la grilla en ese punto, no contra
la cifra famosa.)
