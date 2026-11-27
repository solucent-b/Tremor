#!/usr/bin/env bash
# Runs Tremor for the GitHub Action. See action.yml for the inputs (passed as environment variables).
set -euo pipefail

JAR=$(ls "$GITHUB_ACTION_PATH"/target/tremor-*-all.jar | head -1)
tremor() { java -jar "$JAR" "$@"; }
output() { echo "$1=$2" >> "$GITHUB_OUTPUT"; }

DEP="${INPUT_DEPENDENCY}"
TO="${INPUT_TO}"
DIR=""
if [ -z "$DEP" ] || [ -z "$TO" ]; then
  DETECTED=false
  while IFS='=' read -r key value; do
    case "$key" in
      detected) DETECTED="$value" ;;
      dependency) DEP="$value" ;;
      to) TO="$value" ;;
      directory) DIR="$value" ;;
    esac
  done < <(tremor detect-upgrade --title "${PR_TITLE:-}")
  if [ "$DETECTED" != "true" ]; then
    echo "::notice::Tremor could not tell which dependency this pull request upgrades from its title. Skipping."
    output verdict skipped
    exit 0
  fi
fi
output dependency "$DEP"

PROJECT="${INPUT_PROJECT:-.}"
if [ "$PROJECT" = "." ] && [ -n "$DIR" ]; then
  PROJECT="${DIR#/}"
  PROJECT="${PROJECT:-.}"
fi

# Check the pull request's BASE commit: it still declares the old version and its code compiles against it.
WORK="$GITHUB_WORKSPACE"
if [ -n "${BASE_SHA:-}" ]; then
  WORK="$RUNNER_TEMP/tremor-base"
  git -C "$GITHUB_WORKSPACE" fetch --no-tags --depth=1 origin "$BASE_SHA"
  rm -rf "$WORK"
  git -C "$GITHUB_WORKSPACE" worktree add --detach "$WORK" "$BASE_SHA"
fi
cd "$WORK/$PROJECT"

echo "Compiling $(pwd) ..."
if [ -n "${INPUT_BUILD_COMMAND:-}" ]; then
  bash -c "$INPUT_BUILD_COMMAND"
elif [ -f pom.xml ]; then
  MVN=mvn; [ -x ./mvnw ] && MVN=./mvnw
  $MVN -B -q -DskipTests test-compile
elif [ -f build.gradle ] || [ -f build.gradle.kts ]; then
  GRADLE=gradle; [ -x ./gradlew ] && GRADLE=./gradlew
  $GRADLE -q classes testClasses
fi

OUT="$RUNNER_TEMP/tremor-report"
mkdir -p "$OUT"
set +e
tremor check --project . --dependency "$DEP" --to "$TO" --format json --output "$OUT/report.json" 2> "$OUT/stderr.txt"
CODE=$?
set -e

if [ "$CODE" -eq 2 ]; then
  VERDICT=error
  {
    echo "<!-- tremor-report -->"
    echo "❓ **Tremor:** could not check \`$DEP\` → $TO."
    echo
    echo '```'
    tail -n 5 "$OUT/stderr.txt"
    echo '```'
  } > "$OUT/comment.md"
else
  # Later runs reuse Tremor's cache, so these are quick.
  tremor check --project . --dependency "$DEP" --to "$TO" --format html --output "$OUT/report.html" 2>/dev/null || true
  tremor check --project . --dependency "$DEP" --to "$TO" --format markdown --output "$OUT/comment.md" 2>/dev/null || true
  VERDICT=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["verdict"])' "$OUT/report.json")
  output breaks "$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["summary"]["breaks"])' "$OUT/report.json")"
fi
output verdict "$VERDICT"

BODY="$(cat "$OUT/comment.md")"
if [ -n "${RUN_URL:-}" ]; then
  BODY="$BODY

Full report: download the **tremor-report** artifact from [this workflow run]($RUN_URL)."
fi
echo "$BODY" >> "$GITHUB_STEP_SUMMARY"

if [ "${INPUT_COMMENT:-true}" = "true" ] && [ -n "${PR_NUMBER:-}" ]; then
  EXISTING=$(gh api "repos/$REPOSITORY/issues/$PR_NUMBER/comments" --paginate \
    --jq '.[] | select(.body | contains("<!-- tremor-report -->")) | .id' | head -n 1)
  if [ -n "$EXISTING" ]; then
    gh api -X PATCH "repos/$REPOSITORY/issues/comments/$EXISTING" -f body="$BODY" > /dev/null
    echo "Updated the Tremor comment on #$PR_NUMBER."
  else
    gh api "repos/$REPOSITORY/issues/$PR_NUMBER/comments" -f body="$BODY" > /dev/null
    echo "Commented on #$PR_NUMBER."
  fi
fi
