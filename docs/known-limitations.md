# Known limitations

Tremor reads **compiled bytecode** and checks how each reference would link against the new
version of a library. That catches the breaks that crash at runtime (`NoSuchMethodError`,
`NoClassDefFoundError`, ...) and most compile errors. Some things it cannot see. When Tremor says
"safe", it means *no known break*, never *guaranteed safe*.

## Things Tremor cannot detect (misses)

| Case | Why | What to do |
|---|---|---|
| Reflection: `Class.forName("...")`, `getMethod("name")`, method names in strings | The name only exists as a string at runtime. | Search your code for reflection on the upgraded library. |
| Dependency injection and frameworks (Spring, Guice, CDI, Jackson annotations on your classes) | The framework links to the library at runtime, not your bytecode. | Run your integration tests after upgrading. |
| Behaviour changes inside a method | Same signature, different result. Bytecode can't show it. | Read the release notes. |
| Removed or changed **compile-time constants** (`static final` primitives and strings) | `javac` copies the value into your class, so there is no reference left to check. | Recompile after upgrading. |
| Generic type changes (`List<String>` to `List<Integer>`) | Generics are erased in bytecode; the descriptor is unchanged. | The compiler will catch these. |
| A class no longer implements an interface **and** your code uses it as that type | Tremor does not trace types through variables. It reports a **warning** once per file that uses the class instead. | Check the flagged files. |
| Annotation attribute changes (e.g. a removed `@Foo(bar=...)` element) | Only the annotation type itself is checked. | Recompile. |
| Resource files, `META-INF/services`, configuration keys | Only `.class` files are compared. | Read the release notes. |
| Classes only present under `META-INF/versions/N/` (multi-release jars) | Only the base classes of a multi-release jar are read. | Rarely matters; the base classes define the API. |
| Libraries that use the dependency without declaring it (e.g. `provided` scope) | Only libraries that declare the upgraded dependency in their POM are scanned. | Re-run Tremor on those libraries' upgrades. |

## Things that may be reported but are not real (false positives)

| Case | Why |
|---|---|
| A library that shades (bundles) the upgraded dependency **without** relocating it | Tremor skips references to classes the scanned jar contains itself, so this is usually handled. A jar that bundles only *part* of the dependency can still produce findings. |
| A break inside code your application never runs | Tremor checks all compiled code, including dead code and rarely used paths. A finding there is still real if that code ever runs. |
| Several findings on one line | When a whole class is removed, every member used on that line is reported. It is one fix, not several. |

## Heuristics worth knowing

- **New checked exceptions** are warnings only (they don't break at runtime). When the
  exception class lives in another jar (e.g. Jackson's `JsonProcessingException` is in
  `jackson-core`), Tremor cannot tell whether it is narrower than what was declared before. So it
  only warns if the method declared no checked exceptions before.
  *Found by the Jackson 2.9 → 2.17 real-world test, which used to report `readValue` as throwing
  something new.*
- **"It may have moved" hints** look for a public static method with the same name and parameters
  in another class, or a class with the same simple name in another package, preferring the
  same package. *Found by the Guava 20 → 33 test: `Objects.toStringHelper` moved to
  `MoreObjects.toStringHelper`.*
- **Hierarchies that leave the library**: if a class extends something from a third jar, Tremor
  cannot see inherited members there. It skips those references rather than guess.
- **Version mediation risk** is a rule of thumb. A downgrade (a library gets an older version
  than it declared) is always "risky". A different major version is "risky" unless Tremor
  scanned that library's bytecode and found nothing.

## Build-file reading

- Maven: profiles are ignored. Parent POMs and imported BOMs are fetched from Maven Central plus
  any `--repository` URLs, but **not** from `<repositories>` declared inside POMs.
- Version ranges are passed to Maven Resolver as-is. The "current" version is whatever it
  picks today.
- Gradle: see the Gradle section of the README.
