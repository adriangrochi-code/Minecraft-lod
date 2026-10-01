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

**Implementado en H7 (2026-10-01):**
- `tierra/FarlandsCongeladas` (lógica pura, sin semilla: el borde es igual en
  todos los mundos) + función de densidad `minecraftlodmod:tierra_borde`
  (`BordeTierra`), que envuelve `superficie - y` en los `noise_settings` de la
  Tierra plana; dentro del disco devuelve el argumento tal cual.
- Transición (1 000 bloques): la meseta del polo sur (y ~417 a 1:8) partida en
  placas de Voronoi de ~120 bloques; las grietas van de 1,5 a 10 bloques de
  ancho y de 12 a ~270 de hondo hacia afuera.
- Farlands (desde 1 000, en rampa de 160): franjas concéntricas de 16 bloques
  (rebanada radial escalonada) que son pared maciza o pasillo según tramos de
  ~600 bloques del borde; paredes hasta 24-184 bloques bajo el techo (almenas),
  túneles horizontales de 6 cada 28 (corridos por rebanada; con la
  interpolación de celdas de 8 de alto quedan de ~9) y agujeros chicos.
  Siguen hasta el borde del mundo (esquinas del cuadrado).
- Bioma `minecraftlodmod:farlands_congeladas` (clave `farlands` en la tabla
  de biomas): nieva siempre, cielo y niebla fríos, sin mobs ni features. Regla
  de superficie: nieve arriba, hielo compacto con vetas de hielo azul.
- Borde del mundo de vanilla: cuadrado de 2 × (radio del disco + 5 000) de
  lado (5 013 780 a 1:8), solo si el borde es el de vanilla; se aplica al
  iniciar el servidor (al cargar el nivel vanilla todavía no aplicó el
  guardado).
- LOD y franja: el atajo da la cima exacta de la columna
  (`FarlandsCongeladas.alturaColumna`, bajando de a uno desde la cima
  calculada de la pared); `FuenteAltura.simple` da false desde un chunk antes
  del borde y ahí la columna se genera entera.
- La variante cilíndrica no tiene borde todavía (polos: H10 o después).

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
