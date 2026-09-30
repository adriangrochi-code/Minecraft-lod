# Registro de versiones

Numeración 0.MENOR.PARCHE: MENOR sube con funciones nuevas, PARCHE con solo
arreglos. El número está en el nombre del jar, en el HUD de rendimiento y en
la primera línea del log de depuración. Las versiones 0.2.0 a 0.8.0 se
numeraron después de entregadas (esos jars decían 0.1.0); el commit indica
cuál es cuál.

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
