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
- **Tamaño del cache en disco:** con Deflate por nodo bajó de ~103 KB a
  ~37 KB por chunk (2304 chunks = 86 MB). Sigue siendo mucho para un
  horizonte de km: el siguiente paso es guardar a distancia solo niveles
  gruesos y agregar niveles por encima de la sección.

- **render/: primer render funcional (2026-09-29), a optimizar en Pista B:**
  - Un nivel de LOD por celda de 4×4 chunks (no por nodo), elegido por
    error en pantalla con el FOV efectivo. Falta blend/dithering entre
    niveles (sección 6) y el selector jerárquico real.
  - Sin culling de caras traseras (el orden de vértices no está unificado)
    y sin culling por frustum de celdas: se dibujan todas.
  - Sin perspectiva atmosférica: el LOD termina en un borde duro donde se
    acaban los datos. `AtmosphericPerspective` está lista para usarse.
  - Proyección propia (near 16, far 1.5× radio): no incluye el balanceo de
    cámara al caminar; en espectador no se nota.
  - La luz horneada es fija (de día): de noche el LOD queda claro.
  - Solo singleplayer: lee el cache del servidor integrado. En multiplayer
    falta guardar en el cliente lo que llega por red.
  - `RenderBackend` (interfaz) quedó sin implementar: el render real
    necesita la grilla (para la luz por vértice), no solo la lista de Quad.
    Rediseñar la interfaz cuando llegue el backend de alto rendimiento.
  - Probado con Embeddium: NO (no hay Embeddium en el entorno de dev).

- **Color real de texturas:** solo en singleplayer (el servidor integrado
  usa la paleta del cliente). Un servidor dedicado no tiene texturas y
  genera con el color de mapa; para multiplayer, recolorear en el cliente
  (guardar el id de bloque dominante en los 2 bytes reservados del
  supervóxel). Revisar con resource packs y bloques de mods.

- **Texturas en el LOD (estilo Voxy):** cada supervóxel guarda el estado de
  bloque dominante de su superficie (2 bytes antes reservados, sección 5) y
  el shader `lod_textura` dibuja la textura del atlas activo repetida por
  bloque, con mipmaps; la textura aporta solo detalle (textura / su
  promedio), así el color medio y el tinte de bioma se conservan. Revisar
  en hardware real: costo del shader en iGPU, mezcla de mipmaps entre
  texturas vecinas del atlas a mucha distancia, y cómo se ve con resource
  packs de 32x/64x y con Embeddium. Iris/shaderpacks no aplican a este
  shader. Los ids de estado > 65535 (muchos mods) quedan sin textura.

- **Niveles grandes + quadtree (hecho, a ajustar en Pista B):** nodos de 16³
  vóxeles de 2^L bloques para L = 5..8 (`NivelesGrandes`, reconstruidos
  por lotes cada 5 s desde el nivel 4), y el plan de render es un quadtree
  de teselas (niveles 3-8) que baja a celdas de 4×4 chunks solo cerca. En
  6 km de radio el plan tiene < 3000 piezas en vez de ~15.000 celdas.
  Pendiente: paredes en los bordes de las teselas (no se omiten caras
  contra la tesela vecina), transición visible entre niveles (falta el
  dithering de la sección 6), y medir en hardware real el costo de armar
  teselas de nivel 3-4 (leen hasta 4096 nodos por sección).

- **Calidad visual vs. vanilla (2026-09-29):** comparación a 1920×1080,
  preset Medio (umbral 2.5): vanilla 32 chunks contra vanilla 8 + LOD, mismo
  lugar. El LOD conserva forma, texturas y tonos del terreno lejano. Falta
  para acercarse más: oclusión ambiental por vértice (vanilla oscurece
  esquinas; el LOD es más plano), y el tono del cielo/niebla lejana (la
  niebla estirada cambia el color del cielo cerca del horizonte).
  Las pruebas anteriores con umbral 6-16 px y ventana 854×480 exageraban
  los cubos: no usar esos valores para juzgar calidad.
- Plantas, flores, pasto, antorchas y rieles ya no se dibujan como cubos
  (se ve el suelo); nieve fina y alfombras pintan la cara de arriba del
  bloque de abajo; algas/pasto marino cuentan como agua
  (`LectorSeccionMinecraft.Forma`). Bloques no cúbicos con colisión
  (cercas, escaleras, losas) siguen como cubos.
- Almacenamiento ya es por sección cúbica 16×16×16 (vacías no se guardan,
  uniformes = un vóxel). Con niveles grandes: ~27 KB por chunk.

## Errores recurrentes / bloqueos

- ~~Sesión cloud: `./gradlew build` no podía bajar NeoForge (403 del proxy).~~
  **Resuelto (2026-09-29):** con la red ampliada, `./gradlew build` completo
  (incluye `test`) pasa contra NeoForge 21.1.252 (última 21.1.x publicada en
  maven.neoforged.net a esa fecha).

## Mejoras notadas, no aplicadas todavía

- **Bug corregido en `HierarchicalReducer` (visto con el primer render):**
  promediaba el aire como negro y descartaba la luz horneada, así que todo
  nivel ≥1 salía oscuro; además en empate aire/sólido ganaba el aire y las
  superficies finas se hundían. `VERSION_ALGORITMO` subió a 2.
- `GeneradorLocal`: los chunks que no entran a la cola quedan pendientes y
  se reintentan por tick (antes los chunks que nunca se descargan, como
  spawn o forceload, se perdían para siempre).
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

- **Sección 5, resuelto:** Deflate POR NODO (`storage/CompresionNodos`), no
  por archivo: la tabla de offsets sigue permitiendo leer un nodo suelto.
  Pendientes y cache de lectura también guardan los nodos comprimidos.
- **`RegionFileStore` append-only (resuelto):** datos `r.X.Z.GEN.mlod` solo
  crecen, índice `r.X.Z.idx` aparte (atómico), compactación a una
  generación nueva cuando lo muerto supera a lo vivo. Los archivos del
  formato viejo (`r.X.Z.mlod` sin generación) quedan huérfanos en mundos
  existentes: se podrían borrar al arrancar.
- `BoundedRegionCache` ya se usa como cache global de lectura dentro de
  `RegionFileStore`, con una clave que empaqueta dimensión + región + nodo
  (`RegionFileStore.claveCache`; regiones más allá de ±8192 no se cachean).

- El build pasó de NeoGradle userdev a ModDevGradle 2.0.148 (plugin oficial
  actual del MDK). `neoforge.mods.toml` se movió a `src/main/templates/` y se
  expande con `generateModMetadata`: antes se empaquetaba con los `${...}`
  literales y el mod no habría cargado.

- **Sección 25 (Voxy/FP2), puntos 1 y 2 aplicados:**
  - Vértice compacto de 12 bytes (antes 36): `GeometriaLod.escribirCompacto`
    + elemento propio `RenderLod.POSICION_SPRITE` (4 shorts, uso UV entero)
    + tabla de sprites `PaletaTexturas.TABLA_SPRITES` (textura dinámica,
    3 texeles por sprite) que el shader lee con `texelFetch`. Límites:
    posiciones enteras dentro de ±32767 bloques de la celda (lanza si no),
    y hasta 65535 sprites distintos (los que sobren van con color plano).
  - El cálculo de VRAM de las estadísticas ya no cuenta índices por malla:
    los índices de QUADS son un buffer secuencial compartido de Minecraft.
  - Reducción con bloque representativo: el color se promedia solo entre
    los vóxeles del estado elegido. `VERSION_ALGORITMO` subió a 9.
  - **No aplicado todavía del punto 2:** resolver el color en el cliente y
    guardar por paleta. El vóxel no guarda el bioma, así que el cliente no
    puede recalcular el tinte; haría falta agregar el bioma (cambia el
    formato de 8 bytes de la sección 5), a decidir junto con el cache
    cliente de multiplayer.
  - Puntos 6-8 de la sección 25 siguen pendientes.
- **Sección 25, puntos 3 y 4 aplicados:**
  - Una malla por dirección de cara por celda, con su plano mínimo/máximo;
    `RenderLod.dibujarPasada` saltea los grupos que miran para el otro lado
    y el resto lo descarta el culling de caras traseras (vértices
    antihorarios desde afuera, `GeometriaLod.antihorarioDirecto`).
  - Dibujo al estilo del terreno vanilla: shader y uniforms una vez por
    pasada, y por buffer solo `ChunkOffset` + `draw()`. El fallback con
    `position_color` (sin ChunkOffset) sigue con `drawWithShader`.
  - `GreedyMesher.Vecinos`: las caras del borde de una sección tapadas por
    la sección de arriba/abajo o por el chunk vecino (mismo nivel) ya no se
    generan. Los costados hacia terreno vanilla se siguen omitiendo enteros.
    Reemplaza a `vecinoCubierto`, que omitía el costado completo si el
    vecino tenía datos aunque ahí fuera aire (agujeros en acantilados de
    borde de chunk).
  - **Limitaciones:** el vecino se lee al MISMO nivel que la sección, aunque
    esa celda vecina se dibuje en otro nivel: puede quedar una rendija o
    cara de más en el límite entre niveles (antes era peor: costado
    omitido entero). En teselas (niveles 3-8) solo se usan las bandas de
    arriba y abajo; los costados de tesela siguen siendo paredes
    (pendiente, leer solo el borde de la tesela vecina).
- **Sección 25, punto 5 aplicado (oclusión ambiental por vértice):**
  - `GreedyMesher.oclusionCara`: por esquina, costados + diagonal en la capa
    de enfrente (sólidos y vegetación ocluyen, agua no); solo se fusionan
    caras con la misma oclusión en sus 4 esquinas. `Quad.oclusion` la lleva
    empaquetada (2 bits por esquina).
  - `GeometriaLod`: brillo por nivel `BRILLO_OCLUSION` {0.55, 0.7, 0.85, 1}
    multiplicado al color, y la diagonal del quad se elige para unir las
    esquinas más claras (`ordenEsquinas`), rotando sin cambiar el sentido.
  - Opción `oclusionAmbiental` (cliente, default true); cambiarla rearma el LOD.
  - **Solo caras de arriba (+Y).** Medido en el cliente (mismo lugar, sobre
    el océano en 0,0): sin oclusión 28,5M vértices guardados / 14,8M
    dibujados; con oclusión en todas las caras 40,4M / 21,3M (+42%); solo
    arriba 31,8M / 18,1M (+12% / +23%). Limitarla a niveles 0-2 (probado y descartado) no cambió
    nada: casi todos los vértices están en esos niveles. Las mediciones en
    otros lugares fueron ruidosas (el servidor seguía generando chunks);
    falta una medición determinista sobre los datos guardados.
  - **Pendiente de Pista B:** ajustar `BRILLO_OCLUSION` viéndolo en monitor
    real, y decidir si en el preset Mínimo (A275) conviene apagarla por
    defecto según el costo en vértices medido.
  - En diagonales que caen fuera de dos grillas a la vez (esquina de
    sección) no hay dato y se asume que no ocluye.

- **Arreglos reportados en la primera prueba del jar (2026-09-29):**
  - Calibración colgada: si el jugador salía del benchmark antes de
    terminar, `SesionCalibracion` quedaba activa y en el siguiente mundo lo
    teletransportaba a los puntos de benchmark, cambiaba la calidad y dejaba
    el botón "Calibrar" deshabilitado. Ahora se cancela al salir (solo si ya
    había arrancado: abrir un mundo desde el menú también dispara
    LoggingOut), si el frame corre en otro servidor, o si el mundo abierto no
    es el de benchmark; y el botón pasa a "Cancelar calibración". Verificado
    que la calibración completa sigue funcionando; la cancelación a mitad no
    se llegó a reproducir en Xvfb (termina en ~1 min con render por software).
  - Niebla: se corre hasta el alcance REAL del LOD dibujado, no hasta el radio
    del preset (antes, con pocas celdas, la niebla se iba a 2560 bloques y el
    borde del terreno conocido quedaba expuesto).
  - LOD que "se movía" al caminar: el LOD armaba su propia proyección sin el
    balanceo de cámara de vanilla. Ahora usa la proyección del frame con
    near/far reemplazados (`PlanCeldas.conPlanosDeProfundidad`). Falta verlo
    en monitor real (Pista B).
  - Opción "LOD activado" en el menú del mod para comparar contra vanilla.
  - Huecos: dentro de la distancia vanilla solo se le dejan a vanilla los
    chunks que el cliente YA tiene cargados (punto 6 de la sección 25, versión
    por chunk cargado; no mira si vanilla ya compiló la malla).
  - Pregenerador integrado (`PregeneradorChunks` + `EspiralChunks`): tickets
    propios de nivel 33, del jugador hacia afuera, se frena con MSPT > 40 ms o
    extracción atrasada. Solo singleplayer (la opción vive en la config del
    cliente). Pendiente: versión para servidor dedicado (config de servidor),
    y "rough generation" para radios de miles de chunks, que con generación
    vanilla completa ocupan cientos de GB y tardan días.
- **Oclusión por relieve (raycasting grueso, `OclusionRelieve`):** horizonte
  por dirección (2048 sectores) armado con columnas de 32 bloques del nivel 5,
  de cerca a lejos; una pieza se oculta si su tope queda bajo el horizonte de
  lo estrictamente más cercano. Conservador (suelo del vóxel más alto como
  oclusor, tope de las columnas tocadas como ocluido, nada dentro de 64
  bloques, nada bajo tierra). Medido en un valle nevado del benchmark: 77 de
  218 piezas ocultas, −33% llamadas, −13% vértices dibujados, 0,8 ms por
  replanificación; capturas con y sin oclusión idénticas salvo nubes.
  Relieve leído con tope de 32 regiones por replanificación. Pendiente: la
  curvatura/altura no se replanifica hasta moverse 8 bloques en Y o 3 s.
