# Registro de versiones

Numeración 0.MENOR.PARCHE: MENOR sube con funciones nuevas, PARCHE con solo
arreglos. El número está en el nombre del jar, en el HUD de rendimiento y en
la primera línea del log de depuración. Las versiones 0.2.0 a 0.8.0 se
numeraron después de entregadas (esos jars decían 0.1.0); el commit indica
cuál es cuál.

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
