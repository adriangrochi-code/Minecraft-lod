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

**Implementado en H10 (2026-10-02):**
- **Mundo periódico en x** (`tierra/Costura`): la vuelta al ecuador C se
  redondea a múltiplo de 16 (5 003 776 bloques a 1:8, 6 671 712 a 1:6; k =
  C/360) y `AlturaTierra` envuelve x a [-C/2, C/2) antes de consultar: el
  terreno en x y en x ± C es el mismo bloque a bloque (también agua, clima y
  biomas). Pasando el antimeridiano se genera la continuación, idéntica.
- **Ruidos propios** (detalle de `DetalleTierra`, cuevas de `CuevasTierra`):
  en los 512 bloques antes de +C/2 se mezclan con su valor una vuelta antes
  (peso con curva suave), así en +C/2 valen lo mismo que en -C/2, sin escalón.
  Las cuevas reciben C por el JSON (`periodo_x` de `tierra_cuevas`; un test
  compara con `Costura.circunferencia`). Lo de vanilla (menas, árboles,
  estructuras, ruido de las reglas de superficie) no es periódico: del otro
  lado de la costura cambia, como entre dos chunks cualquiera.
- **El salto** (`tierra/CosturaTierra`): pasando la costura por más de 32
  bloques, el jugador va una vuelta atrás con teletransporte **relativo**
  (conserva velocidad, mirada y vuelo), sin pantalla de carga; su vehículo y
  los demás pasajeros, y los animales con rienda, con él. Al cliente se le
  manda el vehículo de nuevo en la posición nueva (si no, quedaba quieto en
  un chunk que el cliente ya descargó y el jugador veía solo cielo). Otras
  entidades (mobs, ítems, botes vacíos) cruzan solas si el chunk de llegada
  está cargado. Los 32 bloques de margen evitan el rebote.
- **Precarga y espera:** desde 1 024 bloques (más la distancia de vista)
  antes de la costura, un ticket carga los chunks del otro lado alrededor de
  donde va a aparecer el jugador. El salto espera a que los 3×3 chunks de
  llegada estén cargados: NeoForge carga el chunk de destino de forma
  síncrona al mover una entidad (`Entity#setPosRaw`) y, si se estaba
  generando, trababa el servidor decenas de segundos. Mientras espera, el
  jugador sigue en la continuación, que es el mismo terreno.
- **LOD:** `FuenteAltura` usa `AlturaTierra`, que ya es periódica: el
  horizonte aproximado se dibuja a través de la costura sin nada especial.
- **Polos:** siguen sin borde en la cilíndrica (z > C/4); queda para después.
