# 04 — Cómo se ayudan el LOD y el generador

## Del generador al LOD

1. **Gratis, por diseño (`03-generador.md`):** `GeneradorAproximado` ya arma
   el horizonte evaluando `finalDensity` e `initialDensityWithoutJaggedness`
   del generador del mundo. Con `SuperficieTierra` dentro del router, el LOD
   aproximado muestra la Tierra real sin cambiar una línea.
2. **Atajo (lo que hace la diferencia):** interfaz `FuenteAltura` en
   `generation/` (`int altura(x, z)`, `Holder<Biome> bioma(x, z)`,
   `boolean exacta()`), implementada por `AlturaTierra`.
   `GeneradorAproximado` la usa si el nivel la tiene, en vez de buscar la
   superficie muestreando densidades: **una consulta por columna en vez de
   decenas**. Con eso:
   - el horizonte entero (radio 8192 chunks) se arma mucho más rápido;

   **Implementado en H5** como `generation/FuenteAltura` (solo `altura(x, z)`:
   el bioma ya lo da la `BiomeSource`, y la altura es exacta por
   construcción). Los tipos de mundo se registran con
   `FuenteAltura.registrar` (Tierra real lo hace en `TierraReal`);
   `GeneradorAproximado` la toma en su contexto y, si está, cada columna es
   una consulta (`AlturaTierra.altura`, que reproduce la interpolación del
   generador) en vez de la búsqueda en la densidad. El log del horizonte
   cuenta "columnas por atajo".
   - los niveles más altos del LOD (9-10, vóxeles de 512-1024 bloques) del
     **planeta entero** se pueden armar al crear el mundo desde las teselas
     (a 1:8: ~5 000 × 2 500 columnas de nivel 10, segundos): horizonte
     instantáneo en cualquier lugar y, de yapa, un mapa del mundo.
     **Decisión H5: no se hace.** El LOD dibuja hasta 8 192 chunks (131 000
     bloques) y en Tierra real la curvatura esconde todo más allá de ~22 000
     bloques desde el suelo (ver punto 3): del planeta (5 M bloques de ancho)
     nunca se vería más que eso. Con el atajo, el horizonte que sí se ve se
     arma rápido igual. Queda como idea para un mapa del mundo.
3. **Curvatura coherente:** este tipo de mundo fija el radio de
   `core/HorizonteCurvo` en 6371 km / escala (796 km a 1:8) y el "horizonte
   real" (`sqrt(2Rh)`, sección 29) sale con ese radio. En multiplayer el
   radio viaja en el paquete de configuración del LOD.
   **Consecuencia:** con ese radio el horizonte geométrico es corto (~1 800
   bloques a nivel del suelo, ~12 600 a 100 bloques de altura). El LOD no
   debería armar ni subir lo que la curvatura tapa: el radio útil sale de
   `sqrt(2Rh) + sqrt(2R·hMax)` con hMax la altura máxima del terreno
   lejano sobre el mar (a 1:8: ~1 106 bloques → incluso desde el suelo se
   ven picos a ~44 000 bloques). Medirlo en H5.

   **Implementado en H5:** el cliente reconoce el mundo por la clave de su
   `dimension_type` (`minecraftlodmod:tierra_8` / `tierra_6`, que el servidor
   ya le manda: no hace falta paquete propio) y usa radio = 6 371 km /
   escala (`RenderLod.radioPlanetaActivo`). **Cambio (pedido del usuario,
   2026-10-01): por ahora no se fuerzan** la curvatura ni el horizonte real
   en Tierra real (horizonte plano, para depurar); siguen las opciones del
   usuario, y si prende la curvatura el radio es el del planeta. El
   relieve lejano del horizonte real es de 2 km en vez de 32 bloques
   (`TierraReal.relieveHorizonte`, `HorizonteCurvo.radioChunks` con relieve):
   a 1:8, desde el suelo, ~1 360 chunks (~21 700 bloques); montañas más
   altas que 2 km detrás del horizonte se cortan antes, a cambio de no
   generar los ~44 000 bloques de radio que pediría el Everest.

## Del LOD / cubico al generador

4. **Franja vertical exacta (`cubico/GeneracionVertical`):** hoy estima la
   superficie en 5 puntos con la densidad inicial; con `FuenteAltura` la
   conoce exacta → márgenes chicos (`margenAbajo` 2 en vez de 4).
   **Implementado en H6:** con `FuenteAltura` la franja sale de la altura en
   las 5×5 esquinas de celda del chunk (`GeneracionVertical.superficieExacta`:
   la superficie generada interpola entre ellas, así que sus extremos están
   ahí) y el margen de abajo es 2 secciones.
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

   **Implementado en H6:** `ConfigLod.cubico(nivel, opción)` = la opción de
   la config o `ConfigLod.cubicoPorDefecto` (Tierra real lo asigna por la
   clave del `dimension_type`). Lo usan `GeneracionVertical` (franja y
   `recortarArriba`) y `SeccionesComprimidas` por nivel.
   **`compartirSeccionesUniformes` no se prende** en Tierra real: con ella el
   servidor se cae cuando el agua fluye sobre una sección compartida (bug de
   `SeccionesCompartidas`, anotado en `NOTES.md` como pedido a la sesión del
   LOD); queda como la config diga. En todo esto, en los demás mundos y dimensiones nada cambia. En Tierra real no se
   pueden apagar desde la config (sí con `-Dminecraftlodmod.tierraSinCubico=true`,
   para medir).

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
