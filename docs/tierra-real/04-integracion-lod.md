# 04 — Cómo se ayudan el LOD y el generador

## Del generador al LOD

1. **Gratis, por diseño (`03-generador.md`):** `GeneradorAproximado` ya arma
   el horizonte evaluando `finalDensity` e `initialDensityWithoutJaggedness`
   del generador del mundo. Con `DensidadTierra` dentro del router, el LOD
   aproximado muestra la Tierra real sin cambiar una línea.
2. **Atajo (lo que hace la diferencia):** interfaz `FuenteAltura` en
   `generation/` (`int altura(x, z)`, `Holder<Biome> bioma(x, z)`,
   `boolean exacta()`), implementada por `AlturaTierra`.
   `GeneradorAproximado` la usa si el nivel la tiene, en vez de buscar la
   superficie muestreando densidades: **una consulta por columna en vez de
   decenas**. Con eso:
   - el horizonte entero (radio 8192 chunks) se arma mucho más rápido;
   - los niveles más altos del LOD (9-10, vóxeles de 512-1024 bloques) del
     **planeta entero** se pueden armar al crear el mundo desde las teselas
     (a 1:8: ~5 000 × 2 500 columnas de nivel 10, segundos): horizonte
     instantáneo en cualquier lugar y, de yapa, un mapa del mundo.
3. **Curvatura coherente:** este tipo de mundo fija el radio de
   `core/HorizonteCurvo` en 6371 km / escala (796 km a 1:8) y el "horizonte
   real" (`sqrt(2Rh)`, sección 29) sale con ese radio. En multiplayer el
   radio viaja en el paquete de configuración del LOD.

## Del LOD / cubico al generador

4. **Franja vertical exacta (`cubico/GeneracionVertical`):** hoy estima la
   superficie en 5 puntos con la densidad inicial; con `FuenteAltura` la
   conoce exacta → márgenes chicos (`margenAbajo` 2 en vez de 4).
   `recortarArriba` es seguro (no hay islas flotantes reales): en la Tierra a
   1:8 la columna típica tiene ~2 700 bloques de alto y la franja útil unos
   pocos cientos → el ahorro medido en el mundo de prueba de 2048 (ruido 6×
   más barato) aplica de lleno.
5. **Océanos:** hasta ~1 370 bloques de agua: secciones de un solo valor →
   `compartirSeccionesUniformes` las deja casi sin costo; las de roca
   profunda las comprime `comprimirSeccionesLejanas` (bloques y luz).
6. **Completado (`cubico/CompletadoVertical`):** funciona igual (usa el mismo
   generador por ruido); el detalle de `AlturaTierra` es determinista, así que
   lo completado coincide con lo que se habría generado.
7. **Por defecto en este tipo de mundo:** `generacionVertical`,
   `recortarArriba`, `compartirSeccionesUniformes` y
   `comprimirSeccionesLejanas` prendidas (en los demás mundos siguen
   apagadas). Implementación: el `world_preset` marca la dimensión y
   `ConfigLod` resuelve "prendido si el mundo es Tierra real o si el usuario
   lo prende".

## Precisión lejos del origen

A 1:8 las coordenadas llegan a ±2,5 M bloques. El LOD ya dibuja relativo a
la celda (shorts en el vértice compacto, sección 25.1) y vanilla relativo a
la cámara; igual, **probar a ±2,5 M** (titileo de vértices, nubes, partículas,
`HorizonteCurvo` con doubles) en el hito del borde.

## Cómo medir (scripts que ya existen en la otra sesión)

La otra sesión usa un servidor dedicado (`./gradlew runServer`) con RCON
(`enable-rcon=true`, clave `lod` en `run/server.properties`), fuerza la
carga de 24×24 chunks con `forceload`, y lee: el resumen `[LOD] Generación
vertical (30 s)` del log (ms de ruido por chunk), el heap tras GC (`jcmd …
GC.heap_info` y `GC.class_histogram`) y compara bloques guardados con un
lector de `.mca` en Python. Pedile al usuario que te pase esos scripts, o
reescribilos igual: son ~100 líneas.

Comparaciones mínimas por hito: ms/chunk de ruido, heap, y **elevación en
puntos conocidos** (ver `06-hitos.md`).
