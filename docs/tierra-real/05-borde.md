# 05 — Borde del mundo

## Tierra plana (primero)

- Proyección azimutal equidistante centrada en el polo norte: distancia al
  centro r = (90° - lat) × (circunferencia / 360°), ángulo = longitud. El polo
  sur queda en el borde (r ≈ 2,5 M bloques a 1:8).
- Distancias norte-sur reales; este-oeste se estira hacia el sur (la
  Antártida es un anillo larguísimo: el "muro de hielo").
- **Farlands congeladas**, pasando el anillo antártico:
  - banda de transición (~1 000 bloques) donde el hielo antártico se
    rompe en placas y grietas;
  - farlands propiamente dichas (`anchoFarlands`, 4 000 bloques por
    defecto): imitan el artefacto de la Beta (desborde de precisión del
    ruido) con una función de densidad propia: ruido 3D con la coordenada
    escalonada/envuelta para producir las paredes altísimas con túneles
    horizontales repetidos, hasta cerca del techo del mundo;
  - materiales: hielo compacto, hielo azul, nieve en polvo; bioma propio
    ("farlands congeladas": cielo y niebla fríos, nevando siempre, sin mobs
    pasivos);
  - después: el borde del mundo de vanilla (world border) como límite duro.
- El LOD las dibuja desde lejos como la silueta del muro en el horizonte
  (es de lo que más va a lucir del modo).

## Cilíndrica y dar la vuelta (después)

- Equirectangular: x = lon × (C / 360°), z = -lat × (C / 360°); los polos son
  bordes (farlands congeladas o pared de hielo, mismo sistema que arriba).
- **Este-oeste circunnavegable:** Minecraft no tiene mundo toroidal. Plan:
  - al pasar |x| > C/2, teletransporte al otro lado (misma z, x ∓ C) sin
    pantalla de carga, con los chunks del otro lado precargados (ticket)
    unos cientos de bloques antes;
  - el LOD dibuja a través de la costura **leyendo `FuenteAltura` con x
    envuelto** (el horizonte se ve continuo aunque los chunks reales no);
  - entidades y vehículos (botes, caballos, minecarts) cruzan con el jugador.
  - Riesgo: es el hito más difícil; se hace último.
