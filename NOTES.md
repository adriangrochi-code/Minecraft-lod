# Notas de trabajo

Bitácora viva. Claude Code anota acá (ver CLAUDE.md, reglas 4 y 7):
- Mejoras notadas en módulos ya cerrados, sin tocarlos de una.
- Errores que se repitieron 2+ veces de la misma forma, con la hipótesis.
- Cosas que necesitan verificación visual (Pista B) y quedaron sin resolver
  en una sesión de Pista A.

---

## Pendiente de Pista B

- `PresupuestoMemoria.BYTES_ESTIMADOS_POR_TAREA` (256 KB) es un estimado,
  no una medición: perfilar la RAM real por tarea de generación con el
  juego corriendo y ajustarlo.
- Orden de recuperación del `PerformanceAutoTuner` con tres perillas: al ir
  lento baja concurrencia → umbral → radio (sección 23); al sobrar margen
  recupera concurrencia → radio → umbral. La concurrencia se recupera
  primero porque hace falta generación para llenar el radio recuperado.
  Validar con el juego que no oscile.

- `LectorSeccionMinecraft` hornea la luz al cargar el chunk; en chunks recién
  generados el motor de luz puede no haber corrido todavía y se asume cielo
  abierto (15). Ver con el juego si aparecen cuevas/voladizos "iluminados"
  en el LOD; si pasa, capturar en un evento posterior a la iluminación.
- Colores: `getMapColor` se llama con `EmptyBlockGetter` (no se puede tocar el
  mundo desde el pool). Bloques cuyo color de mapa depende de la posición
  caen a su color base — revisar visualmente con mods de worldgen.

- **network/: prueba real con dos instancias.** Protocolo registrado y
  testeado (codecs, limitador, lectura del store), y el servidor dedicado
  arranca con él. Falta: cliente conectado a servidor dedicado pidiendo
  nodos (`ClienteLod.pedir`), cliente con mod contra servidor sin mod (debe
  quedar en compatibilidad, `servidorTieneCompanion() == false`) y cliente
  vanilla contra servidor con mod (debe poder entrar: payloads `optional()`).
  Requiere que render/ asigne el receptor (`ProtocoloLod.asignarReceptor`)
  y que el selector del cliente decida qué pedir.

- **config/:** el botón "Calibrar desde este preset" (sección 11) es un
  texto de "próximamente" hasta que exista benchmark/. `autoAjuste` y
  `fpsObjetivo` están persistidos pero nadie los consume todavía: los usa
  `ControlDeRendimiento` cuando render/ lo arranque.

- **benchmark/: la calibración todavía no mide el LOD.** El recorrido
  completo funciona (mundo fijo, 4 puntos, escalones, guardado), pero hasta
  que render/ registre `SesionCalibracion.asignarAplicador`, cambiar de
  escalón no cambia lo que se dibuja: hoy mide solo el costo vanilla de cada
  escena. Al conectar render/, revisar también que 10 s de calentamiento
  alcancen para que el LOD del punto esté generado antes de medir.
- Puntos de `PuntosBenchmark` verificados con capturas bajo Xvfb (llanura
  plana de piedra, bosque denso, cumbre nevada a y=196, caverna a y=-45).
  Falta ver en hardware real si la orientación (yaw/pitch) es la más
  representativa para el LOD, cuando exista.
- **Tamaño del cache en disco:** 4096 chunks generados = 422 MB de `.mlod`
  (~100 KB/chunk), casi todo nivel 0 sin comprimir. Para radios grandes es
  inviable: evaluar no persistir el nivel 0 (se puede re-extraer del mundo)
  o comprimir por bloques (ver la contradicción de la sección 5 anotada abajo).

## Errores recurrentes / bloqueos

- ~~Sesión cloud: `./gradlew build` no podía bajar NeoForge (403 del proxy).~~
  **Resuelto (2026-09-29):** con la red ampliada, `./gradlew build` completo
  (incluye `test`) pasa contra NeoForge 21.1.252 (última 21.1.x publicada en
  maven.neoforged.net a esa fecha).

## Mejoras notadas, no aplicadas todavía

- **Generación LOCAL (`GeneradorLocal`), limitaciones conocidas:**
  - Invalidación gruesa: un chunk se regenera al descargarse si
    `isUnsaved()`. No está verificado que el Unload llegue antes del guardado
    (si llega después, las ediciones no se reflejan hasta otra carga). La
    invalidación fina por `BlockEvent` queda para cuando la pida `network/`.
  - `store.contiene(...)` corre en el hilo del servidor al cargar cada chunk:
    la primera vez por región lee el header de disco. Barato, pero es I/O en
    el hilo principal.
  - `idDimension` usa hash para dimensiones no vanilla: dos dimensiones de
    mods pueden caer en el mismo id (1/253) y pisarse nodos. Solución real:
    un mapa id↔dimensión persistido junto al cache.
  - Chunks descartados por cola llena se reintentan solo al recargarse.
- **network/, decisiones a revisar:**
  - Ritmo por jugador fijo (1024 nodos/s, ráfaga 2048) y radio servido =
    `radioLodChunks` del preset del SERVIDOR. Deberían salir de la config
    del servidor cuando exista `config/`.
  - Pedidos de chunks nunca generados responden `NO_GENERADO`; el servidor
    no los genera a pedido (sería forzar generación vanilla de terreno
    lejano, costo que la sección 17 reserva para "rough generation").
  - El cliente todavía no guarda lo recibido: falta un cache cliente
    (disco por servidor + `BoundedRegionCache`, que antes necesita la clave
    con región+dimensión anotada más abajo).
- **benchmark/, decisiones tomadas (con el usuario, 2026-09-29):**
  - Mundo de benchmark APARTE (`minecraftlodmod-benchmark`, seed 12345) en
    vez de dimensión: cumple "calibrar sin tener un mundo propio" (sección
    9) y la seed fija sale gratis, sin mixin ni generador propio. Cambia la
    sección 9 del documento de arquitectura: "volver a la posición
    original" pasa a ser "volver al menú principal".
  - Escalones: los 6 presets + 2 intermedios por tramo (16 en total),
    todos con el fps objetivo del preset elegido. Resuelve el punto abierto
    de la sección 14.
  - Pasa un escalón si su frame time promedio ≤ 90% del objetivo.
  - `/locate biome` NO sirve para ubicar puntos: reporta biomas 3D a la
    altura de búsqueda (sobre un océano puede decir "picos"). Los puntos
    se eligieron analizando el relieve real con los `.mlod` del propio mod.
  - El botón existe solo en la pantalla de Cloth; la automática de
    NeoForge no admite botones propios.
- **config/, decisiones tomadas:**
  - La persistencia es `ModConfigSpec` de NeoForge, no AutoConfig de Cloth:
    así Cloth queda como dependencia OPCIONAL (solo la pantalla) y un
    servidor dedicado no la necesita. Sin Cloth, la pantalla automática de
    NeoForge edita los mismos valores (mismas claves de idioma).
  - Los cambios de calidad se aplican al abrir el próximo mundo (el pool y
    el cache se dimensionan al arrancar el servidor), no en caliente.
  - NeoForge 21.1 guarda la config de SERVIDOR en `config/`, no por mundo.
  - Servidor con preset AUTOMATICO: en singleplayer usa la calidad del
    cliente; en dedicado, la heurística por hardware.
- **Validación sin monitor (útil para Pista B):** el cliente corre bajo
  `Xvfb` con render por software (Mesa llvmpipe) y se maneja con `xdotool`
  + capturas con `xwd`. Así se verificó la pantalla de config, que guarda,
  y que un mundo singleplayer genera con la calidad elegida. Sirve para
  chequeos funcionales; NO para juzgar rendimiento ni calidad visual fina.
- `build.gradle`: `neoForge.unitTest` habilitado para poder testear clases
  con tipos de Minecraft. Cuesta ~7 s extra de arranque por corrida de test.
- Validado con `runServer` headless (seed 12345): el spawn genera
  `dim0/r.0.0.mlod` y `r.0.-1.mlod` y los nodos decodifican a un heightmap
  coherente.

- **Contradicción en la sección 5 (a decidir):** pide GZIP "a nivel de
  archivo completo" y a la vez lectura parcial por tabla de offsets. Con el
  archivo entero comprimido no se puede hacer seek a un nodo: hay que
  descomprimir todo. `storage/RegionFileStore` prioriza la lectura parcial
  (datos sin comprimir, el RLE por nodo ya comprime el terreno natural). Si
  se quiere compresión, la opción compatible es Deflate por bloques de
  varios nodos, no por archivo.
- `BoundedRegionCache` usa como clave solo `claveNodo`, que es relativa a la
  región: dos regiones distintas chocan en el mismo cache. Para usarlo como
  cache global delante de `RegionFileStore` hace falta una clave que incluya
  región + dimensión (o un cache por región). No se tocó por la regla 4.

- El build pasó de NeoGradle userdev a ModDevGradle 2.0.148 (plugin oficial
  actual del MDK). `neoforge.mods.toml` se movió a `src/main/templates/` y se
  expande con `generateModMetadata`: antes se empaquetaba con los `${...}`
  literales y el mod no habría cargado.
