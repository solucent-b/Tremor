# Contributing to Tremor

Thanks for helping! The most valuable contributions are **real upgrades where Tremor got it
wrong**: a break it missed, or one it reported that isn't real.

## Build and test

You need Java 21+ and Maven 3.9+.

```bash
mvn verify                 # unit, fixture and end-to-end tests; fully offline
mvn verify -Pnetwork       # also the real-world tests against Maven Central (Guava, Jackson, Commons IO)
mvn package -DskipTests    # just build target/tremor-<version>-all.jar
```

Every commit must pass `mvn verify`.

## Project layout

| Package | What it does |
|---|---|
| `dev.tremor.cli` | picocli commands, report output, PR-title parsing |
| `dev.tremor.project` | reads `pom.xml`, Gradle builds and version catalogs |
| `dev.tremor.resolve` | Maven Resolver: dependency trees and downloads |
| `dev.tremor.bytecode` | ASM API model and JVM-style member lookup |
| `dev.tremor.diff` | compares two API models |
| `dev.tremor.scan` | finds every reference in compiled code, with file and line |
| `dev.tremor.analysis` | links references against the new version; library and mediation checks |
| `dev.tremor.cache` | on-disk cache keyed by jar hash |
| `dev.tremor.report` | text, JSON, HTML and Markdown reports |

## Adding a fixture (the best way to report a bug)

Fixtures live in `src/test/resources/fixtures/<name>/` and run automatically:

```
<name>/
  v1/lib/Thing.java          the library before
  v2/lib/Thing.java          the library after
  app/app/App.java           code compiled against v1
  dependents/<artifact>/...  (optional) other libraries built against v1
  expected.txt               the findings Tremor must report, nothing more, nothing less
```

`expected.txt` has one line per finding:

```
break REMOVED_METHOD project | App.java:5 calls Thing.gone(), which was removed
warning DEPRECATED project | App.java:6 calls Thing.old(), which is now deprecated
version-change BREAKS library-y
```

Run `mvn test -Dtest=FixtureSuiteTest` and the failure message shows what Tremor actually found.

## Style

- Small classes, clear names, Javadoc where it helps.
- Messages are for beginners: say **what** broke, **where**, and **what to do**.
- Never crash on one odd class file. Record it, skip it, carry on.
- Prefer missing a break to inventing one, and document the gap in `docs/known-limitations.md`.
- Conventional Commits (`feat:`, `fix:`, `test:`, `docs:`, ...), one change per commit.
