# Notas de trabajo

Bitácora viva. Claude Code anota acá (ver CLAUDE.md, reglas 4 y 7):
- Mejoras notadas en módulos ya cerrados, sin tocarlos de una.
- Errores que se repitieron 2+ veces de la misma forma, con la hipótesis.
- Cosas que necesitan verificación visual (Pista B) y quedaron sin resolver
  en una sesión de Pista A.

---

## Pendiente de Pista B

- **Generación por franja vertical (0.26.1, cubico/GeneracionVertical):**
  medida solo en servidor dedicado (tiempos), sin mirar el terreno. Falta
  recorrer en el juego un mundo alto con la opción prendida: que el borde
  franja/relleno no deje escalones raros ni agua o lava colgando, y cómo se
  ve la pizarra/relleno al bajar. El completado (0.26.2) está verificado
  bloque por bloque contra un mundo completo, no a la vista: falta mirar
  que no se note la llegada de las cuevas al bajar rápido (completa 2
  secciones de adelanto) y que el reenvío del chunk no tironee. La
  decoración (0.26.7) corre features sobre el mundo vivo con escrituras
  filtradas: mirar que no queden plantas cortadas en el borde del chunk
  (lo que cruzaría al vecino se descarta).
- **Secciones lejanas comprimidas / compartidas (0.26.3):** medidas en
  servidor dedicado sin jugadores. Falta jugar con ellas prendidas (que
  bajar o subir rápido no tironee al descomprimir; ~4000
  descompresiones/recompresiones cada 30 s en la prueba, por la extracción
  del LOD) y probar con otros mods (Lithium/C2ME-like escriben secciones
  desde otros hilos: la compartida avisa con error, la comprimida no).
  Luz comprimida (0.26.4): un mod que escriba la luz por `getData()` sobre
  una capa comprimida escribiría en una copia temporal (vanilla no lo hace;
  Starlight/ScalableLux reemplazan el motor y no usan estas capas igual).

- **Sincronización vertical (0.26.0, cubico/):** probar con Sodium y con
  VulkanMod en la PC (acá solo vanilla + OpenGL en Xvfb). Limitaciones
  conocidas, para la próxima vuelta:
  - la luz del cielo del cliente no conoce los bloques de arriba del rango
    (Vertigo manda las "fuentes de luz del cielo"): al romper un bloque
    cerca del borde puede aclararse un momento hasta que llega la luz del
    servidor;
  - entidades en secciones ocultas se ven flotando sobre el LOD;
  - el LOD se dibuja antes y vanilla siempre queda encima (se limpia la
    profundidad): un caso raro de una isla del LOD delante de terreno
    vanilla se vería mal;
  - al subir una sección se mandan (2·distancia+1)² secciones de golpe;
    si tironea, repartirlo en varios ticks, las más cercanas primero;
  - en multiplayer el LOD vertical necesita los datos LOD en el cliente
    (hoy solo singleplayer, como el resto del LOD por red).

- **Shaders de Voxy, etapa B (0.26.8, opción experimental `contratoVoxy`):**
  el LOD se dibuja con `voxy_opaque` del pack (`render/DibujoVoxy`), `VOXY`
  definido para todo el pack, `vx*` y `vxDepthTex*` en Iris. Verificado en
  Xvfb (llvmpipe) con un pack de prueba y con Complementary r5.9.3: arma,
  compila, sin errores de GL, el LOD sale con la luz, niebla y nubes del
  pack. Falta, y hace falta verlo en la PC:
  - hecho en 0.26.9: agua en pasada translúcida (`voxy_translucent`, solo la
    superficie), `customId` por estado (`block.properties` del pack, tabla
    R32I) y luz de cielo horneada en `lightMap`. A juzgar en la PC: el fondo
    marino se ve a través del agua en escalones de vóxeles grandes (cuadros
    más claros y oscuros según la profundidad); ríos y cascadas en altura
    quedan sin paredes de agua; `excludeLodsFromVanillaDepth` sigue sin usarse;
  - sombras del LOD (Complementary con `VOXY` usa colortex18 de sombra en
    pantalla; el LOD no está en el shadow map);
  - Iris 1.8 admite colortex0-15: con el contrato prendido se amplía a 32
    (`MixinObjetivosIris`); si el pipeline igual falla con `VOXY`, el pack
    se recarga sin él (`MixinPipelineIris`) y el LOD vuelve a
    gbuffers_terrain. Juzgar en GPU real que no haya buffers de más;
  - probar con NVIDIA: Mesa acepta inicializadores no constantes en
    globales y otros drivers podrían no hacerlo.

- **Vóxeles que se notan (0.25.0):** juzgar en monitor real el fundido entre
  niveles (`FundidoNiveles.DURACION_NANOS` = 0,4 s; tramado Bayer 4×4) y si
  la aproximación fina cerca alcanza. Costo medido del tope de píxeles en
  Xvfb (1280×720, misma vista): 4 px = 2,8 M vértices, 2 px = 4,4 M,
  1 px = 7,0 M. Si en la 1060 sobra, bajar el valor por defecto de
  "Píxeles máximos" a 2 px.
- **Rocas y copas "flotantes" lejanas** (vista del punto bosque del mundo de
  benchmark, hacia el oeste): capas grises con cielo entre ellas. No cambian
  con el tope de píxeles ni con "Descartar cuevas". En Xvfb el auto-ajuste
  recorta el radio (6 FPS) y quedan fuera de vista, así que no se pudo
  aislar: revisar con radio fijo en la PC.
- **Prioridad de chunks reales sobre la aproximación (0.24.1):** resuelto
  (`GenerationTaskScheduler.intentarEnviarDeFondo`/`hayPrioritariasEsperando`,
  cesión entre evaluaciones de densidad en `GeneradorAproximado`). Pendiente
  medir en la A275 cuánto tarda un chunk aproximado cercano (en Xvfb, con el
  procesador compartido con el render por software, son minutos) y si con
  pregeneración continua el horizonte avanza (el trabajo de fondo espera
  mientras haya extracción encolada).
- **Luz de bloque de noche (0.24.0):** juzgar el color cálido
  (`vec3(1.0, 0.86, 0.66)` en `lod_textura.fsh`/`_vk`) y la curva
  `pow(luz/3, 1.5)`. La luz de bloque va cuantizada a 2 bits
  (`SuperVoxel.luzBloque`) y por vértice sale del vóxel del quad, no
  suavizada por esquina: si se ven bordes duros de luz, suavizarla como
  `VertexLightSampler`. El camino de colores planos (vanilla
  `position_color`) no la usa.
- **Acabado del LOD (0.23.0, `render/AcabadoLod`):** ajustar viéndolo
  `RenderLod.FUERZA_SSAO` (0,8), el radio del SSAO (2,5% de la distancia,
  en `lod_ssao.fsh`) y el valor por defecto de la neblina (0,5). Medir el
  costo del SSAO en la A275 y el 3500U; si es alto, apagarlo por defecto en
  el preset Mínimo. Con VulkanMod el acabado no corre (sus shaders no se
  convierten): falta portarlo o hacer la niebla dentro de `lod_textura_vk`.
- **Atlas del LOD (0.22.0):** juzgar en monitor real el brillo de los huecos
  del follaje (`AtlasLod.HUECO_FOLLAJE` = 0,45) y si los modelos horneados
  (escaleras, cercos, losas) se ven bien en aldeas lejanas. El costado
  horneado se mira desde el norte y se usa en los 4 costados: una escalera
  de lado muestra el perfil del norte en todas sus caras.
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

- **VulkanMod (0.11.0):** reproducido con lavapipe en Xvfb: los shaders propios no pasan
  el conversor de VulkanMod (NPE "has no initialized pipeline"). Con VulkanMod el LOD usa
  colores planos con shaders vanilla y FSR queda apagado. Falta ver en la PC real (i5 +
  GTX 1060) que dibuje bien y cuánto cuesta; en lavapipe VulkanMod no dibuja el terreno
  vanilla ni sin el LOD (problema del entorno).
- **Texturas con VulkanMod (0.13.0):** variante `lod_textura_vk` + formato con posición en
  dos elementos UV SHORT×2. Causas encontradas (decompilando VulkanMod 0.5.5-dev+3.1):
  solo intercepta `new ShaderInstance(provider, String, formato)` (con ResourceLocation el
  shader queda sin pipeline, sin error en el log); arma los atributos por uso+tipo sin mirar
  la cantidad (UV+SHORT = R16G16_SINT); su conversor no acepta `flat`, cambia `%` por
  `mod()` de floats y numera los samplers en orden de aparición entre las dos etapas.
  Verificado en Xvfb con lavapipe (se ven las texturas). Pista B: verlo en la GTX 1060.
  FSR/escalado con VulkanMod: se podrían portar igual (constructor con String + reglas del
  conversor), pero leen y escriben framebuffers de GL: pendiente.
- **Espigas con VulkanMod (0.14.0, visto en video en la GTX 1060):** el `AutoIndexBuffer` de
  quads de VulkanMod es compartido y arranca en 65536 vértices; si un VBO más grande lo hace
  crecer, libera el viejo y los VBO ya subidos siguen apuntando a él (índices basura). También
  crece solo x2 por vez. Con VulkanMod el LOD parte las mallas en piezas de <= 65536 vértices
  (`RenderLod.MAX_VERTICES_VULKANMOD`); en lavapipe, 0 "Reallocating AutoIndexBuffer" en el log.
- **Luz según la hora (0.14.0):** el color del LOD se multiplica por el píxel (bloque 0,
  cielo 15) del lightmap de vanilla (`RenderLod.colorLuzCielo`, `AccesoLightTexture`),
  normalizado al mediodía. Verificado en Xvfb con OpenGL y VulkanMod (mediodía #FFFFFF,
  medianoche #51517F). Limitación: la luz horneada no separa cielo y bloque, así que de noche
  también se oscurece lo iluminado por antorchas. Con shaderpack (Iris) no aplica: el pack
  ilumina con el lightmap.
- **Nieve (0.14.0):** bit 1 de `SuperVoxel.flags` = nevado; `GreedyMesher` dibuja solo la cara
  de arriba con color/estado de la capa de nieve (lo fija `PaletaTexturas` en el cliente);
  el reductor lo conserva si cubre >= la mitad de las columnas. Tests con bloques reales.
  Pista B: verlo en el juego (en Xvfb la zona nevada a la vista era del generador aproximado,
  que pone bloques de nieve enteros en biomas fríos). Alfombras: siguen pintando el bloque entero.
- **Shaders (0.11.0):** con Iris el LOD pasa por el gbuffers_terrain del pack. Probado solo
  con un pack mínimo propio (Complementary en software es pura niebla). Falta ver en
  hardware real con Complementary/BSL: niebla de borde (tapa el LOD pasada la distancia
  vanilla), sombras (el LOD no entra en la pasada de sombras), agua (el LOD la dibuja
  opaca con el resto del terreno) y costo por llamada de Iris.
- **FSR con transparentes, mobs y contorno de bloque (0.10.0):** `ScreenSize` ahora toma el
  tamaño del framebuffer chico (`MixinShaderInstance`). Confirmar en hardware real.

- **Escalado temporal / XeSS / DLSS (0.12.0):** probar en hardware real.
  - TEMPORAL: estelas al moverse (sobre todo mobs, que no tienen vectores propios) y
    nitidez quieto. En Xvfb (1 FPS) solo se pudo ver quieto: queda igual que nativo.
  - XeSS: libxess.dll en `.minecraft/minecraftlodmod/`. Mirar el log: "XeSS x.y.z
    cargado", "Vulkan listo en <GPU>", "XeSS listo". Si la imagen tiembla o queda borrosa
    quieta, probar "Invertir jitter".
  - DLSS: DLL de Streamline en `.minecraft/minecraftlodmod/streamline/` (solo RTX). El
    log de Streamline queda en esa misma carpeta.
  - Todavía sin semáforos compartidos (glFinish + fence): medir cuánto cuesta.

## Errores recurrentes / bloqueos

### Pedido a la sesión del LOD (desde la rama `claude/tierra-real`, 2026-10-01)

- **Crash con `compartirSeccionesUniformes` (cualquier mundo):** el agua que
  fluye sobre una sección compartida tira `IllegalArgumentException: The value
  1 is not in the specified inclusive range of 0 to 0` en
  `ZeroBitStorage.getAndSet` (desde `FlowingFluid.spreadTo` →
  `LevelChunk.setBlockState` → `LevelChunkSection.setBlockState`). Causa:
  `SeccionesCompartidas.copiarSiCompartida` copia con
  `s.getStates().copy()`, y en vanilla `SingleValuePalette.copy()` devuelve
  **la misma paleta**, cuyo manejador de cambio de tamaño es el contenedor
  compartido original: al escribir un bloque distinto en la "copia", la
  paleta agranda el **compartido** (corrompiéndolo para todas las secciones
  que lo usan) y la copia queda con almacenamiento de 0 bits. Arreglo
  propuesto (una línea): crear un contenedor nuevo con el único valor, p. ej.
  `new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, s.getStates().get(0, 0, 0), PalettedContainer.Strategy.SECTION_STATES)`
  (o `recreate()` + `set`), en vez de `copy()`. Reproducido en Tierra real
  (forceload de 15×15 chunks en los Alpes); crash report
  `run/crash-reports/crash-2026-10-01_20.54.36-server.txt` en esa máquina.
  Mientras tanto Tierra real no la prende por defecto.

- ~~El repo no compila desde GitHub~~ (`.gitignore` con `build/` ignoraba
  `vulkanmod/render/chunk/build/`). **Resuelto 2026-10-01:** la sesión del LOD
  ancló las rutas y subió la carpeta; `claude/tierra-real` lo juntó y
  `./gradlew build` pasa desde un clon limpio (357 tests).


- ~~Sesión cloud: `./gradlew build` no podía bajar NeoForge (403 del proxy).~~
  **Resuelto (2026-09-29):** con la red ampliada, `./gradlew build` completo
  (incluye `test`) pasa contra NeoForge 21.1.252 (última 21.1.x publicada en
  maven.neoforged.net a esa fecha).

## Tierra real (rama `claude/tierra-real`)

- Diseño y resultados: `docs/tierra-real/` (hitos y medidas en `06-hitos.md`),
  sección 33 de la arquitectura. Hechos H0–H3.
- Datos: `.minecraft/minecraftlodmod/tierra/tierra.lodt`, preparado con
  `java -cp <clases> com.example.minecraftlodmod.tierra.PreparadorDatos
  ETOPO_2022_v1_30s_N90W180_surface.tif tierra.lodt` (657 MB, ~5 min). Sin
  el archivo, el mundo sale como fondo de mar plano y el log lo dice.
- Comandos de prueba: `/tierra ir <lat> <lon>`, `/tierra medir <lat> <lon>`.
- Pendiente de Pista B: ver el mundo en el cliente (pantalla de crear mundo
  con los 4 tipos, horizonte del LOD sobre Tierra real). En Xvfb (render por
  software) el mundo carga y se juega, el horizonte aproximado se genera con
  el atajo (0 evaluaciones de densidad), pero **no se ve terreno lejano** en
  las capturas desde la cumbre del Cervino: ¿Mesa o la curvatura de 796 km?
  Probar en la PC con `/tierra ir 45.9763 7.6586` y mirar al sur.

## Mejoras notadas, no aplicadas todavía

- **Índice append-only (hecho en 0.25.5):** foto + bloques de diario con
  CRC; se junta en una foto cuando el diario pasa la mitad de los nodos.
- **Plan dentro del cuadro:** el primer plan al entrar tarda 86-126 ms en
  Xvfb (JIT frío + relieve) y después 1-15 ms. Si en la PC sigue habiendo un
  tirón al cruzar chunks, mover `planificar` a un hilo aparte (hoy lee
  estado del render sin sincronizar: hay que separar entrada y salida).

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
- **Prioridad por vista (`PrioridadVista`):** costo = distancia² × factor
  (1 en vista ±60°, 3 hasta ±100°, 8 atrás; todo "en vista" a menos de 48
  bloques). Ordena el armado de mallas, los pendientes de extracción y el
  pregenerador (ventana de 512 candidatos de la espiral).
- **Zoom solo en lo que se mira:** `PlanCeldas.Vista` + `fovPara`: con FOV
  efectivo menor al de las opciones, solo las piezas que tocan el cono de la
  vista (media apertura horizontal del FOV con zoom + 10°) usan ese FOV; el
  resto, el normal. Con zoom, girar más de 10° replanifica. Probado con
  catalejo real: mismas 199 piezas, 8,0M → 10,7M vértices (solo lo mirado se
  afinó). Pendiente de Pista B: juzgar la calidad con zoom en monitor real.
- **`/lod pregenerar [on|off|radio <chunks>]`** (`ComandoPregeneracion`): mismo
  valor que la opción del menú (config del cliente), con el avance (anillo,
  chunks nuevos, chunks/s, en curso). Solo singleplayer. Probado en el cliente.
- **Horizonte aproximado (`GeneradorAproximado`, `TerrenoAproximado`):** para
  chunks nunca generados, altura por `RandomState.router().finalDensity()`
  (búsqueda de a 8 bloques con pista de la columna vecina) y superficie por
  bioma (arena, terracota, nieve, piedra, copas de árbol, pasto). Se guardan
  niveles 3 y 4 en claves propias (nivel + 8) con marca propia (nivel 14);
  lo real siempre tiene prioridad (render, teselas 3-4 y niveles grandes vía
  `AccesoStore`). 2×2 columnas por chunk; más allá de 1536 bloques, 1.
  Medido (4 núcleos Xeon, pool de 2 hilos del preset Medio): 2-4 ms/chunk,
  390-800 chunks/s con lotes de 16 por tarea (antes 80/s, atado a los ticks);
  el radio de 160 chunks (~80 mil) se completa en ~3 min. Limitaciones: sin estructuras, árboles sueltos, ríos
  sobre el nivel del mar ni reglas de superficie reales; dimensiones con techo
  (Nether) no se aproximan. Solo singleplayer (opción en config del cliente).
  Pendiente: generar directo los niveles 5-8 muestreando a su resolución para
  radios de miles de chunks (hoy se muestrea por chunk aunque se dibuje con
  vóxeles de 256 bloques).
- **Un solo buffer lejos (`RenderLod.UN_BUFFER_DESDE` = 768 bloques):** teselas
  y celdas lejanas van en un buffer con todas las caras en vez de uno por
  dirección. Con el horizonte aproximado completo (160 chunks): 11.400 → 4.800
  llamadas de dibujo por frame, +34% vértices enviados (las caras de espaldas
  las descarta la GPU). En render por software fue algo más lento (limitado por
  vértices); **Pista B:** medir en GPU real y ajustar la distancia.
- **FSR 1 experimental (`EscaladoFsr` + `render/mixin/`):** paquete nuevo
  `render.mixin`, obligatorio para Mixin (trata todo su paquete como mixins).
  Probado en Xvfb: los mixins se aplican, la imagen al 50% se ve escalada y
  sin roturas. **Pendiente de Pista B:** FPS real en A275/3500U/1060, calidad
  visual, convivencia con Embeddium, contorno de entidades brillantes con FSR
  (se copia la profundidad entre framebuffers de distinto tamaño), y exponer
  `fsrNitidez` en la pantalla de Cloth (hoy solo en el TOML / pantalla de
  NeoForge).
- **Huecos en el agua (reportado con foto en el i5):** el mesher solo creaba
  caras contra aire, así que bajo la superficie del agua del LOD no había
  nada. Mirando a ras del agua, la visual entraba al agua translúcida de
  vanilla y llegaba por debajo a la zona del LOD: se veía el cielo. Ahora
  sólido contra agua también tiene cara (`Quad.BAJO_AGUA`) y el descarte de
  cuevas no la saca. Verificado en el océano del benchmark (antes/después).
  Pendiente: medir cuántos vértices suma el fondo marino.
- Además, un chunk se le deja a vanilla solo si ya COMPILÓ la sección de la
  superficie y las dos de abajo (`LevelRenderer.isSectionCompiled`), no solo
  si está cargado. Con Embeddium esa consulta puede dar siempre false: en ese
  caso el LOD dibuja por debajo de todo vanilla (más costo, sin huecos).
- **Umbral por distancia (`PlanCeldas.factorUmbral`):** 0,6× cerca, 1× a 1024
  bloques, hasta 1,6× lejos. Pedido: que los saltos de nivel no se noten cerca
  y que lejos baje más. Los niveles intermedios (×1,5) de la sección 2 siguen
  sin implementar (cambio de formato); el dithering entre niveles (sección 6)
  es lo siguiente que más ayuda.
- **Datos del i5 9400F + 1060 (F3 del usuario):** 82 FPS con GPU al 37%
  (límite de CPU), servidor integrado a 15,7 ms/tick y 1,9 GB/s de asignación
  con pregenerador (256) + horizonte aproximado prendidos: la inestabilidad
  viene del trabajo de fondo. Pendiente: conectar la tercera perilla del
  `PerformanceAutoTuner` (sección 23) para frenar la generación cuando cae
  el FPS. FSR bajó de 440 a 160 FPS en esa PC sin causa encontrada todavía
  (el shader solo no lo explica); en esa PC no tiene sentido usarlo.
- **0.9.0: HUD de rendimiento + log de depuración (`MonitorRendimiento`,
  `EstadisticaFrames`).** La GPU no se muestra: Minecraft solo la mide con F3
  abierto. El evento de recarga de config llega en otro hilo: solo marca, y el
  render escribe el evento (antes rompía con "Rendersystem called from wrong
  thread"). El HUD se achica solo si las líneas no entran. Versiones: ver
  `CHANGELOG.md` y la regla en CLAUDE.md.

## VulkanMod integrado (0.15.0)

- Código: `vulkanmod/` = yiyuyan/VulkanModNeoForge rama `1.21neo-0.5.5_new` (3f85319), LGPL-3.0,
  paquetes renombrados con sed. Cambios propios marcados "Minecraft LOD" (MixinPlugin, Initializer,
  VBO/AutoIndexBuffer, OptionsScreenM `method_19828` -> `lambda$init$2`, sin MixinRuntime).
  Paquete nuevo creado por pedido explícito del usuario (integrar el repo completo).
- Sin el localizador `cn.ksmcbrigade.vulkan_core` (apagaba la ventana temprana en caliente con
  Unsafe y agregaba las libs al classpath): las libs lwjgl-vulkan/vma/shaderc (win+linux) van como
  recursos del jar; `config/ConmutadorVulkan` guarda el pedido y edita `earlyWindowControl`.
- Conflicto conocido: VulkanMod suelto + integrado = paquete org.lwjgl.vulkan/vma/shaderc en dos
  módulos -> el juego no arranca (antes de que corra código nuestro). No se puede reubicar VMA
  (JNI atado al nombre del paquete). Documentado en el CHANGELOG.
- Jar-in-jar: fabric-api-base, renderer-api-v1, rendering-fluids-v1, rendering-data-attachment-v1,
  block-view-api-v2 y forgified-fabric-loader (full; lo pide el entrypoint de fluids).
- Verificado en Xvfb con lavapipe: arranca con Vulkan (dispositivo llvmpipe), LOD texturizado,
  día/noche; y apagado arranca en OpenGL sin aplicar sus mixins.
- Pendiente de Pista B: la GTX 1060 con Vulkan integrado; el menú de opciones de VulkanMod
  (Opciones > Video); macOS no tiene nativos incluidos (NO_DISPONIBLE).

## Perfilado y optimización (0.15.1)

Cómo medir: `./gradlew runClient -Pperfil=archivo.jfr` (JFR, `settings=profile`, empieza a
los 45 s y graba 150 s); `jfr view hot-methods archivo.jfr`, o `jfr print --json --events
jdk.ExecutionSample` y agrupar por hilo / primer marco del mod. Ojo: las pilas de la generación
aproximada (funciones de densidad) pasan de 64 marcos y salen truncadas, sin marcos del mod.

Perfil de partida (0.15.0, OpenGL por software, recorriendo el mundo de benchmark), 4958
muestras:
- 38% pool de generación: casi todo `finalDensity` del generador aproximado (inherente;
  se bajó el refinado de altura de hasta 7 a 3 evaluaciones con búsqueda binaria).
- 31% `Worker-Main`: generación de chunks vanilla (no es del mod).
- 16% `LOD-EscrituraRegiones`: ~100% en `RegionHeader.claves()` (`Set.copyOf` de todo el
  índice, dos veces por escritura) -> `RegionHeader.copia()` y `bytesVivos()`.
- `BoundedRegionCache.obtener`: `LinkedHashMap` degradado a árboles por `Long.hashCode` de
  `claveCache` (bits de región sobre los del nodo) -> claves mezcladas con fmix64.
- Memoria: 22,5 GB asignados en 150 s. Del mod: `indiceDe` releía índices ausentes/viejos en
  cada consulta (1,6 GB) -> marca `SIN_INDICE`; `CompresionNodos` (1,7 GB) -> buffer por hilo;
  `GreedyMesher.mallarEje` (1,5 GB) -> matrices por eje; `Tinte/Material.values()` (600 MB);
  `RunLengthCodec.leerRuns` -> `leerYDecodificar`; `carpetaDe` -> ruta por dimensión;
  `LectorSeccionMinecraft.material` -> cache por estado (se vacía con `TagsUpdatedEvent`).

Siguiente candidato (sin tocar): `CacheRelieve.preparar` lee hasta 32 regiones de disco por
replanificación EN EL HILO DE RENDER (hasta ~14 ms cada 3 s = tirón en equipos débiles).
Pasarlo al hilo de mallas o bajar `LECTURAS_POR_PLAN`.

## Auto-ajuste CPU/GPU (0.16.0)

- `core/BalanceadorCpuGpu` (lógica pura, tests) + `render/BalanceCpuGpu` (medición). Antes de esto
  `PerformanceAutoTuner`/`ControlDeRendimiento` existían con tests pero NADA los usaba: la opción
  `autoAjuste` no hacía nada. Quedan sin uso (el balanceador los reemplaza); no se borraron.
- GPU: `TimerQuery` de Minecraft entre `RenderFrameEvent.Pre` y `Post` (consultas sin esperar, se
  leen cuando están listas). Con F3 la consulta es de Minecraft: se usa `getGpuUtilization()`.
  Con VulkanMod no hay medición (límite "?": orden genérico).
- Diagnóstico: GPU ocupada >= 85% del cuadro = límite GPU. Un cambio de lado se confirma en el
  ciclo siguiente (en el borde alternaba y el agrupado iba y venía 256 <-> 384).
- Tope de detalle: 2x el preset y nunca más de 6 px. Medido en Xvfb: a 10 px el plan usaba teselas
  grandes también cerca, sin datos, y el LOD desaparecía (6 piezas de 168) mientras el FPS "subía".
  Pendiente de investigar aparte: por qué esas teselas cercanas no tienen datos.
- En Xvfb (render por software, imposible llegar a 40 FPS) llega al piso (5 px, radio 40) con el
  LOD visible (113 piezas). Una corrida anterior quedó en 19 piezas en el mismo estado y no se
  repitió: observar en la PC real si el LOD queda muy recortado en el piso.
- Pista B: ver en la 1060 y en la 3500U/A275 qué diagnostica y si los tirones bajan.

## Pantalla de opciones estilo Sodium (0.17.0)

- `config/PantallaLod` (dibujo, pestañas, desplazamiento, teclas) + `config/OpcionesLod` (modelo
  con valor pendiente: Interruptor, Ciclo, Deslizador en enteros con escala para decimales,
  Accion inmediata). Reemplaza a `PantallaCloth`; Cloth Config fuera del build y del mods.toml.
- Deslizadores: el pendiente arranca con el valor guardado tal cual (redondearlo al paso marcaba
  cambios sin tocar nada, ej. 500 MB con paso 32).
- Nombres que no entran al lado del control se cortan con "…" (el completo va en el panel).
- Verificado en Xvfb (960x540, escala auto): las 4 pestañas, panel de descripción, opciones
  deshabilitadas sin preset Personalizado y botón "LOD" en Opciones > Video. Con Sodium/Embeddium
  o VulkanMod la pantalla de video es otra y el botón no aparece (queda la lista de mods).


## Opciones de video dentro de la pantalla del LOD (0.18.0)

- `PantallaConfig` reemplaza `VideoSettingsScreen` en `ScreenEvent.Opening` por `PantallaLod`
  (pestañas Video, Gráficos, LOD, Calidad LOD, Generación, Experimental). No lo hace con
  sodium/embeddium/rubidium/vulkanmod cargados ni con el Vulkan integrado activo (tienen su
  propia pantalla); ahí queda el botón "LOD" de antes.
- `config/OpcionesVideo` arma cada fila desde el `OptionInstance` de vanilla (AT para
  `SliderableValueSet`, `CycleableValueSet`, `IntRangeBase`): casilla si es Boolean, deslizador
  entero si es `IntRangeBase`, deslizador 0..100 con redondeo al valor admitido si es otro
  deslizable (FPS, brillo, distancia de entidades), ciclo con el `valueSetter` de vanilla si no.
  El texto del valor es el de vanilla sin el "Nombre: ".
- Al aplicar: `OptionInstance.set` (callbacks de vanilla), `options.save()`, recarga de texturas
  si cambian los mipmaps, `changeFullscreenVideoMode` si cambia la resolución y `resizeDisplay`
  si cambia la escala de GUI (igual que `VideoSettingsScreen.removed`).
- "Opciones de video originales" abre la vanilla una vez (flag) y vuelve a la nuestra.
- Pendiente: el aviso de Fabuloso en GPUs de la lista negra de Mojang (vanilla muestra un
  diálogo; acá la opción no cambia y queda en amarillo). Botón de shaders de Iris sin probar
  (Iris no está en el entorno de prueba).
- Verificado en Xvfb: Opciones > Video abre la pantalla nueva; distancia de render, actualización
  de chunks, mipmaps y escala de GUI se aplican y quedan en options.txt; ida y vuelta a la
  pantalla vanilla.

## Vóxeles como terreno, nubes lejanas y curvatura (0.19.0)

- Franja de pasto por vóxel: 21 texturas de costado detectadas con franja en vanilla. Verificado
  que compila y carga; el efecto visual en vóxeles grandes queda para Pista B (en Xvfb el mundo de
  prueba casi no tiene LOD lejano con costados a la vista).
- Nubes lejanas: verificadas en Xvfb (siguen el dibujo de vanilla más allá de su borde). Pendiente
  de Pista B: brillo relativo a las de vanilla (hoy 0.8 del color de nubes) y el borde del hueco
  cuando el plano lejano de vanilla corta antes que su cuadrado (distancia de render < 8).
- Curvatura: verificada con R = 1 km (terreno y nubes se curvan). Con R real el efecto recién se
  nota a varios km. Por celda (VulkanMod, shaderpacks, colores planos) puede dejar escalones entre
  celdas con radios chicos. El recorte por relieve (OclusionRelieve) no conoce la curvatura: es
  conservador (lo que baja sigue tapado).
- Horizonte real: verificado en el log (radio=66 = 264 del horizonte × el piso del auto-ajuste).
- Pendiente: nubes con VulkanMod (necesitaría variante del shader).

## Superficie a la altura real, niveles hasta 10 y horizonte por región (0.20.0)

- `SuperVoxel.alturaLocal` pasa a ser el RELLENO (0-255 del alto del vóxel lleno desde abajo,
  promedio de sus columnas). Nivel 0 visible = 255. `HierarchicalReducer` lo calcula por columna
  (hijo de arriba = 1 + su relleno) y deja visible un vóxel con mayoría O con relleno medio ≥ 1/4.
  `VERSION_ALGORITMO` 11: todo el LOD guardado se regenera.
- `GreedyMesher.mallar(..., bloquesPorVoxel)`: cara +Y de un vóxel con aire/agua encima a la altura
  del relleno; costados hasta ahí; contra un vecino de superficie más bajo, el tramo de en medio.
  Recortes en bloques enteros (el vértice compacto usa shorts). Solo se fusionan caras con el mismo
  recorte y un costado recortado no se estira a lo alto (más quads en terreno irregular: medir en
  Pista B cuánto sube el conteo de vértices).
- `GeometriaLod`: el costado recortado con franja (pasto) se parte en CPU: fila de arriba con la
  textura del costado y el resto con la de abajo (`Cara.spriteAbajo`), nivel 0 en el vértice para
  que el shader no busque el borde del vóxel.
- Niveles grandes hasta 10 (vóxeles de 1024, teselas de 16 384 bloques: el límite de los shorts).
  `RADIO_MAX` 8192 chunks.
- `pixelesMaximos` (4 por defecto): tope del error de pantalla en `PlanCeldas.nivelPara`, que el
  auto-ajuste no puede pasar (si falta rendimiento, recorta radio).
- Horizonte por región (`TerrenoAproximado.nivelDeRegion`): chunk por chunk hasta 512 chunks; más
  lejos un nodo entero por tarea, una columna por vóxel: nivel 5 hasta 1024 chunks, 6 hasta 4096,
  7 más allá (decidido por el centro del nodo más grande que ya queda lejos: partición en árbol).
  Claves: nivel 13 con el nivel real en la Y; marca en nivel 14 con Y 0x800|nivel.
  `NivelesGrandes.desdeSecciones` usa `seccionAproximada` en chunks sin datos por sección, así los
  niveles 5-10 existen en todo el radio. El planificador no baja de nivel 5 más allá de 512 chunks.
- Verificado en Xvfb: sin errores, el log muestra nodos por región generándose (lento en llvmpipe:
  ~1 nodo cada 3 s con la CPU compartida con el render por software).
- Pendiente de Pista B: juzgar el relieve lejano con la superficie real (y si quedan placas finas
  flotando donde un vóxel con poco relleno no tiene nada abajo), el costo en vértices, y cuánto
  tarda en llenarse el horizonte de 8192 chunks en la PC real.
- Transición gradual entre niveles (fundido con tramado al cambiar de nivel, sección 6): no
  implementada todavía; es el siguiente paso para que el cambio de nivel no se note.

## Horizonte que no aparecía, bosques nevados y hielo (0.20.1)

- Diagnóstico con jstack en Xvfb: los 2 hilos del pool (preset Medio) estaban en
  `GeneradorAproximado.generarGrande` → `altura` → `finalDensity` más de 100 s por nodo (la
  densidad final evalúa cuevas); y después, con eso resuelto, ocupados por el modo chunk por chunk
  cercano: los nodos por región y `lanzarLoteGrande` esperaban en la cola del ForkJoinPool.
- Arreglos: `altura(..., rapida)` con `initialDensityWithoutJaggedness > 0.390625` (lo que usa
  vanilla para la superficie preliminar) en el horizonte por región y en el tramo de una columna
  por chunk; el modo chunk por chunk usa como mucho `hilos - 1` tareas; nodos por región de a uno;
  `GenerationTaskScheduler.intentarEnviarReservado` (3 lugares extra) para los nodos por región y
  los lotes de niveles grandes; con MSPT alto sigue de a una tarea en vez de pararse.
- Calidad en vivo: `RenderLod` compara `ConfigLod.calidadCliente()` cada cuadro (fuera de la
  calibración) y `GeneradorLocal.aproximar` lee el radio de la config del cliente.
- Reductor: visible también con ≥ 2 de 4 columnas cubiertas (copas ralas).
- Aproximado: bosques (taiga, grove, etc.) antes que nieve, con `nevado`; cumbres nevadas con
  bloque de nieve; agua que se congela = vóxel de agua con estado de hielo.
- `VERSION_ALGORITMO` 12.

## Escalado que gana FPS (0.21.0)

- Medición en Xvfb (llvmpipe, 960×540): FSR1 al 50% daba 5,0 fps igual que apagado (el costo lo
  ponen la CPU y los vértices del LOD, no los píxeles). Con el plan del LOD medido en píxeles del
  framebuffer chico (`Escalado.alturaDelMundo`, y el tope `pixelesMaximos` convertido a pantalla):
  6,2 fps (+24%).
- `InteropVulkan`: semáforos compartidos (`GL_EXT_semaphore[_fd|_win32]` +
  `VK_KHR_external_semaphore[_fd|_win32]`): GL señala entradas (con layouts GENERAL de las
  texturas compartidas) y hace glFlush, Vulkan espera/trabaja/señala salida, GL espera antes de
  leer; la fence se espera al empezar el lote siguiente. llvmpipe no tiene GL_EXT_semaphore_fd:
  en Xvfb solo se probó el respaldo con glFinish. **Pista B:** probar XeSS en la 1060 (el log dice
  "sincronización por semáforos en la GPU"); si hay imagen rota o parpadeo,
  `-Dminecraftlodmod.sinSemaforos=true` vuelve al glFinish.
- `PruebaEscalado` (opción `escaladoSoloSiGana`): 3 s con, 3 s sin, cada 2 min; con escalado solo
  si es ≥3% más rápido. Verificado en Xvfb (157 ms con vs 192 ms sin → queda prendido).
- XeSS en GPUs que no son Intel usa el camino DP4a, que en Pascal (GTX 1060) es caro: aun sin la
  espera de CPU puede no ganar; la medición automática lo va a apagar si es así.
- `-Dminecraftlodmod.pruebaVulkan=true` ahora también ofrece XESS fuera de Windows (con el
  escalador de prueba EscaladorBlit).
