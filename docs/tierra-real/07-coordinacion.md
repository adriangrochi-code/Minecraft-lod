# 07 — Coordinación con la otra sesión

- **Rama propia:** `claude/tierra-real` (o la que asigne el usuario), creada
  desde la rama actual del LOD.
- **Versión:** no subir `mod_version` ni entregar jars hasta juntar las
  ramas (regla de `CLAUDE.md`: nunca dos jars distintos con el mismo número).
  Para probar, jars locales sin entregar.
- **Archivos compartidos** (tocar lo mínimo, en bloques propios):
  - `config/ConfigLod.java`: sección propia `[tierra]`;
  - `MinecraftLodMod.java`: una línea de registro;
  - `CHANGELOG.md`: una sola entrada al juntar;
  - `arquitectura-minecraft-lod-mod.md`: sección 33 nueva (no editar las otras);
  - `NOTES.md`: subsección propia.
- **Puntos de contacto con el código de la otra sesión** (no reescribir,
  solo enganchar): `generation/GeneradorAproximado` (atajo `FuenteAltura`),
  `core/HorizonteCurvo` (radio), `cubico/GeneracionVertical` (superficie
  exacta), `ConfigLod` (valores por defecto según tipo de mundo).
- **Paquete nuevo `tierra/`**: autorizado por el usuario para este trabajo.
- Si algo del LOD/cubico necesita cambiar de verdad para esto, anotarlo en
  `NOTES.md` ("Pedido a la sesión del LOD") en vez de reescribirlo.
