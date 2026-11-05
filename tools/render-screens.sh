#!/usr/bin/env bash
# Renders every screen, sheet and component with the screenshot tests into a folder for a person to
# look at (docs/design/design-system.md, "Review gate"). Renders at 2x by default so text is easy to read.
# Nothing here is committed: the default folder is under build/, and any folder you pick stays local.
#
#   tools/render-screens.sh [out-dir] [density] [gradle options…]
#
# out-dir defaults to build/screens; density is a Robolectric qualifier (mdpi, hdpi, xhdpi, xxhdpi),
# default xhdpi. Each module gets a subfolder (feature-chat/, core-designsystem/ …), and each screen
# five files: <name>_360-light, _360-dark, _411-light, _411-dark and _360-light-font200.
#
# GRADLE overrides the Gradle command (default ./gradlew), e.g. a wrapper that queues builds.
set -euo pipefail

cd "$(dirname "$0")/.."
OUT="${1:-build/screens}"
DENSITY="${2:-xhdpi}"
# Anything after the two goes to Gradle (--continue, --offline …).
shift $(( $# < 2 ? $# : 2 ))
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"

# Every module with screenshot tests (a *Screenshots.kt under src/test).
TASKS=()
while IFS= read -r module; do
  # Task options apply to the task before them. --rerun: a review render must draw even when the
  # tests are up to date.
  TASKS+=(":${module//\//:}:testDebugUnitTest" --rerun --tests '*Screenshots*')
done < <(find app core feature -path '*/src/test/*' -name '*Screenshots.kt' -not -path '*/build/*' \
  | sed -E 's#/src/test/.*##' | sort -u)

${GRADLE:-./gradlew} "${TASKS[@]}" -Proborazzi.test.record=true \
  "-Plocus.screenshots.dir=$OUT" "-Plocus.screenshots.density=$DENSITY" "$@" || {
  echo "Some tests failed; the renders that finished are in $OUT." >&2
}

echo "Rendered $(find "$OUT" -name '*.png' | wc -l | tr -d ' ') images into $OUT"
