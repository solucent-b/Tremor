# Gradle support

Tremor reads Gradle builds **without running Gradle**. That keeps it fast and safe (no build
scripts are executed), but it only understands dependencies written in the usual ways.

## What works

- `build.gradle` (Groovy) and `build.gradle.kts` (Kotlin DSL) in the `--project` directory.
- String notation: `implementation 'g:a:v'`, `implementation("g:a:v")`, `"g:a:$version"`,
  `"g:a:${version}"`, `'g:a:' + version`.
- Map notation: `implementation group: 'g', name: 'a', version: 'v'` and the Kotlin
  `implementation(group = "g", name = "a", version = "v")`.
- Version variables from `gradle.properties`, `ext { ... }`, `def x = "..."`, `val x = "..."`.
- Version catalogs: `gradle/libs.versions.toml` (in the project or its parent directory),
  `[versions]` and `[libraries]`, used as `libs.some.alias`.
- BOMs: `platform(...)` and `enforcedPlatform(...)` fill in versions of dependencies declared
  without one.
- Configurations: `implementation`, `api`, `compileOnly`, `runtimeOnly`, and their `test*` versions.
- Compiled classes are read from `build/classes/<language>/<sourceSet>` (Java, Kotlin, Groovy, Scala).
  Run `./gradlew classes testClasses` first.

## Not supported yet

- **Running Gradle.** Dependencies added by plugins, convention plugins (`buildSrc`,
  `build-logic`), loops, or other code in the build script are not seen.
- **Multi-project builds as a whole.** Point `--project` at one sub-project at a time. Its
  dependencies on sibling projects (`project(':core')`) are ignored.
- Version catalog **bundles** (`libs.bundles.*`). Tremor prints a note when it sees one.
- Dynamic versions (`1.+`, `latest.release`) and ranges. Tremor prints a note and skips them.
- `constraints { }`, `resolutionStrategy`, dependency substitution and capabilities.
- **Gradle's conflict resolution.** Gradle picks the *highest* requested version, while Tremor
  builds the tree with Maven's rules (*nearest* wins). The break analysis is the same either way,
  but the "version changes" section may differ from what Gradle actually resolves.

If Tremor misreads your build, run `./gradlew dependencies --configuration runtimeClasspath` and
compare. Please open an issue with the snippet it missed.
