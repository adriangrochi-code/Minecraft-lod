# Tierra real — paquete de diseño para la sesión nueva

Mundo aparte (tipo de mundo propio, **nunca por defecto**) que genera la
Tierra real a escala reducida (1:8 por defecto, 1:6 opcional), pensado para
trabajar junto al LOD, la generación por franja vertical y la compresión de
secciones lejanas que ya tiene el mod.

## Orden de lectura

1. `01-decisiones.md` — todo lo que ya está decidido, con los números.
2. `02-datos.md` — fuentes de elevación y clima, licencias, formato propio.
3. `03-generador.md` — cómo se arma el generador (función de densidad
   propia dentro del generador por ruido de vanilla).
4. `04-integracion-lod.md` — **lo más importante**: cómo se ayudan el LOD y
   el generador (los dos sentidos).
5. `05-borde.md` — Tierra plana con farlands congeladas y, después, dar la
   vuelta al mundo.
6. `06-hitos.md` — plan por hitos, con qué se prueba y qué se mide en cada uno.
7. `07-coordinacion.md` — reglas para trabajar en paralelo con la otra sesión.
8. `prompt-inicial.md` — el texto para pegar al arrancar la sesión nueva.

Antes de esto, como siempre en este repo: `CLAUDE.md`,
`arquitectura-minecraft-lod-mod.md` (sobre todo las secciones 25.7, 29, 30,
31 y 32) y `README.md` de la raíz.

## Lo que hay que verificar (no confirmado al escribir esto)

Las licencias y tamaños exactos de los datos (`02-datos.md`) están escritos
de memoria: la sesión nueva los verifica en las páginas oficiales antes de
usarlos y corrige este paquete si algo no coincide.
