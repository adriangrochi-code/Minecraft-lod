# 08 — Cómo compilar la rama `claude/tierra-real` (prioridad 1)

Verificado el 2026-10-01 desde un **clon limpio de GitHub**: con estos pasos
`./gradlew compileJava` pasa y los 25 tests de `tierra/` dan 0 fallos.

## Por qué no compilaba

El `.gitignore` tenía `build/`, que además de la carpeta de salida de Gradle
ignoraba **cualquier paquete llamado `build`**: los 47 archivos de
`src/main/java/com/example/minecraftlodmod/vulkanmod/render/chunk/build/`
nunca estuvieron en GitHub ("package ...vulkanmod.render.chunk.build does
not exist"). Arreglado en `claude/acceso-extra-xhb3yf`: rutas ancladas a la
raíz (`/build/`, `/run/`, `/.gradle/`, `/runs/`, `/out/`) y la carpeta subida.

## Pasos (en la sesión de Tierra real)

```
git fetch origin claude/acceso-extra-xhb3yf
git merge origin/claude/acceso-extra-xhb3yf
```

1. **Conflicto esperado, solo en `arquitectura-minecraft-lod-mod.md`:** las
   dos ramas tocaron el final del archivo. Resolver quedándose con **las
   dos cosas**: la sección 32 como está en `claude/acceso-extra-xhb3yf`
   (tiene los puntos 0.26.4 y 0.26.5 y el "Pendiente" nuevo) y, después, la
   sección 33 de Tierra real completa.
2. `CHANGELOG.md` y `gradle.properties` entran sin conflicto (versión 0.26.5
   del LOD). Sigue valiendo: Tierra real no sube `mod_version` hasta juntar.
3. **Arreglo de un test** (`AlturaTierraTest`): en los tests de NeoForge los
   recursos viven en un sistema de archivos del cargador de módulos que no
   abre `FileChannel` (`UnsupportedOperationException` en
   `LectorLodt.abrir`). Copiar el recurso a un archivo temporal:

```diff
diff --git a/src/test/java/com/example/minecraftlodmod/tierra/AlturaTierraTest.java b/src/test/java/com/example/minecraftlodmod/tierra/AlturaTierraTest.java
index 49f7b91..6df346b 100644
--- a/src/test/java/com/example/minecraftlodmod/tierra/AlturaTierraTest.java
+++ b/src/test/java/com/example/minecraftlodmod/tierra/AlturaTierraTest.java
@@ -27,7 +27,12 @@ class AlturaTierraTest {
 
     @BeforeAll
     static void abrir() throws IOException, URISyntaxException {
-        Path p = Path.of(AlturaTierraTest.class.getResource("/tierra/himalaya-bengala-30s.lodt").toURI());
+        // En los tests de NeoForge el recurso vive en un sistema de archivos que no abre FileChannel: copia temporal.
+        Path p = java.nio.file.Files.createTempFile("himalaya", ".lodt");
+        p.toFile().deleteOnExit();
+        try (var in = AlturaTierraTest.class.getResourceAsStream("/tierra/himalaya-bengala-30s.lodt")) {
+            java.nio.file.Files.copy(in, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
+        }
         lector = LectorLodt.abrir(p, 64L << 20);
         fuente = new FuenteTierra(lector);
     }
```

   Mismo cuidado en cualquier test futuro que abra un recurso con
   `FileChannel` (o `Files.newByteChannel`): siempre copia temporal.
4. Comprobar:

```
./gradlew compileJava
./gradlew test --tests "com.example.minecraftlodmod.tierra.*"
```

## Red que necesita el build (si aparece un 403 del proxy)

Gradle baja todo de: `maven.neoforged.net` (NeoForge, MDG), `piston-meta.mojang.com`,
`piston-data.mojang.com`, `libraries.minecraft.net`, `resources.download.minecraft.net`
(Minecraft y assets), `repo.maven.apache.org` (LWJGL, JUnit), `api.modrinth.com`
(Iris, solo de compilación) y `maven.su5ed.dev` (Forgified Fabric API del VulkanMod
integrado). Si alguno da 403, es la política de red de la sesión: el usuario la
amplía en la configuración del entorno (no se puede arreglar desde el código).

## Versiones

Java 21, NeoForge 21.1.252 (Minecraft 1.21.1), Gradle por el wrapper del
repo (`./gradlew`, no un Gradle instalado aparte).
