# Registro de versiones

Numeración 0.MENOR.PARCHE: MENOR sube con funciones nuevas, PARCHE con solo
arreglos. El número está en el nombre del jar, en el HUD de rendimiento y en
la primera línea del log de depuración. Las versiones 0.2.0 a 0.8.0 se
numeraron después de entregadas (esos jars decían 0.1.0); el commit indica
cuál es cuál.

## 0.26.28 — Plantas y camas/cofres con su color en el LOD
Solo cambia la zona del LOD; el terreno cercano (vanilla) queda igual.
- **Pasto, flores, caña y cultivos tiñen el suelo** en el LOD según cuánto de
  su textura tapa (una flor tiñe poco, un pasto alto más; hasta 75%). Antes el
  LOD los omitía y lejos un campo de flores se veía como pasto pelado. Costo:
  ninguno (no agrega geometría). Carteles, rieles y estandartes no tiñen.
- **Camas y cofres con su color real** (colchón de cabecera y pies, tapa y
  frente del cofre, de su textura de entidad) en vez de color madera.
- Se ve en el terreno extraído desde esta versión; lo ya guardado en el cache
  del LOD se actualiza cuando se vuelve a extraer (al cambiar bloques), sin
  regenerar todo.

## 0.26.27 — Benchmark que mide de verdad y configura según el equipo
- **Benchmark nuevo.** Además de los 4 lugares de antes, mide la "vista alta"
  (el horizonte entero, lo más pesado del LOD) y un vuelo en línea recta (carga
  de chunks y tirones en movimiento). En cada lugar espera a que el LOD termine
  de armarse (antes medía a los 10 s, a veces con el LOD a medias) y mide FPS
  promedio, 1% bajo, peor cuadro, tiempo de GPU y CPU, servidor, vértices,
  llamadas de dibujo y cuánto tardó en cargar el LOD.
- **Botón "Medir rendimiento"** (menú principal): mide tu configuración tal
  cual, sin cambiarla.
- **Informe** en `.minecraft/minecraftlodmod/benchmark/` con el hardware, los
  ajustes y una tabla por lugar: para comparar máquinas o versiones.
- **"Calibrar" configura según el equipo:** un escalón ahora tiene que alcanzar
  el FPS objetivo también sin tirones (1% bajo). Si ni el más liviano alcanza y
  la placa de video es el límite (como en la A275), prende FSR 1; si el más
  alto sobra y la GPU tiene margen (como la GTX 1060), prende la oclusión en
  costados. El auto-ajuste queda quieto mientras mide.
- **FSR que no se activaba en la A275:** la prueba "solo si gana" lo apagaba
  cuando con y sin escalado daba casi lo mismo (límite del procesador, tope de
  FPS o vsync). Ahora se apaga solo si hace el juego más de un 3% más lento.
  También se apagaba con Iris instalado aunque no hubiera shaderpack.
- **Arreglo:** en mundos creados con versiones anteriores a la 0.26.14, algunas
  celdas cercanas del LOD no se armaban (huecos): una marca vieja compartía
  clave con el terreno aproximado fino y se leía como si fuera terreno.

## 0.26.26 — Agua del LOD translúcida
- El agua del LOD es translúcida como la de vanilla (opción "Agua
  translúcida", prendida): se ven la orilla, los bajíos y la roca detrás de
  las cascadas; el agua honda queda oscura por la luz del fondo. Se dibuja al
  final, sobre lo opaco, con la opacidad de la textura del agua de vanilla.
  Medido: misma geometría, GPU +3% (dentro del ruido de la medición).
- Con agua translúcida (y en el contrato Voxy de shaderpacks) las cascadas y
  ríos en pendiente ya no desaparecen: se descartan solo las paredes de agua
  del borde de cada celda, que se verían como una grilla.
- Revisados los bordes entre niveles de detalle: sin escalones ni huecos (el
  relleno por vóxel mantiene la altura real en todos los niveles); sin cambios.

## 0.26.25 — Más calidad cuando sobra rendimiento
- **Detalle extra automático:** con el auto-ajuste prendido, si el juego va
  con 20% o más de margen sobre el FPS objetivo y todo lo demás ya está en
  el preset, el LOD suma detalle por encima del preset (hasta el doble, sin
  bajar nunca de vóxeles de 1 píxel). Es lo primero que se saca si el cuadro
  deja de alcanzar, y después espera 30 s antes de volver a sumarlo, para
  que no suba y baje. El HUD muestra "detalle +N%" mientras está activo.
- **Oclusión en costados** (opción nueva, apagada): la oclusión ambiental
  también en los costados y caras de abajo, como vanilla. Más relieve en
  acantilados, laderas de roca y bosques. Medido: +29% de geometría y +18%
  de GPU, por eso queda apagada; para GPUs con margen (la 1060).

## 0.26.24 — Generación de chunks un poco más rápida
- El acuífero de vanilla (agua y lava subterráneas) buscaba, para cada bloque
  de aire o cueva, los 12 centros de acuífero vecinos desde cero. Ahora
  recuerda los de la última celda, que comparten casi todos los bloques
  seguidos. Mismo terreno: comparado contra vanilla en 40 millones de
  bloques, sin ninguna diferencia. Medido: 328 → 261 ns por bloque (−20%),
  ~4% menos CPU al generar chunks nuevos. No se aplica si tenés C2ME.

## 0.26.23 — Optimizaciones sin cambio visual
- El shader del terreno LOD ya no tiene `discard`: el fundido entre niveles
  pasó a un programa aparte que solo usan las mallas que entran o salen. Un
  shader con `discard` apaga en la GPU la prueba de profundidad temprana, así
  que antes se pintaba cada píxel del LOD aunque quedara tapado. Los datos
  fijos por cara ya no se interpolan por píxel. La imagen es la misma.
- Extracción del LOD al doble de velocidad por bloque (287 → 145 ns): un
  solo objeto por vóxel en vez de cinco copias, la mezcla de biomas solo
  para los bloques que se tiñen y solo su canal, y la luz de los vecinos
  en una pasada. Comprobado contra el código anterior: 54 millones de
  vóxeles idénticos.

## 0.26.22 — Menos carga de GPU en los presets altos
- Nueva opción **Píxeles mínimos por vóxel** (Calidad, 2 px por defecto): por
  más detalle que pida el preset, el LOD ya no dibuja vóxeles más chicos que
  eso en pantalla. Los vóxeles de 1 bloque son ~85% de los vértices del LOD,
  y en Alto, Ultra y Horizonte se estiraban hasta 1-1,7 km con menos de un
  píxel cada uno: no se distinguían y eran casi todo el trabajo de la GPU.
- Medido con el umbral de Ultra: vértices dibujados 14,8 M → 9,2 M (−38%),
  tiempo de GPU del cuadro −31%, sin diferencia visible en la imagen. A
  1080p, el área con vóxeles de 1 bloque baja a la mitad en Alto, a un tercio
  en Ultra y a un quinto en Horizonte. En Medio no cambia nada.
- El log de estadísticas del LOD muestra el tiempo de GPU del cuadro.

## 0.26.21 — Generación de terreno más liviana cerca de estructuras
- Al generar chunks, vanilla ajusta el terreno alrededor de aldeas, ciudades
  antiguas y otras estructuras evaluando cada punto de toda la altura de la
  columna contra cada pieza cercana, aunque cada pieza solo afecta a menos de
  12 bloques. Ahora cada chunk arma una caja con el alcance de sus piezas y
  fuera de ella el ajuste es 0 sin recorrer nada. El terreno sale idéntico
  (comprobado contra vanilla en más de 10 000 puntos al azar).
- Medido en un vuelo por terreno nuevo: esa parte pasó del 6,4 % al 0,5 % del
  CPU de la generación.
- No se aplica si tenés C2ME.

## 0.26.20 — Extracción del LOD al doble de velocidad
- Leer una sección para el LOD cuesta la mitad: antes cada bloque buscaba su
  estado en varios mapas (id del bloque, material, forma, el bloque de
  arriba); ahora la información se calcula una vez por estado de la paleta
  de la sección y cada bloque va directo por su índice. Medido: 228-235 µs →
  105-116 µs por sección (sección mezclada de 10 estados).
- En terreno nuevo, el LOD de lo que cargás compite menos con la generación
  de los chunks: ese CPU queda para el mundo.

## 0.26.19 — Generación en paralelo prendida por defecto
- **Generación de chunks en paralelo** (opción del servidor
  `generacionParalela`) pasa a estar **prendida por defecto**: superficie,
  cuevas y estructuras de los chunks nuevos se reparten entre los núcleos en
  vez de ir de a uno.
- Medido en singleplayer (distancia 12, teletransporte a terreno nunca
  generado, 4 núcleos): la vista se completa en 35-37 s con la opción y en
  47-48 s sin ella (3 pruebas cada una) → unos 25 % más rápido.
- Si un mod de generación de terreno falla o tira errores raros, apagala en
  `config/minecraftlodmod-server.toml`. El log avisa una vez cuando está activa.
- Ya tenés un `minecraftlodmod-server.toml` con `generacionParalela = false`
  (el valor viejo): cambialo a mano a `true` para usarla.

## 0.26.18 — Primero lo que tenés a la vista
- **Arreglo importante de carga en terreno nuevo:** el anillo real y los
  chunks en RAM (0.26.15-0.26.17) pedían chunks alrededor de la vista con la
  misma prioridad que los que tenés delante, y en terreno sin explorar se
  comían toda la generación. Ahora solo piden chunks nuevos cuando ya está
  cargado todo lo que está dentro de tu distancia de render.
- Medido (distancia 12, teletransporte a terreno nunca generado): antes la
  vista seguía con 607 de 625 chunks faltantes después de 80 s; ahora se
  completa en 37-39 s (3 pruebas).

## 0.26.17 — Chunks en RAM según la RAM para LOD
- **Chunks en RAM** ahora es un interruptor (prendido) y usa la misma RAM
  que le das al LOD ("RAM para LOD" del preset o personalizado), con tope en
  un cuarto de la memoria de Java. La mitad va al colchón de adelante, el
  más ancho que entre (de 2 a 32 chunks más allá de tu distancia de render),
  y el resto a retener lo que dejás atrás. Más RAM para el LOD = más chunks
  listos alrededor. Ejemplo: Medio (500 MB) con distancia 12 → colchón de
  16 chunks y hasta ~5300 chunks retenidos.
- Ojo: el juego usa en total hasta el doble de "RAM para LOD" (la del LOD
  más la de los chunks). Dale a Minecraft memoria de sobra (-Xmx) o bajá
  ese valor.
- Se quitan las opciones de ancho y de MB sueltas de la 0.26.16.

## 0.26.16 — Chunks en RAM para que carguen antes
- **Chunks en RAM** (Generación, 8 chunks por defecto, 0 = apagado): los
  chunks hasta esa distancia más allá de tu distancia de render se mantienen
  cargados en memoria, sin animarlos ni mandarlos a la pantalla. Al caminar,
  lo que entra a la vista ya está listo; vanilla lo leía del disco y lo
  armaba de a uno en el hilo del servidor recién en ese momento. Se piden de
  a poco, primero lo que mirás, y nunca con el servidor atrasado.
- **RAM para chunks retenidos** (384 MB por defecto): lo que dejás atrás
  queda cargado hasta ese límite y se suelta primero lo que hace más tiempo
  que no ves, así volver por el mismo camino es inmediato.
- Medido en el equipo de pruebas: el colchón de 8 chunks con distancia 12
  ocupa unos 100 MB. Ahí la carga ya era rápida (disco y procesador
  rápidos) y no se notó diferencia; donde debería notarse es en la Lenovo.
  Si en la Lenovo te falta RAM, bajá las dos opciones.

## 0.26.15 — Sin huecos en el LOD y terreno real justo después de vanilla
- **Huecos en acantilados y laderas (el "anillo con huecos" lejano):** en el
  terreno aproximado, la parte de abajo de cada columna se guardaba "sin
  luz", y el LOD descarta las caras sin luz (son cuevas). La pared de un
  acantilado o una ladera empinada desaparecía y se veía el cielo a través.
  Ahora los costados del terreno aproximado se dibujan con luz plena, y las
  paredes enterradas entre chunks se ocultan comparando con el chunk de al
  lado. Lo mismo en las piezas grandes lejanas.
- **Ranuras donde cambia el nivel de detalle:** el borde de una celda se
  ocultaba contra el chunk vecino aunque ese vecino se dibujara con vóxeles
  de otro tamaño. Ahora solo se oculta si la celda de al lado tiene el mismo
  nivel. Esas ranuras se veían de frente mirando a lo largo de los ejes:
  las 4 direcciones a 90°.
- **Borde con el terreno normal:** las paredes del LOD que dan a un chunk de
  vanilla más bajo (un acantilado frente a la playa) ya no faltan.
- **Anillo real** (Generación, 16 chunks por defecto, 0 = apagado): genera
  chunks de verdad hasta esa distancia más allá de tu distancia de render y
  te sigue al moverte. Lo primero que se ve del LOD después del terreno
  normal tiene árboles y el relieve real, como en Voxy; más lejos sigue el
  horizonte aproximado. Los chunks quedan guardados en el mundo (16 chunks
  son unos pocos MB). Solo singleplayer.
- **Prioridad y revisión del borde:** cada segundo se revisan los 32 chunks
  después de vanilla; lo que falta, o está más grueso de lo que pide su
  distancia, pasa adelante en la cola. Las celdas cercanas incompletas se
  rearman cada 2 s (antes 10).

## 0.26.14 — Más rápido fuera del LOD: modo híbrido, entidades tapadas, partículas, generación en paralelo
- **Arreglo importante:** faltaban pedazos de terreno e islas en el LOD. La
  marca de "chunk ya extraído" usaba la misma clave que un nodo del terreno
  aproximado fino, así que algunos chunks se daban por hechos sin estarlo.
  La marca nueva usa otra clave; la vieja se sigue reconociendo.
- **Modo híbrido** (General, «Modo híbrido (vanilla corta)», apagado por
  defecto): con el LOD activo, la distancia de vanilla se acota según el
  preset (Mínimo 5, Bajo 6, Medio 8, Alto 10, Ultra y Horizonte 12 chunks) y
  el LOD dibuja el resto. Nunca sube la distancia que elegiste. Medido: de
  4-5 a 9-10 FPS pidiendo 16 chunks.
- **Ocultar entidades tapadas** (prendido): no se dibujan los animales, mobs,
  cofres, carteles, cabezas, etc. que el terreno tapa. Un hilo aparte lo
  prueba con rayos desde la cámara. Medido: de 105 a 51 entidades dibujadas
  con la misma imagen. Si tenés EntityCulling instalado, manda ese.
- **Distancia de entidades, distancia de partículas y máximo de
  partículas** (General): para recortar entidades lejanas, partículas
  lejanas (vanilla: 32 bloques) y lluvias de partículas.
- **Velocidad de carga de chunks** (General, 7 ms = vanilla): cuánto del
  tick dedica el juego a recibir chunks. En las pruebas no cambió nada
  (el límite era el servidor); probá subirlo y contame.
- **Generación en paralelo** (opción experimental del servidor
  `generacionParalela`, apagada): superficie, cuevas y features de los
  chunks nuevos se reparten entre los núcleos en vez de ir de a uno. En el
  equipo de pruebas (4 núcleos) no ganó tiempo: probalo en un servidor con
  más núcleos y apagalo si algún mod de generación falla.
- **Menos RAM por estado de bloque** (siempre, salvo con FerriteCore): los
  ~26 mil estados de bloque comparten una tabla de vecinos por bloque
  (~15 MB menos, cliente y servidor).
- Arreglado un caso raro en el que el cache del LOD podía quedarse con la
  versión vieja de un nodo recién guardado.

## 0.26.13 — GPU y VRAM medidas como el Administrador de tareas (Windows)
- En Windows, el uso de GPU y la VRAM del HUD salen de los contadores de
  rendimiento del sistema (los mismos del Administrador de tareas): andan
  con cualquier placa (NVIDIA, AMD, Intel) y también con Vulkan prendido.
  GPU %: el motor más ocupado de la placa; VRAM: memoria dedicada usada
  sobre la total de la placa.
- Se leen en un hilo aparte una vez por segundo (no frenan el juego). Si en
  tu sistema no están, el HUD usa la medición de antes.
- Sin probar en Windows todavía (se desarrolló en Linux): fijate que los
  valores se parezcan a los del Administrador de tareas.

## 0.26.12 — HUD nuevo: FPS | MIN | AVG, CPU, RAM, GPU y VRAM con colores
- El HUD de rendimiento pasa arriba a la izquierda, con texto con sombra y
  sin fondo: `FPS | MIN | AVG | CPU % | RAM usada/máx GB | GPU % | VRAM
  usada/total GB`. MIN y AVG cuentan desde que entraste al mundo (sin los
  primeros 5 segundos de carga).
- Colores: los porcentajes y la memoria van de verde a amarillo y a rojo al
  acercarse al 100%; los FPS en rojo por debajo de 30, amarillo hasta 60.
- GPU %: parte del cuadro que la GPU está ocupada (con Vulkan no hay
  medición: «-»). VRAM: con NVIDIA usada/total; con AMD en OpenGL el driver
  solo da la libre («X GB libre»); con Vulkan, lo que reservó el juego sobre
  el total de la GPU; Intel y otros, «-».
- Nueva opción **«Línea del LOD en el HUD»** (pestaña Depuración): apagada,
  queda solo el primer renglón.
- El mesher junta caras de distinto estado de bloque con la misma textura
  (hojas, pasto...): ~1% menos vértices, sin cambios en la imagen.

## 0.26.11 — El LOD fuera de la vista ya no se manda a la GPU
- **Rendimiento (GPU):** cada celda del LOD se prueba contra el campo de
  visión antes de dibujarla; las de atrás y los costados no se mandan. Antes
  la GPU procesaba todos sus vértices para después recortarlos. Medido en la
  misma vista (render por software, 1280×720): 2,5 M → 0,86 M vértices y 279
  → 57 llamadas por cuadro, dibujo del LOD 133 → 55 ms, FPS 6 → 11; volando a
  y=300 mirando abajo, 3,3 M → 1,1 M vértices. Sin cambios visibles.
- Funciona igual con OpenGL, Vulkan, shaderpacks y el contrato Voxy (no
  depende de cómo cada uno arma la profundidad).

## 0.26.10 — Sin huecos cerca al volar alto
- **Arreglo:** volando alto (o con la sincronización vertical), el suelo
  cercano desaparecía y quedaban huecos con el cielo, paredes de piedra y
  copas de árboles flotando. Vanilla (y Sodium) no dibujan las secciones que
  están a más de *distancia de render × 16* bloques en vertical de la
  cámara, pero el LOD daba por hecho que vanilla dibujaba la columna entera
  y no la dibujaba él. Ahora, cuando la superficie de una columna queda
  fuera de esa franja, el LOD la dibuja (a nivel del suelo nada cambia: no
  se rearman celdas al caminar por colinas).
- El error `jdk.attach module not found` del log con Vulkan no es una caída:
  es el aviso de que el Java de Modrinth no trae ese módulo, y el juego sigue.

## 0.26.9 — Contrato Voxy: agua translúcida y materiales del pack
- Con la opción "Contrato Voxy" y un shaderpack que la soporta
  (Complementary r5.9), el agua lejana ya no es un plano azul opaco: se
  dibuja con el shader de agua del pack (`voxy_translucent`), con su
  transparencia y sus reflejos, y se ve el fondo marino.
- El pack sabe qué bloque es cada vóxel lejano (sus ids de
  `block.properties`): hojas, agua, bloques que brillan y demás materiales
  reciben su tratamiento propio en vez de quedar como bloque genérico.
- La luz del cielo del LOD llega al pack (antes iba siempre al máximo): lo
  que está bajo techo o en sombra se ve más oscuro, como el terreno cercano.
- Probado en render por software con Complementary r5.9.3. Sin la opción,
  el LOD se dibuja igual que antes.

## 0.26.8 — Contrato de Voxy con shaderpacks: el LOD con los shaders del pack
- Opción experimental **"Contrato Voxy"** (pestaña Experimental, apagada):
  con Iris y un shaderpack que soporta Voxy (por ejemplo Complementary
  Reimagined r5.9), el LOD se dibuja con el código del propio pack
  (`voxy_opaque`) y el pack lo ilumina, le pone niebla y lo compone como al
  resto del terreno. Sin la "niebla de borde" que tapaba el LOD más allá de
  la distancia de render vanilla.
- Iris 1.8 admite 16 buffers de color y Complementary en modo Voxy usa más:
  con la opción prendida se amplían; si aun así el pack no carga en ese
  modo, se recarga solo en el modo normal (el LOD sigue como antes, no se
  quedan los shaders apagados).
- Probado en render por software (Xvfb) con Complementary r5.9.3: carga,
  compila y dibuja sin errores. Pendiente: agua translúcida (hoy el agua
  lejana sale opaca), materiales por bloque (hojas, emisivos), sombras del
  LOD, y verlo en una GPU real.

## 0.26.7 — Vegetación de cuevas en lo que se completa al bajar
- Con `cubico.generacionVertical`, la banda que se completa cuando un
  jugador se acerca ahora también recibe sus features: musgo, arcilla,
  azaleas, lianas y hojas de las cuevas frondosas, liquen brillante,
  dripstone, sculk, mazmorras, manantiales, y en islas (si se recortó
  arriba) árboles y pasto. Mismo orden y misma semilla que la generación de
  vanilla, así cada feature cae donde habría caído; corre sobre el mundo
  vivo con un filtro que descarta lo que caiga fuera de la banda (nada se
  duplica arriba). Menas, lagos, geodas y estructuras no se repiten (ya
  estaban sobre el relleno).
- Medido contra la misma zona generada completa (y -64..0, 49 columnas):
  musgo 2989 vs 2866, lianas 291 vs 291, azaleas 82 vs 79, arcilla 13418 vs
  13869; bloques iguales 99,20% → 99,37%. Toda la lava con luz 15. Costo:
  3,6 ms por columna en el hilo del servidor (el completado se reparte con
  un tope de 4 ms por tick).

## 0.26.6 — La caché RAM del LOD respeta el presupuesto de verdad
- **Arreglo importante de memoria:** la caché RAM del LOD contaba solo los
  datos de cada nodo (~43 B en promedio), pero cada entrada arrastraba
  ~100 B más (entrada del mapa, clave `Long`, tabla, cabecera del array).
  Medido en singleplayer: decía 145 MB y ocupaba ~450 MB, unas 3 veces el
  presupuesto elegido (en la A275 con el preset Mínimo, ~300 MB en vez de
  100). Ahora usa un mapa de claves `long` sin objetos por entrada y el
  presupuesto cuenta datos + 48 B por entrada: lo que muestra el HUD es lo
  que ocupa.
- Medido (singleplayer en Xvfb, mismo recorrido): heap del juego 1133 →
  856 MB (-24%).
- Con el mismo número de MB entran menos nodos que antes (antes se pasaba
  del límite); para la misma RAM real entran ~1,5 veces más.
- Arreglo del repo: el `.gitignore` ignoraba `vulkanmod/render/chunk/build/`
  (la regla `build/` atrapaba cualquier paquete con ese nombre) y el código
  no compilaba desde un clon limpio de GitHub.

## 0.26.5 — Contenedores de sección más livianos (cliente y servidor)
- Cada contenedor de paleta (bloques y biomas de cada sección) traía un
  detector de acceso desde varios hilos con su propio `Semaphore` y
  `ReentrantLock` (~96 B con sus objetos internos) y su propia
  `Configuration` (24 B), aunque solo existen unas pocas distintas. El
  detector ahora guarda el "dueño" en un campo protegido por el monitor del
  propio objeto (mismo error que vanilla si dos hilos se cruzan) y las
  configuraciones se comparten. Siempre activo, en cliente y servidor y en
  cualquier mundo; no se aplica si está FerriteCore, y se apaga con
  `-Dminecraftlodmod.sinRecortesPaleta=true`.
- Medido en el mundo de prueba de 2048 de alto (servidor dedicado, 1024
  chunks): heap 712 → 621 MB (-13%), sin cambio en el tiempo de generación;
  bloques y luz guardados iguales. En un mundo normal el ahorro es menor
  (hay ~5 veces menos secciones por columna).

## 0.26.4 — Luz de las secciones lejanas comprimida
- Con `cubico.comprimirSeccionesLejanas`, además de los bloques, la luz
  (cielo y bloque) de las secciones lejanas en vertical se guarda comprimida
  y se descomprime sola al leerla. Guardar el chunk y mandar la luz usan una
  copia temporal (la capa sigue comprimida); solo el motor de luz al
  escribir la descomprime de verdad. La luz de un chunk recién cargado
  (menos de 10 s) no se toca.
- Medido en el mundo de prueba de 2048 de alto: solo ~8500 capas de luz
  tenían datos (las de luz pareja ya no ocupan nada en vanilla), 17 MB que
  quedan en ~1 MB; los `byte[]` del heap bajaron de 85 a 69 MB. La luz
  guardada coincide con la de una corrida sin compresión (99,46%, lo mismo
  que entre dos corridas normales) y los bloques al 100%.
- Arreglo encontrado en la prueba: una capa comprimida contaba como "vacía"
  y no se guardaba (ni se habría mandado a los clientes).

## 0.26.3 — Menos RAM del servidor con lo lejano en vertical (etapa 2 de cubic chunks)
- **Nueva opción `cubico.comprimirSeccionesLejanas`** (servidor, apagada):
  los bloques de las secciones a más de `distanciaCompresion` (8) secciones
  en vertical de todos los jugadores se guardan comprimidos en RAM y se
  descomprimen solos la primera vez que algo los lee. Guardar el mundo o
  mandar el chunk no las deja descomprimidas. No toca secciones con ticks
  aleatorios. Barrido en el hilo del servidor con tope de 1 ms por tick.
- Medido en el mundo de prueba de 2048 de alto (1024 chunks): heap del
  servidor 818 → 720 MB (-12%); los datos de bloques de las secciones
  comprimidas pasaron de 94 MB a 7,8 MB. El mundo guardado da los mismos
  bloques que sin compresión. En mundos de altura normal el efecto es chico
  (casi nada queda a más de 8 secciones del jugador).
- **Nueva opción `cubico.compartirSeccionesUniformes`** (apagada): las
  secciones de un solo bloque comparten un contenedor, con copia al escribir.
  Ganó ~10 MB en la prueba: la roca profunda casi nunca es uniforme (menas,
  tufa, diorita) y muchas secciones tienen dos biomas. Si otro mod escribe
  directo en las secciones, avisa con un error en vez de romper el mundo.
- Por qué no se descargan secciones enteras: todo Minecraft (carga, luz,
  guardado, red) y los demás mods asumen columnas completas; achicar lo que
  guarda cada sección lejana da el ahorro sin romper eso.

## 0.26.2 — Completar el relleno al bajar (etapa 2 de cubic chunks, parte 2)
- Con `cubico.generacionVertical`, cuando un jugador se acerca en altura a
  una columna con relleno (a menos de `distanciaJugador` secciones), la
  banda que falta se genera con ruido, acuíferos y reglas de superficie en un
  chunk aparte (hilo de fondo) y se mezcla en el real: entran las cuevas de
  ruido, los acuíferos y las vetas; quedan las menas, las cuevas de carvers,
  las estructuras y lo que haya puesto un jugador. Después se recalcula la
  luz y se reenvía el chunk. Arriba (si se recortó) entran las islas
  flotantes, sin la vegetación de las features.
- Medido contra el mismo mundo generado completo (y −64..0, 49 columnas):
  99,2% de bloques iguales (con la franja sola, 95,8% y faltaba ~40% del
  volumen de cuevas); la diferencia que queda es la vegetación de las cuevas
  frondosas (musgo, arcilla, pasto), que viene de features. Costo por
  columna: 83 ms en segundo plano y 3,8 ms en el hilo del servidor.
- Comando para administradores: `/lodcubico completar <radio> <seccionY>`.
- Arreglo: la superficie preliminar (acuíferos, reglas de superficie) se
  busca en la altura completa aunque el ruido esté recortado.

## 0.26.1 — Generación por franja vertical (etapa 2 de cubic chunks, parte 1)
- **Nueva opción experimental del servidor `cubico.generacionVertical`**
  (apagada, en `minecraftlodmod-server.toml`): el ruido del terreno se
  calcula solo en una franja de cada columna, la superficie con margen
  (`margenAbajo`, 4 secciones) y la altura de los jugadores cercanos
  (`distanciaJugador`, 8). Abajo queda piedra de relleno que no cuesta nada;
  sobre ella siguen la pizarra profunda, el lecho de roca, las menas y las
  cuevas de los carvers (faltan cuevas de ruido, acuíferos y vetas grandes).
  Con `recortarArriba` también se recorta encima de la superficie (las
  islas flotantes fuera de la franja no se generan; el LOD aproximado las
  sigue mostrando). Solo chunks nuevos, no Nether ni End.
- Medido en un mundo de prueba de 2048 de alto (-1024..1023), 1024 chunks en
  un servidor dedicado: ruido por chunk 171 ms → 103 ms con la franja
  (-40%) → 28 ms recortando también arriba (6× menos). Estimar la franja
  cuesta 1,4-1,7 ms por chunk.
- Cada chunk guarda la franja que se generó completa: es la marca para
  completar las secciones de relleno cuando un jugador se acerque (parte 2).
- **Arreglo:** el mod no arrancaba en un servidor dedicado (el VulkanMod
  integrado tocaba LWJGL, que el servidor no tiene).

## 0.26.0 — Sincronización vertical (etapa 1 de cubic chunks) y LOD vertical
- **Nueva opción experimental "Sincronización vertical"** (pestaña
  Experimental, apagada): el servidor te manda de cada columna solo las
  secciones de 16 bloques cercanas en altura ("Distancia vertical", 8 por
  defecto); las de más arriba y más abajo te llegan como aire. Al subir o
  bajar se mandan las que entran y se vacían las que salen. Menos memoria,
  red y armado de mallas en el cliente (también con Sodium, que recibe datos
  normales). Idea tomada de Vertigo (Builderb0y, MIT), escrita de nuevo
  para NeoForge.
- **LOD vertical:** lo que no está en el rango lo dibuja el LOD, así las
  islas flotantes, las montañas altas y lo de abajo siguen viéndose (con
  Vertigo desaparecen).
- Medido en Xvfb (distancia de render 6, distancia vertical 3): el cliente
  pasó de 1468 secciones con bloques a 30 volando a y=200 y a 448 a y=80;
  bajar de 200 a 80 movió 1407 secciones (1,2 MB).
- Necesita el mod en el servidor; el servidor puede prohibirla
  (`permitirSincroVertical`). El log muestra `secciones cliente`.

## 0.25.7 — Mallas en varios hilos
- En el video de la 0.25.3 (GTX 1060) la cola de mallas por armar llegaba a
  ~960 mientras se volaba: un solo hilo las armaba todas y lo cercano
  tardaba en aparecer. Ahora usa un tercio de los núcleos (de 1 a 3; en el
  i5-9400, 2 hilos), cada uno con su propia geometría reutilizable.

## 0.25.6 — Chunks huecos, lluvia sin tirones, RAM llena con lo cercano, opciones con Vulkan
- **Chunks huecos o vacíos (y LOD cercano que faltaba):** un chunk se
  extraía apenas cargaba, a veces antes de que el motor de luz lo iluminara;
  sin luz, todas sus caras se descartaban como si fueran cuevas y quedaba
  hueco o vacío, marcado como hecho para siempre. Ahora se espera a que
  tenga luz. Los chunks extraídos con versiones anteriores se vuelven a
  extraer una vez cuando cargan (más trabajo de generación al principio).
- **Tirón al empezar o terminar de llover o nevar:** la lluvia achicaba el
  radio del plan en décimos y cada décimo rearmaba celdas. Ahora las mallas
  quedan armadas y lo que tapa la neblina simplemente no se dibuja.
- **RAM para LOD:** el cache ya no se llena solo con lo que se pide. Un
  hilo de fondo trae del disco las regiones más cercanas, de adentro hacia
  afuera, hasta llenar el 90 % (a ~40 MB/s, para no competir con el disco
  del juego). Al llenarse, se desaloja primero lo lejano; lo que queda lejos
  sigue en disco. Si la RAM elegida no entra en la memoria de Java (-Xmx),
  se usa como mucho el 40 % de ella y el log lo avisa. El log muestra
  `cache RAM N MB`.
- **Opciones de video con Vulkan:** la pantalla de VulkanMod tiene un
  botón "LOD" que abre las opciones del LOD, y la del LOD tiene "Opciones de
  VulkanMod" arriba de todo en la pestaña Video. Se llega a las dos desde el
  menú principal y desde la partida.

## 0.25.5 — Índice de regiones append-only
- Cada lote de escritura (cada 3 s) reescribía entero el índice de cada
  región tocada, y en este mundo hay índices de hasta 1,5 MB: mucho disco
  con la aproximación tocando muchas regiones (se nota en discos mecánicos).
  Ahora cada lote agrega al final solo sus entradas nuevas (un bloque de
  diario con CRC). Cuando el diario crece más que el índice, se reescribe
  entero una vez: el costo queda repartido.
- Un corte de luz a mitad de un bloque no rompe nada: el bloque cortado o
  dañado se ignora (esos nodos se regeneran) y se recorta en la próxima
  escritura.
- Los mundos existentes cargan igual (un índice sin diario es el formato
  anterior). Si volvés a una versión anterior, esa no lee el diario: los
  nodos de los últimos lotes se regeneran, sin romper nada.

## 0.25.4 — Menos tirones: disco, recolector de basura y prioridades
Revisión con el perfilador (JFR) buscando tirones y caídas de rendimiento.
- **El juego esperaba al disco:** el hilo que guarda el LOD tenía tomado el
  candado de cada región mientras forzaba la escritura al disco (`fsync`,
  hasta 330 ms en el perfil) o compactaba el archivo. Cualquier lectura de
  esa región esperaba: el hilo del servidor (bloqueado 150 ms en el perfil:
  tirones de todo el juego en singleplayer), el de mallas y el relieve del
  render. Ahora las lecturas no usan ese candado.
- **Archivos abiertos una vez:** cada nodo leído abría y cerraba el archivo
  de la región (en Windows, con el antivirus mirando cada apertura, caro).
  Ahora los archivos de lectura quedan abiertos.
- **Relieve fuera del cuadro:** la oclusión por relieve leía y descomprimía
  hasta 32 regiones del disco dentro del cuadro, y cada 60 s vencían todas
  juntas. Ahora se leen en un hilo aparte.
- **Menos basura para el recolector** (pausas de 35-90 ms en el perfil):
  índices de región sin objetos por nodo (cargar uno de 65 000 nodos tardaba
  235 ms), escritos y leídos por partes en vez de arreglos de 1,5 MB; una
  sola geometría reutilizada para armar mallas en vez de una nueva que crecía
  hasta varios MB por celda; menos copias de vóxeles al generar y reducir.
- **Generación con menos prioridad que el juego:** los hilos de generación
  (que la aproximación usa al máximo) quedan por debajo del render y del
  servidor; en Windows eso llega al sistema operativo.
- **Subidas a la GPU con tope de tamaño** por cuadro (4 MB), no solo de
  cantidad: cuatro teselas lejanas grandes juntas trababan el cuadro.
- Servidor: la lista de chunks pendientes se recorre una vez por tick, no
  ocho. HUD de rendimiento: el texto se arma una vez por segundo, no en cada
  cuadro.
- Log: las estadísticas del LOD dicen cuántas veces se replanificó y la más
  larga (`planes N (máx X ms)`), que corre dentro del cuadro.

## 0.25.3 — Shaders de Voxy (paso 4, etapa A: diagnóstico)
- Con Iris y un shaderpack que trae el contrato de Voxy (`voxy.json` +
  `voxy_opaque.glsl` / `voxy_translucent.glsl`, como Complementary r5.9),
  el LOD lee esos archivos con las opciones del pack aplicadas, los compila
  con su propio vértice y anota el resultado en el log (`LOD/Voxy: ...`).
  Las fuentes armadas quedan en `.minecraft/minecraftlodmod/voxy/`.
- **Todavía no cambia cómo se ve:** el LOD se sigue dibujando con el
  terreno del pack (`gbuffers_terrain`), igual que en la 0.25.2. Dibujar
  con el código Voxy del pack es la etapa B.
- Verificado: los dos programas de Complementary r5.9.3 compilan y enlazan
  (Mesa, render por software).

## 0.25.2 — Catalejo sin tirones, niebla con lluvia y en zonas sin datos
- **Tirón al usar el catalejo:** mientras el zoom se animaba, el LOD se
  replanificaba entero en cada cuadro (plan, relieve, celdas a rearmar).
  Ahora espera a que el FOV quede quieto, o como mucho replanifica cada
  0,4 s mientras se mueve.
- **Niebla con lluvia** (Calidad, prendida): con lluvia o tormenta el LOD
  tiene más neblina y un radio más chico (hasta la mitad). Lo que la niebla
  tapa no se dibuja: menos carga de GPU mientras llueve.
- **Niebla en zonas sin datos** (Calidad, prendida): en cada dirección, la
  niebla termina donde empieza la primera zona que el LOD todavía no tiene
  (sin cargar o armándose), así no se ven bordes rectos ni huecos.
- **Menos GPU en el acabado:** la oclusión ambiental en pantalla (SSAO) se
  calcula a media resolución (4 veces menos píxeles) y se lleva a la
  resolución completa con el promedio que ya hacía.
- Las versiones ahora suben de a parche salvo cambios muy grandes.

## 0.25.1 — Interfaz con Vulkan y chunks huecos
- **Con Vulkan no se veía la interfaz** (HUD, barra de objetos, mira, F3)
  si el Java no trae `jdk.attach` (el de Modrinth no lo trae): VulkanMod
  tenía que convertir la profundidad de las proyecciones a la de Vulkan y esa
  conversión nunca se aplicaba, así que se recortaba todo lo de la pantalla.
  Ahora se aplica siempre que el arreglo por `jdk.attach` no está activo.
  El mensaje "jdk.attach module not found" del log es normal y no rompe nada.
- **Chunks huecos que quedaban vacíos:** si un chunk real se cargaba con la
  cola de extracción llena y se descargaba antes de extraerse, quedaba sin
  datos, y la aproximación (que lo había salteado por estar a la vista) no
  volvía a pasar. Ahora la aproximación vuelve a recorrer su zona cada minuto
  y tapa esos huecos.

## 0.25.0 — Que no se noten los vóxeles
- **Arreglado: chunks aproximados que no terminaban nunca.** La búsqueda de
  la superficie calculaba mal el punto medio con alturas negativas (el mundo
  baja a -64) y quedaba dando vueltas para siempre: había chunks cercanos
  que "tardaban minutos" y un hilo de generación ocupado sin avanzar. En la
  prueba, el horizonte aproximado pasó de no completar casi nada a ~290
  chunks por segundo (radio de 160 chunks en unos 3 minutos).
- **Aproximación más fina cerca:** las zonas por las que nunca pasaste se
  estimaban solo en vóxeles de 8 bloques, que de cerca ocupan decenas de
  píxeles y se notaban por más bajo que fuera el tope de píxeles. Ahora,
  hasta 512 bloques se estiman en vóxeles de 2 bloques y hasta 1024 en
  vóxeles de 4. Lo ya aproximado en grueso que queda cerca se rehace solo.
- **Fundido entre niveles** (Calidad, prendido): cuando una zona cambia de
  nivel de detalle, la malla vieja y la nueva se cruzan con un tramado en
  menos de medio segundo en vez de saltar de golpe. La vieja se sigue
  dibujando hasta que la nueva está lista, así que tampoco aparecen huecos
  al cambiar de tesela lejana. Con colores planos, VulkanMod o shaderpacks
  solo se evitan los huecos (sin tramado).
- El log de la aproximación dice cuántas evaluaciones del terreno hace.

## 0.24.1 — Los chunks reales primero
- **La aproximación del horizonte ya no frena los chunks cercanos:** cuando
  el auto-ajuste dejaba un solo lugar de generación (procesador justo, como
  la A275), la aproximación lo ocupaba minutos y los chunks reales cercanos
  no entraban al LOD (en la prueba: cientos esperando, 0 por segundo). Ahora
  la extracción de chunks reales tiene prioridad: la aproximación no arranca
  si hay chunks reales esperando, nunca ocupa el último lugar si hay más de
  uno, y si ya está trabajando cede el turno enseguida (entre cada cálculo
  del terreno) y retoma después. En la prueba, tras teletransportarse a una
  zona nueva: 16, 193 y 80 chunks reales cada 10 s (antes 0), con la
  aproximación cediendo 75 veces y siguiendo igual con el horizonte.
- **No aproxima lo que vanilla va a cargar igual:** los chunks dentro de la
  distancia de visión llegan como chunks reales; estimarlos era el trabajo
  más caro y competía por el procesador con la generación del mundo.
- La primera columna de cada chunk cercano arranca la búsqueda desde la
  estimación rápida de la superficie en vez del techo del mundo.

## 0.24.0 — Luz de antorchas de noche y biomas que se funden
Tercer paso de "que el LOD se vea como Voxy" (solo la idea, sin su código).
- **De noche, lo iluminado sigue iluminado:** el LOD guardaba un solo valor
  de luz y de noche se oscurecía todo junto. Ahora guarda aparte la luz de
  bloque (antorchas, lava, faroles, piedra luminosa): de noche las aldeas,
  los ríos de lava y las construcciones iluminadas se ven a lo lejos con luz
  cálida, como en vanilla. Con shaderpacks esa luz va también al lightmap.
- **Biomas que se funden:** el color del pasto, las hojas y el agua se mezcla
  entre biomas vecinos como el "biome blend" de vanilla, en vez de cambiar en
  escalones de 4 bloques. Se nota en el borde de dos biomas visto de lejos.
- Las zonas ya guardadas no se pierden: toman la luz de antorchas y la mezcla
  de biomas a medida que se vuelven a generar (al cambiar bloques o al
  regenerar el caché).

## 0.23.0 — Acabado como Voxy: sombras suaves y neblina
Segundo paso de "que el LOD se vea como Voxy" (solo la idea, sin su código).
- **El LOD termina en la niebla:** antes el terreno lejano no tenía niebla
  y se cortaba contra el cielo. Ahora la misma niebla que vanilla corre hasta
  el alcance del LOD se aplica también al LOD: el borde se esconde en ella.
- **Neblina atmosférica** (Calidad, 0 a 1, por defecto 0,5): el terreno se
  acerca al color del cielo con la distancia, como el aire real, desde donde
  termina vanilla hasta el horizonte. Sin costo de rendimiento.
- **Oclusión ambiental en pantalla (SSAO)** (Calidad, prendida): sombra
  suave en valles, pies de montaña y bajo los árboles, calculada sobre la
  imagen del LOD, además de la horneada. El radio crece con la distancia,
  así de lejos marca el relieve. Cuesta algo de GPU: en gráficos integrados
  débiles conviene apagarla.
- Solo con OpenGL; con VulkanMod o shaderpacks no cambia nada.

## 0.22.0 — Texturas como Voxy: modelos horneados y hojas con volumen
Primer paso de "que el LOD se vea como Voxy" (solo la idea, sin su código).
- **Atlas propio del LOD:** las texturas del paquete activo se copian a un
  atlas del LOD con teselas del mismo tamaño y sus propios mipmaps. Así la
  textura repetida por bloque ya no se mezcla con la de al lado al alejarse.
- **Modelos horneados:** lo que no es un cubo (escaleras, losas, cercos,
  muros, cactus, faroles...) se dibuja por software desde arriba y desde un
  costado con su modelo real. El LOD usa esa imagen: de lejos una escalera
  o un cerco se ven como tales y no como un cubo con la textura de una cara.
- **Hojas con volumen:** los huecos de las hojas ya no se rellenan con el
  color plano: van oscurecidos, como el interior de un árbol. El bosque
  lejano tiene textura y profundidad en vez de ser un bloque verde liso.
  El color medio de lejos no cambia.

## 0.21.0 — Escalado que gana FPS (o se apaga solo)
- **El LOD se arma para la resolución a la que se dibuja el mundo:** con
  escalado, el detalle se mide en píxeles de la resolución interna. Antes el
  LOD mandaba los mismos vértices que sin escalar (su costo real) y el
  escalado no ganaba nada: en la prueba, FSR 1 al 50% pasó de no ganar nada
  a +24% de FPS. El tope de "píxeles máximos por vóxel" sigue siendo en
  píxeles de pantalla.
- **XeSS y DLSS sin frenar el procesador:** el puente con Vulkan se
  sincroniza con semáforos en la placa de video en vez de esperar a que
  termine todo en cada cuadro (glFinish), que era lo que hacía perder FPS.
  Si el driver no los tiene, sigue como antes. Sin probar todavía en tu PC.
- **"Escalado solo si da más FPS"** (Depuración / Experimental, prendido):
  cada 2 minutos mide unos segundos con escalado y sin él y lo deja solo si
  los cuadros salen más rápidos. Si el límite es el procesador (lo más común
  en Minecraft), bajar la resolución no gana y ahora se apaga solo; el log
  dice qué midió.

## 0.20.1 — El horizonte lejano aparece, bosques nevados y mar helado
- **El LOD lejano no aparecía** (se cortaba a una distancia fija aunque el
  radio fuera de miles de chunks). Varias causas, todas arregladas:
  - las zonas lejanas buscaban la altura del terreno con la función más cara
    del generador (la que incluye cuevas) y cada una ocupaba un hilo minutos;
    ahora usan la estimación de superficie de vanilla, cientos de veces más
    rápida;
  - lo cercano llenaba todos los hilos y lo lejano (y el armado de los
    niveles grandes que lo dibujan) esperaba en la cola: ahora siempre queda
    un hilo para eso;
  - con el servidor cargado (por ejemplo pregenerando) la generación
    aproximada se frenaba del todo: ahora sigue de a una tarea;
  - cambiar el radio o el preset con el mundo abierto no llegaba ni al
    render ni a la generación hasta volver a entrar: ahora se toma en vivo;
  - un error en la generación aproximada la apagaba hasta reiniciar el
    mundo: ahora se reintenta al minuto.
- El HUD cuenta en "approx" también las zonas lejanas (en chunks cubiertos).
- **Bosques nevados:** a lo lejos siguen siendo bosque (copas con nieve
  arriba), no una planicie blanca pelada. También los árboles reales se
  conservan más al pasar a vóxeles grandes (un vóxel se ve si cubre al menos
  media superficie vista desde arriba).
- **Mar y ríos helados:** el LOD aproximado les pone hielo encima.
- Se regenera el LOD guardado.

## 0.20.0 — Relieve lejano a la altura real y horizonte de más de 2048 chunks
- **Los vóxeles grandes siguen la forma del terreno:** cada vóxel guarda hasta
  qué altura llega lo sólido adentro, y su superficie se dibuja ahí, no en el
  borde del cubo. Un vóxel de 16 o de 256 bloques ya no es un escalón de su
  tamaño: el relieve lejano queda a la altura real, bloque a bloque en
  vertical, y el mar queda al nivel del mar.
- **Vóxeles más grandes para lo muy lejano:** niveles hasta vóxeles de 1024
  bloques, y el radio del LOD llega hasta **8192 chunks (~131 km)**.
- **Píxeles máximos por vóxel** (Calidad LOD, 4 por defecto): el detalle se
  elige por cuánto ocupa cada vóxel en pantalla; con este tope ninguno se ve
  más grande, por lejos o grande que sea. El auto-ajuste no lo pasa: si falta
  rendimiento, recorta el radio.
- **Horizonte aproximado por región:** más allá de 512 chunks ya no se
  aproxima chunk por chunk sino por zonas enteras (una columna por vóxel), con
  zonas más grandes cuanto más lejos: el horizonte de decenas de km se llena
  cientos de veces más rápido.
- Arreglos: una costa o el borde de una meseta ya no desaparecen al pasar a un
  nivel más grueso.
- **Se regenera todo el LOD guardado** (cambió el formato de la altura).

## 0.19.0 — Terreno lejano más natural, nubes y curvatura
- **Vóxeles grandes como terreno:** a lo lejos, el costado de los bloques de
  pasto (y nieve, micelio, podzol) ya no muestra una línea de pasto por cada
  bloque: la franja va solo arriba y abajo se ve tierra, como un corte del
  terreno. Opción "Vóxeles grandes como terreno" (Calidad LOD).
- **Nubes lejanas:** las nubes siguen, con la misma forma y movimiento, hasta
  donde llega el LOD, y se funden con el cielo en el horizonte (antes se
  cortaban a unos cientos de bloques). Opción "Nubes lejanas".
- **Curvatura del horizonte** (apagada por defecto): el terreno lejano y las
  nubes bajan como sobre un planeta. Radio configurable: Tierra 1:1 (por
  defecto), Marte, Luna o planetas más chicos para un efecto más notorio.
- **Horizonte real:** con la curvatura, el radio del LOD sale de la altura de
  los ojos y el tamaño del planeta (hasta dónde se vería el horizonte de
  verdad) en vez del radio del preset. El auto-ajuste lo sigue recortando si
  falta rendimiento.

## 0.18.0 — Todo en Opciones > Video
- **Opciones > Video ahora es la pantalla estilo Sodium**, con las opciones de
  video de Minecraft y las del LOD juntas: pestañas Video, Gráficos, LOD,
  Calidad LOD, Generación y Experimental.
- Están todas las opciones de video de Minecraft (distancia de render y de
  simulación, gráficos, nubes, partículas, escala de interfaz, FPS máximos,
  VSync, pantalla completa y su resolución, mipmaps, etc.), cada una con su
  explicación y cuánto pesa en el rendimiento.
- Botón "Opciones de video originales" por si otro mod agrega algo en la
  pantalla de siempre. Con Iris, botón para los paquetes de shaders.
- Con Sodium, Embeddium o Vulkan activo, la pantalla de video es la de ellos
  y el LOD sigue con su botón "LOD".

## 0.17.0 — Opciones con el estilo de Sodium
- **Pantalla de opciones nueva**, con el estilo de las de Sodium: pestañas
  (General, Calidad, Generación, Depuración / Experimental), filas prolijas
  con el nombre a la izquierda y el control a la derecha, y un panel que
  explica cada opción y cuánto pesa en el rendimiento.
- Los cambios quedan en amarillo hasta aplicarlos. Deshacer / Aplicar / Hecho
  abajo a la derecha (Ctrl+Z y Ctrl+S también). Al cerrar se aplican solos.
- Botón **"LOD"** arriba a la derecha de Opciones > Video, además del botón
  Config de la lista de mods.
- Ya no hace falta Cloth Config.

## 0.16.0 — Auto-ajuste según el procesador y la placa de video
- **"Auto-ajuste dinámico" ahora funciona** (la opción existía pero no estaba
  conectada al juego). Cada segundo mide cuánto tarda el cuadro y cuánto
  trabaja la placa de video, decide cuál de los dos limita y alivia a ese lado:
  - **Placa de video al límite:** separa más las caras del terreno lejano (la
    placa se saltea las que dan la espalda), oculta más terreno tapado y, si
    usás escalado, baja un poco su resolución (hasta 15 puntos, nunca por
    debajo del 50%).
  - **Procesador al límite:** genera menos terreno a la vez y agrupa más las
    caras (menos llamadas de dibujo).
  - **Tirones:** si el promedio alcanza pero hay cuadros muy lentos, baja la
    generación simultánea, que es lo que suele causarlos.
  - Solo si nada de eso alcanza baja el detalle (como mucho al doble del
    preset) y el radio, y los recupera cuando sobra margen.
- El HUD de rendimiento muestra qué limita ("límite GPU (GPU 12.3 ms)"), si
  hay tirones y "PISO" si ya no queda nada que bajar.
- Respeta el tope de FPS de las opciones de video como objetivo si es menor
  que el del preset.

## 0.15.1 — Optimización (medida con un perfilador)
- **Guardado del LOD mucho más liviano:** el hilo que escribe las regiones a
  disco pasaba casi todo su tiempo copiando la lista de nodos de cada región
  (~8% de todo el CPU del juego mientras se genera). Ahora la copia es directa.
- **Menos lecturas de disco:** una región sin datos (o con datos de una versión
  anterior del mod) ya no se vuelve a buscar en el disco en cada consulta.
- **Cache de nodos sin colisiones:** las claves se repartían mal y el cache se
  volvía lento; ahora se mezclan antes de guardarlas.
- **Menos basura para el recolector de memoria:** compresión con buffer
  reusado, lectura de nodos sin objetos intermedios, mallado sin matrices nuevas
  por capa, y enums/materiales de bloque calculados una sola vez.
- **Horizonte aproximado:** la altura de cada columna se busca con menos
  evaluaciones del generador de terreno (búsqueda binaria).

## 0.15.0 — VulkanMod integrado
- **Vulkan viene incluido en el mod**: ya no hace falta instalar VulkanMod ni
  Forgified Fabric API. El interruptor "Vulkan" de Depuración / Experimental lo
  prende o apaga para el próximo arranque (también apaga la ventana de carga
  temprana de NeoForge, que Vulkan no admite, y la devuelve al apagarlo).
- **Importante:** sacá el VulkanMod suelto (y su "vulkan-libs") de la carpeta de
  mods; los dos juntos no arrancan. La Forgified Fabric API suelta puede quedar.
- Es el código de VulkanModNeoForge 0.5.5-dev+3.1 (LGPL-3.0) con el arreglo de
  los triángulos estirados hecho en su origen. Sus opciones siguen en
  Opciones > Video.
- Solo Windows y Linux. El jar pesa ~15 MB más (22 MB) por las librerías de Vulkan y Fabric.

## 0.14.0 — Noche, nieve y arreglos con VulkanMod
- **De noche el LOD se oscurece** como el resto del mundo: sigue la hora
  del día, la lluvia, las tormentas, la visión nocturna y el brillo de la
  configuración (antes quedaba iluminado como de día).
- **Nieve:** una capa de nieve ya no convierte el bloque de abajo en un cubo
  blanco. Las hojas, el pasto o la piedra conservan sus costados y solo la
  cara de arriba se ve nevada. Se regenera el LOD guardado.
- **VulkanMod:** se arreglaron los triángulos estirados ("espigas") del LOD
  lejano. Era un problema de VulkanMod con buffers de más de 65 536 vértices;
  con VulkanMod el LOD ahora parte esas mallas.
- **Escalado:** el menú solo muestra los modos que tu placa puede usar
  (DLSS solo con NVIDIA RTX; XeSS con Intel Arc/Xe, NVIDIA desde la serie
  10 y AMD RX 5000 en adelante; ninguno fuera de Windows). Si la config
  pedía uno que no se puede, se usa el Temporal.

## 0.13.0 — Texturas del LOD con VulkanMod
- Con VulkanMod, el terreno LOD ahora se ve con texturas (igual que sin
  VulkanMod) en vez de colores planos. Usa una variante del shader que el
  conversor de VulkanMod acepta; si no cargara, sigue con colores planos.
- FSR y el escalado siguen apagados con VulkanMod.

## 0.12.0 — Escalado temporal, XeSS y DLSS (experimentales)
- La opción "Escalado" (Depuración / Experimental) elige: Apagado, AMD FSR 1,
  Temporal (propio), Intel XeSS o NVIDIA DLSS.
- **Temporal (propio):** junta varios cuadros con un desplazamiento
  sub-píxel distinto y vectores de movimiento reconstruidos desde la
  profundidad. Anda en cualquier GPU; quieto queda casi igual que la
  resolución nativa.
- **XeSS / DLSS:** el mod levanta un Vulkan propio en la misma GPU y comparte
  las imágenes con OpenGL. Las DLL no vienen con el mod:
  - XeSS: `libxess.dll` (SDK de Intel XeSS) en `.minecraft/minecraftlodmod/`.
  - DLSS (solo RTX): `sl.interposer.dll`, `sl.common.dll`, `sl.dlss.dll` y
    `nvngx_dlss.dll` (SDK de NVIDIA Streamline) en
    `.minecraft/minecraftlodmod/streamline/`.
  Si falta algo o falla, el escalado sigue con el Temporal y lo avisa en el log.
- Opción "Invertir jitter (XeSS/DLSS)" por si la imagen tiembla con esos dos.

## 0.11.0 — Vulkan, shaders y más
- **VulkanMod:** el LOD ya no cuelga el juego y se dibuja con colores planos
  (los shaders propios del LOD y FSR no funcionan bajo VulkanMod).
- **Interruptor de Vulkan** en "Depuración / Experimental": activa o
  desactiva VulkanMod para el próximo arranque (renombra el jar a
  `.disabled`, como Modrinth; si está cargado, al cerrar el juego).
- **Shaders (Iris):** con un shaderpack el LOD se dibuja con el terreno del
  pack, que lo ilumina. Los packs con niebla de borde lo tapan pasada la
  distancia vanilla (se puede apagar en las opciones del pack).
- Con Sodium/Embeddium, el LOD ya no se dibuja debajo del terreno vanilla
  cercano.
- Arreglo: al prender/apagar texturas o shaders, algunas celdas cercanas
  quedaban armadas con el modo anterior y no se dibujaban.

## 0.10.0 — Menú de depuración, arreglos de FSR y VulkanMod
- Nueva sección del menú "Depuración / Experimental": HUD de rendimiento, log
  de depuración y FSR (activo, escala y nitidez).
- FSR: entidades, agua, hielo, vidrio, la mano y el contorno del bloque
  apuntado se dibujaban al doble y corridos (el viewport quedaba del tamaño de
  la ventana). Arreglado; las líneas también toman el tamaño real.
- El LOD se dibuja de cerca a lejos (lo mirado primero): la GPU descarta
  antes lo que queda tapado.
- Con VulkanMod instalado, el dibujo del LOD y FSR se desactivan solos en vez
  de colgar el juego (la generación sigue). Soporte real pendiente.
- La config de HUD, log y FSR pasa a la sección `[experimental]` del archivo:
  esos valores vuelven a su default una vez.

## 0.9.0 — HUD de rendimiento y log de depuración
- HUD arriba de la pantalla: FPS, tiempo de cuadro (promedio, peor, 1% bajo),
  CPU del juego y del sistema, RAM, tick del servidor, costo del LOD y ritmo
  de extracción, horizonte aproximado y pregeneración. Opción "HUD de
  rendimiento"; se oculta con F1 o F3.
- Log de depuración (`logs/minecraftlodmod-depuracion.log`): una línea por
  segundo con rendimiento, posición y trabajo del LOD, más eventos (entrar o
  salir de un mundo, cambios de config, tirones). Opción "Log de depuración".
- Número de versión real en el jar (antes todos decían 0.1.0).

## 0.8.0 — `b562322`
- Fondo bajo el agua en el LOD: sin huecos al mirar a ras del agua.
- El LOD le cede un chunk a vanilla solo cuando vanilla ya lo compiló.
- Umbral de detalle por distancia: más detalle cerca, menos lejos.

## 0.7.0 — `de1eb5a`
- AMD FSR 1 experimental (apagado por defecto).

## 0.6.0 — `60568f3`
- Horizonte aproximado desde el generador del mundo, sin generar chunks.
- Piezas lejanas en un solo buffer (menos llamadas de dibujo).

## 0.5.0 — `8653158`
- Comando `/lod pregenerar [on|off|radio]`.

## 0.4.0 — `ede69de`, `db9f56d`
- Oclusión por relieve (no se dibuja lo tapado por montañas).
- Prioridad por vista: zoom con detalle solo en lo que se mira; mallas y
  generación primero en el campo visual.

## 0.3.0 — `bb62284`
- Arreglos de la primera prueba: calibración colgada, niebla, balanceo de
  cámara, huecos en el borde con vanilla.
- Opción "LOD activado" y pregenerador integrado.

## 0.2.0 — `fbe9546`
- Vértice compacto de 12 bytes, reducción por bloque representativo, dibujo
  por dirección de cara, caras entre secciones vecinas fuera, oclusión
  ambiental en las caras de arriba.

## 0.1.0
- Base: generación LOCAL, almacenamiento, render con texturas, niveles
  grandes, red, config y calibración por benchmark.
