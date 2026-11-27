# Design decisions

A running log of choices made while building Tremor, and why.

## Build and packaging

- **Java 21, Maven, single fat jar.** The shaded jar is attached with the `all`
  classifier (`target/tremor-<version>-all.jar`), so the plain jar stays a normal
  library jar. Run with `java -jar target/tremor-*-all.jar`.
- **Version string** comes from a Maven-filtered `tremor.properties` resource, so
  `tremor --version` always matches the POM.
- **Stable dependency lines only.** JUnit 5 and AssertJ 3 are pinned to their latest
  stable releases even where newer milestones exist.

## Command line

- **Exit codes:** `0` safe, `1` breaks found, `2` Tremor error. Bad arguments are
  also `2`, which is picocli's default for usage errors.
- `--project` defaults to the current directory; `--format` defaults to `text` and
  is case-insensitive.
- `--dependency` takes `groupId:artifactId` only. The *current* version is always
  read from the project, never from the command line.

## Tests

- Tests tagged `@Tag("network")` reach Maven Central. They are **skipped by default**
  so `mvn verify` works offline and in CI. Run them with `mvn verify -Pnetwork`.
