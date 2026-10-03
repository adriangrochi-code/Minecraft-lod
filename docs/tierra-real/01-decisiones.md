# 01 — Decisiones

## Qué es

- Un **tipo de mundo aparte** ("Tierra real" en la pantalla de crear mundo,
  junto a Amplificado / Plano), definido como `world_preset` del mod. Nunca
  reemplaza al mundo por defecto ni cambia mundos existentes.
- Dos variantes del mismo tipo, elegibles al crear el mundo:
  - **Tierra (cilíndrica):** proyección equirectangular, este-oeste
    circunnavegable (en un hito posterior), los polos son el borde.
  - **Tierra plana:** proyección azimutal equidistante centrada en el polo
    norte (el mapa clásico de "tierra plana"); la Antártida queda como anillo
    exterior y más allá hay **farlands congeladas** de varios miles de
    bloques. Ver `05-borde.md`.
- Mismos datos y mismo generador para las dos; solo cambia la proyección
  (`Proyeccion`: lat/lon ↔ x/z de bloque).

## Escala

Escala horizontal y vertical iguales (sin exageración por defecto).
Relieve total: Everest 8 849 m + fosa de las Marianas ~10 935 m ≈ 19,8 km.

| | 1:8 (por defecto) | 1:6 |
|---|---|---|
| 1 bloque | 8 m | 6 m |
| Everest sobre el mar | ~1 106 bloques | ~1 475 bloques |
| Fosa más profunda | ~1 367 bloques | ~1 823 bloques |
| Relieve total | ~2 473 | ~3 298 |
| Circunferencia (ecuador) | ~5,01 M bloques | ~6,68 M bloques |
| Radio del disco (tierra plana) | ~2,50 M bloques | ~3,33 M bloques |
| Radio del planeta para la curvatura | 796 km | 1 062 km |

- Las dos entran en la altura máxima de vanilla (4064) y dentro del borde de
  30 M bloques: **no hace falta formato de guardado propio**.
- La escala más grande posible sin exagerar ni recortar fosas es ~1:5,2. Más
  grande que eso, o con exageración vertical, necesita la etapa pendiente de
  cubic chunks (formato propio para pasar de 4064) o comprimir las fosas
  (decisión: opción `profundidadMaxima`, recorta lo más hondo, apagada).
- `exageracionVertical` (1,0 por defecto; hasta donde entre en 4064).

## Alturas de la dimensión (nivel del mar en y = 63, como vanilla)

`y = 63 + elevación_m / escala`. Se mantiene el 63 para que mobs, peces,
estructuras y reglas de vanilla que asumen ese nivel sigan andando.

| | min_y | height | techo (max_y) |
|---|---|---|---|
| 1:8 | -1328 | 2704 | 1376 |
| 1:6 | -1776 | 3520 | 1744 |

(16 bloques de margen bajo la fosa para el lecho de roca y ~200 sobre el
Everest para construir; todo múltiplo de 16.)

## Lo que se reutiliza del mod (no se reescribe)

- Generador por ruido de vanilla con **una función de densidad propia**
  (`03-generador.md`), así funcionan solos: LOD aproximado, franja vertical,
  completado, compresión de secciones, cuevas, acuíferos, menas, estructuras.
- Curvatura (`core/HorizonteCurvo`) con radio = 6371 km / escala.
- Opciones de `cubico/` prendidas por defecto **solo en este tipo de mundo**
  (no hay islas flotantes reales: `recortarArriba` es seguro).

## Abiertas (las decide el usuario cuando lleguen)

- Punto de aparición (ciudad o coordenadas): por ahora, elegible al crear el
  mundo + comando `/tierra ir <lat> <lon>`.
- Ríos y lagos (hito opcional, `06-hitos.md`).
