# Minecraft LOD Mod — instrucciones de proyecto

Mod de terreno LOD para NeoForge 1.21.1, inspirado en Voxy, con énfasis en
rendimiento escalable (Lenovo ThinkPad A275 hasta GTX 1060 + i5-9400) y
compatibilidad amplia (Embeddium, potencialmente VulkanMod).

**Antes de cualquier tarea de diseño o arquitectura, leé
@arquitectura-minecraft-lod-mod.md — 24 secciones con todas las decisiones
ya tomadas y su razonamiento.** No propongas de nuevo algo que ya está
decidido ahí sin releerlo primero; releer ese archivo una vez por sesión es
más barato que reinventar una decisión ya tomada.

**Para el plan de trabajo por sesión, leé @README.md — tiene la Pista A/Pista B.**

## Comandos

```
./gradlew build          # compilación completa
./gradlew test           # TODOS los tests — evitalo salvo al cerrar un hito completo
./gradlew test --tests "com.example.minecraftlodmod.core.*"        # solo un paquete
./gradlew test --tests "com.example.minecraftlodmod.core.LodSelectorTest"  # solo una clase
./gradlew runClient      # lanza el cliente de Minecraft (Pista B, necesita sesión gráfica)
```

## Reglas de eficiencia — leer antes de trabajar

Estas reglas existen para no gastar tokens/tiempo de ventana en trabajo
redundante. Aplican siempre, en ambas pistas.

1. **No releas un archivo que ya está en tu contexto de esta sesión y no
   cambió.** Si ya lo leíste y nadie lo tocó desde entonces, usá lo que ya
   tenés. Volver a leer "por las dudas" quema contexto sin necesidad.

2. **No corras `./gradlew test` completo después de cada cambio chico.**
   Corré solo el test/paquete relevante a lo que tocaste
   (`--tests "...NombreDeLaClase"`). Reservá el test suite completo para el
   cierre de un hito (ver checklist más abajo).

3. **Antes de crear un archivo nuevo, revisá si ya existe algo que resuelve
   lo mismo.** La estructura de paquetes ya está fijada (`core`, `storage`,
   `generation`, `render`, `network`, `benchmark`, `config`) — un archivo
   nuevo va en el paquete que le corresponde por tema, nunca crees un
   paquete nuevo sin que se te pida explícitamente.

4. **No reescribas un módulo ya completo y testeado
   (`core`, `storage`, `generation` lógica pura, `config`) "por las dudas"
   o para "mejorarlo" sin que haya un pedido concreto.** Si mientras
   implementás algo notás una mejora posible en código ya cerrado, anotala
   en `NOTES.md` (crealo si no existe) en vez de tocarlo de una.

5. **Batchear cambios relacionados antes de compilar**, en vez de compilar
   después de cada línea. Un ciclo típico: hacer varios cambios
   relacionados → un `build`/`test` targeted → seguir.

6. **No repitas exploración de directorios ya vista.** Si ya listaste
   `src/main/java/...` en esta sesión, no lo vuelvas a listar salvo que
   hayas creado/borrado archivos vos mismo desde entonces.

7. **Límite de reintentos ante un error que se repite igual:** si el mismo
   enfoque falla 2 veces seguidas de la misma forma, PARÁ de intentar
   variaciones a ciegas. Anotá el error exacto y la hipótesis en
   `NOTES.md`, y seguí con otro ítem de la lista de tareas en vez de seguir
   iterando sin señal nueva — es la forma más común de gastar ventana de
   uso sin avanzar.

8. **No expliques en comentarios lo que ya está explicado en el javadoc
   existente del archivo.** Los archivos ya escritos tienen bastante
   contexto en sus comentarios — no lo repitas en el mensaje de vuelta a mí,
   asumí que yo también puedo leer el código.

## Las dos pistas (ver @README.md para el detalle completo)

**Pista A (de día, sin supervisión):** compilar contra el MDK real,
`generation/` real, `storage/` real, `network/` (protocolo/serialización),
`config/` (Cloth Config), `benchmark/` (datos, sin necesitar verlo), conectar
la tercera perilla de `PerformanceAutoTuner`.

**Pista B (de noche, conmigo presente):** todo lo de `render/` — hook de
render, shader de blend, hook de FOV/zoom, `AtmosphericPerspective`, testeo
cruzado real en los dos equipos de referencia.

**Si en una sesión de Pista A te encontrás con algo que en realidad
necesita verificación visual para saber si está bien, NO lo intentes
resolver a ciegas ni asumas que "probablemente esté bien".** Dejalo
compilando (aunque el resultado visual sea incierto), anotalo en
`NOTES.md` bajo "Pendiente de Pista B", y seguí con el siguiente ítem.

## Checklist de cierre de hito

Al terminar un hito completo (no una tarea chica):
1. `./gradlew build` completo.
2. `./gradlew test` completo.
3. Actualizar `NOTES.md` con qué quedó pendiente para Pista B, si algo.
4. Un commit por hito cerrado, mensaje claro (qué módulo, qué quedó
   funcional) — no un commit por archivo tocado.

## Convenciones ya establecidas (no las reinventes)

- **Versiones:** cada jar que se le entrega al usuario sube `mod_version` en
  `gradle.properties` y agrega su entrada en `CHANGELOG.md`. Nunca entregar
  dos jars distintos con el mismo número. Formato 0.MENOR.PARCHE: desde la
  0.25.2 (pedido del usuario) se sube el PARCHE por defecto, aunque haya
  funciones nuevas; el MENOR solo con cambios muy grandes.

- Nombres de clases, métodos y comentarios en español, siguiendo el estilo
  ya usado en el código existente.
- Cada clase de lógica pura (`core`, `generation` no-Minecraft, `config`)
  tiene su test correspondiente en `src/test/`.
- Los módulos `render`, `network`, `benchmark` tienen placeholders con
  TODOs detallados — completalos ahí, no empieces archivos nuevos para lo
  mismo.
- Formato binario, RLE, y estructura de octree: ya definidos en el
  documento de arquitectura, sección 5 — no cambiar sin razón documentada.
