Trabajá en una rama nueva `claude/tierra-real`, creada desde la rama actual
del mod. Leé primero `CLAUDE.md`, `arquitectura-minecraft-lod-mod.md`
(secciones 25.7, 29 a 32) y todo `docs/tierra-real/` en orden (empieza por
su README). Ahí están las decisiones ya tomadas para un tipo de mundo
aparte, "Tierra real" (nunca por defecto), a escala 1:8 (1:6 opcional), con
variante cilíndrica y variante "tierra plana" con farlands congeladas en el
borde, integrado con el LOD y con cubic chunks del mod.

Arrancá por el hito H0 de `06-hitos.md`: verificá licencias y tamaños de los
datos en las páginas oficiales, corregí `02-datos.md` si algo no coincide, y
escribí la sección 33 de la arquitectura con las decisiones. Después seguí
con H1 y H2 (lógica pura con tests). Podés crear el paquete `tierra/`. No
subas `mod_version` ni entregues jars hasta que juntemos las ramas;
seguí `07-coordinacion.md`.
