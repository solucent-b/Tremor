# GitHub Action

Tremor ships a reusable action that runs on Dependabot and Renovate pull requests. It works out
which dependency the PR upgrades, checks the upgrade, and posts **one** comment on the PR, which
it updates on every new push instead of adding more.

> ✅ **Tremor:** upgrading `guava` 32.1.0-jre → 33.0.0-jre is safe for this project.

> ⚠️ **Tremor:** 3 things will break when upgrading `guava` 20.0 → 33.0.0-jre.
> - `src/main/java/com/example/UserService.java:19` calls `Objects.toStringHelper(Object)`, which was removed — ...

The full HTML, JSON and Markdown reports are uploaded as the **tremor-report** workflow artifact.

## Setup

Add [`docs/examples/tremor-pr.yml`](examples/tremor-pr.yml) to your project as
`.github/workflows/tremor.yml`:

```yaml
name: Tremor
on:
  pull_request:
permissions:
  contents: read
  pull-requests: write
jobs:
  tremor:
    if: github.actor == 'dependabot[bot]' || github.actor == 'renovate[bot]'
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: solucent-b/Tremor@v0.1.0
```

`pull-requests: write` is needed for the comment. GitHub gives Dependabot PRs a read-only token
unless the workflow asks for more with `permissions`, as above.

## How it works

1. Reads the PR title, e.g. `Bump com.google.guava:guava from 32.1.0-jre to 33.0.0-jre in /backend`
   (Dependabot) or `Update dependency com.google.guava:guava to v33.0.0-jre` (Renovate). Grouped
   updates ("Bump the maven group with 3 updates") are skipped; set `dependency` and `to` instead.
2. Checks out the PR's **base** commit in a separate worktree. It still has the old version,
   and its code compiles against it.
3. Compiles it (`mvn test-compile`, or `./gradlew classes testClasses`; override with `build-command`).
4. Runs `tremor check` and writes JSON, HTML and Markdown reports.
5. Posts or updates the comment (found by a hidden `<!-- tremor-report -->` marker), writes the
   same text to the job summary, and uploads the reports.
6. Fails the job when there are breaks, unless `fail-on-breaks: 'false'`.

## Inputs

| Input | Default | Meaning |
|---|---|---|
| `project` | `.` | Project directory. If Dependabot's title says `in /backend`, that is used. |
| `dependency` | from the PR title | `groupId:artifactId` to check. |
| `to` | from the PR title | Version to upgrade to. |
| `build-command` | auto | Command that compiles main and test classes. |
| `fail-on-breaks` | `true` | Fail the job when there are breaks (or Tremor itself errors). |
| `comment` | `true` | Post/update the PR comment. |
| `java-version` | `21` | JDK used to build the project and run Tremor. |
| `github-token` | `github.token` | Token for the comment. |

## Outputs

`verdict` (`safe`, `warnings`, `breaks`, `skipped`, `error`), `breaks` (a number) and `dependency`.

## Known gaps

- Renovate titles that only give a major version (`to v2`) are passed on as-is. Tremor then
  reports that version `2` does not exist and lists the real ones. Set `to` explicitly in that case.
- The action builds Tremor from source on each run (about a minute). A prebuilt release jar
  will come later.
