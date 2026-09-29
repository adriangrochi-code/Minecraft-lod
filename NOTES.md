# Notas de trabajo

Bitácora viva. Claude Code anota acá (ver CLAUDE.md, reglas 4 y 7):
- Mejoras notadas en módulos ya cerrados, sin tocarlos de una.
- Errores que se repitieron 2+ veces de la misma forma, con la hipótesis.
- Cosas que necesitan verificación visual (Pista B) y quedaron sin resolver
  en una sesión de Pista A.

---

## Pendiente de Pista B

(vacío por ahora)

## Errores recurrentes / bloqueos

- **Sesión cloud (2026-09-29): `./gradlew build` no puede bajar NeoForge.**
  El proxy de red del entorno cloud devuelve 403 para `maven.neoforged.net`
  (también bloquea `libraries.minecraft.net` y `piston-meta.mojang.com`).
  La configuración de Gradle en sí evalúa bien: falla recién en
  `createMinecraftArtifacts` al resolver `neoform-runtime`. Solución: permitir
  esos dominios en la política de red del entorno, o compilar en local.
  Mientras tanto, la lógica pura se validó compilando con `javac` + JUnit
  standalone: los 57 tests pasan sin cambios.
- `neo_version=21.1.77` en `gradle.properties` es una versión conocida de
  1.21.1 pero no se pudo confirmar cuál es la última; subirla al compilar.

## Mejoras notadas, no aplicadas todavía

- El build pasó de NeoGradle userdev a ModDevGradle 2.0.148 (plugin oficial
  actual del MDK). `neoforge.mods.toml` se movió a `src/main/templates/` y se
  expande con `generateModMetadata`: antes se empaquetaba con los `${...}`
  literales y el mod no habría cargado.
