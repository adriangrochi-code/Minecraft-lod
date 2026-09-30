# Registro de versiones

Numeración 0.MENOR.PARCHE: MENOR sube con funciones nuevas, PARCHE con solo
arreglos. El número está en el nombre del jar, en el HUD de rendimiento y en
la primera línea del log de depuración. Las versiones 0.2.0 a 0.8.0 se
numeraron después de entregadas (esos jars decían 0.1.0); el commit indica
cuál es cuál.

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
