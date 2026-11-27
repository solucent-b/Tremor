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

## Reading projects

- **Lightweight POM reader, not the full Maven model builder**, for the project itself. It
  handles properties, `<dependencyManagement>`, parents (on disk, or downloaded) and imported
  BOMs, which covers almost every real project without pulling in Maven's whole plugin world.
  Published POMs (libraries in the tree) are read by Maven Resolver's own model builder.
- **Gradle is read statically** (build files, `gradle.properties`, version catalog) instead of
  running Gradle or using the Tooling API. That's fast, needs no Gradle install, and never
  executes build scripts. The trade-off is documented in `docs/gradle.md`.
- `--repository` and `--no-central` exist so Tremor works behind a company mirror, and so the
  test suite runs fully offline against a throwaway file repository.

## Resolving

- **Maven Resolver 1.9 + `maven-resolver-supplier`** (no Sisu/Guice container). The local
  repository is Tremor's own `~/.tremor/cache/repository`, never `~/.m2`, so Tremor never
  changes the user's Maven state. `$TREMOR_CACHE` or `--cache-dir` move it.
- The tree is collected in **verbose mode**, so losing nodes and pre-management versions are kept.
  That is what makes the version-mediation report possible.
- "After the upgrade" is modelled as the user would do it: the new version is set on the direct
  dependency **and** added to dependency management, so it wins everywhere.
- HTTP proxies come from the standard `https.proxyHost`/`https.proxyPort` JVM properties.

## Analysis

- **Linkage-based matching.** Each reference in the scanned bytecode is resolved against the new
  version with the JVM's own lookup rules (class, superclasses, then interfaces). The API diff
  is then used only to *explain* a failure. This is why "method moved to a superclass" is
  correctly not a break, and why inherited calls through the user's own subclasses are checked.
- Classes outside the library and the JDK are "unknown": Tremor skips references it cannot
  decide rather than guessing. False negatives are preferred over noisy false positives.
- A scanned jar that **contains its own copy** of a referenced class (shading without
  relocation) is not reported. Relocated shading never matches in the first place.
- Each other library is compared against **the version it declared** (likely what it was compiled
  against), not the project's current version.
- Mediation risk: downgrade means risky; a different major version means risky unless Tremor
  scanned that library and found nothing; anything else is low.
- **Removed interfaces** are a warning (once per file), because proving the break needs type
  tracking through variables.

## Reports

- Exit codes `0` safe, `1` breaks (including a library that will break), `2` Tremor error.
  Warnings never fail the build.
- The terminal report uses colour only on a real terminal, and honours `NO_COLOR` and `--no-color`.
- JSON is built by hand as a tree (not by serializing internal classes), so the documented schema
  stays stable while the code changes.
- The HTML report is one file with inline CSS and no JavaScript (`<details>` for collapsing).
- A Markdown format exists for PR comments; the GitHub Action uses it.

## Speed

- Parsed API models and scan results are cached on disk under `~/.tremor/cache/analysis`, keyed
  by the SHA-256 of the jar. Java serialization with an `ObjectInputFilter` that only allows
  Tremor's own classes and basic JDK types. Corrupt entries are rebuilt.
- Libraries are downloaded one at a time (polite to the repository) and scanned in parallel.
- Measured: a warm check of a project with 160 dependent libraries takes well under a second
  (`LargeProjectSpeedTest`).

## GitHub Action

- A **composite** action that builds Tremor from source, so `uses: solucent-b/Tremor@<tag>`
  always runs the code at that tag.
- It checks the PR's **base** commit, because the head already has the new version and may not
  compile against it.
- One comment per PR, found again through a hidden `<!-- tremor-report -->` marker and edited
  in place.
