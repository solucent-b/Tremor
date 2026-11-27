# JSON report format

`tremor check ... --format json` prints a single JSON object. This page documents
**schema version 1**.

## Stability promise

- `schemaVersion` changes only when a field is renamed, removed or changes meaning.
- Within a schema version, new fields may be **added**. Ignore fields you do not know.
- Enum values (`verdict`, `severity`, `kind`, `risk`) may gain new members. Treat unknown
  values as "something Tremor learned to detect later".

## Example

```json
{
  "schemaVersion": 1,
  "tool": { "name": "tremor", "version": "0.1.0" },
  "project": { "groupId": "com.example", "artifactId": "app", "version": "1.0", "path": "/work/app" },
  "dependency": { "groupId": "com.google.guava", "artifactId": "guava", "from": "20.0", "to": "33.0.0-jre" },
  "verdict": "breaks",
  "summary": {
    "breaks": 2, "warnings": 1, "files": 1, "libraries": 1, "riskyVersionChanges": 0,
    "text": "2 breaks, 1 warning across 1 file and 1 library"
  },
  "breaks": [
    {
      "severity": "BREAKING",
      "kind": "REMOVED_METHOD",
      "origin": { "type": "project" },
      "file": "src/main/java/com/example/UserService.java",
      "line": 84,
      "className": "com.example.UserService",
      "member": "load",
      "action": "calls Cache.getIfPresent(Object)",
      "reason": "was removed",
      "consequence": "NoSuchMethodError at runtime",
      "hint": "Maybe use Cache.getIfPresentOrNull(Object) instead.",
      "message": "src/main/java/com/example/UserService.java:84 calls Cache.getIfPresent(Object), which was removed"
    }
  ],
  "warnings": [],
  "versionChanges": [
    {
      "library": { "groupId": "org.y", "artifactId": "library-y", "version": "1.4.2" },
      "dependency": "com.google.guava:guava",
      "requested": "31.1-jre",
      "resolved": "33.0.0-jre",
      "risk": "BREAKS",
      "explanation": "library-y 1.4.2 asks for guava 31.1-jre but will get 33.0.0-jre, ..."
    }
  ],
  "apiChanges": { "breaking": 57, "warnings": 140 },
  "scannedLibraries": [ { "groupId": "org.y", "artifactId": "library-y", "version": "1.4.2" } ],
  "notes": [ "Tremor reads compiled bytecode. It cannot see reflection ..." ]
}
```

## Fields

| Field | Type | Meaning |
|---|---|---|
| `schemaVersion` | int | Always `1` for this format. |
| `tool.name`, `tool.version` | string | Tremor's own name and version. |
| `project` | object | The checked project's `groupId`, `artifactId`, `version` and absolute `path`. |
| `dependency` | object | The upgraded dependency: `groupId`, `artifactId`, `from` (the version used today, as resolved in the tree) and `to`. |
| `verdict` | string | `safe`, `warnings` or `breaks`. `breaks` matches exit code `1`. |
| `summary` | object | Counts: `breaks`, `warnings`, `files` (project files with findings), `libraries` (libraries with findings or risky version changes), `riskyVersionChanges`, and the human `text` line. |
| `breaks` | array of finding | Places that will fail at runtime or no longer compile. |
| `warnings` | array of finding | Places worth a look: deprecations, new checked exceptions, overrides the library no longer calls. |
| `versionChanges` | array | Libraries that get a different version than they declared because of the upgrade (Maven "nearest wins"). |
| `apiChanges` | object | Totals of `breaking` and `warnings`-level API changes in the library, used or not. |
| `scannedLibraries` | array of GAV | Other libraries in the tree that use the dependency and were scanned. |
| `notes` | array of string | Caveats about this analysis (unreadable classes, unknown parent POMs, the limits of static analysis). |

### Finding

| Field | Type | Meaning |
|---|---|---|
| `severity` | string | `BREAKING` or `WARNING`. |
| `kind` | string | What changed. See the list below. |
| `origin.type` | string | `project` (your code) or `library` (a jar in your tree; then `groupId`, `artifactId` and `version` are set). |
| `file` | string | For your project, the source file relative to the project when it can be found (e.g. `src/main/java/...`), otherwise the package path. For libraries, the package path inside the jar. |
| `line` | int | Line number, or `0` if the class was compiled without line numbers. |
| `className` | string | Fully qualified class containing the usage. |
| `member` | string or null | Method or field containing the usage. |
| `action` | string | What the code does, e.g. `calls Cache.getIfPresent(Object)`. |
| `reason` | string | What changed, e.g. `was removed`. |
| `consequence` | string or null | What happens at runtime (null for warnings). |
| `hint` | string or null | Suggested fix, when Tremor has one. |
| `message` | string | `file:line action, which reason`, ready to print. |

### `kind` values

`REMOVED_CLASS`, `CLASS_LESS_VISIBLE`, `CLASS_NOW_FINAL`, `CLASS_NOW_ABSTRACT`,
`CLASS_KIND_CHANGED`, `REMOVED_SUPERTYPE`, `REMOVED_METHOD`, `REMOVED_CONSTRUCTOR`,
`PARAMETERS_CHANGED`, `RETURN_TYPE_CHANGED`, `METHOD_LESS_VISIBLE`, `METHOD_NOW_FINAL`,
`METHOD_NOW_ABSTRACT`, `METHOD_STATIC_CHANGED`, `ABSTRACT_METHOD_ADDED`, `REMOVED_FIELD`,
`FIELD_TYPE_CHANGED`, `FIELD_LESS_VISIBLE`, `FIELD_NOW_FINAL`, `FIELD_STATIC_CHANGED`,
`NEW_CHECKED_EXCEPTION`, `DEPRECATED`.

### `risk` values

- `BREAKS`: the library's own bytecode uses something the resolved version no longer has.
- `RISKY`: the library gets an **older** version than it declared, or a different major
  version that Tremor did not scan.
- `LOW`: same major version, or Tremor scanned the library and found no break.
