#!/bin/sh
# Prints the code and test counts quoted in docs/STATE.md and docs/TESTING.md.
#
# Counting method:
# - Only files tracked by git count, so build output and local files never do.
# - Main lines: every line (blank lines and comments included) of the .kt files under a module's
#   src/main/. build-logic (the Gradle convention plugins) is left out; C++ and other languages are too.
# - Test lines: every line of the .kt files under src/test/ and src/androidTest/.
# - @Test count: lines whose first word is the @Test annotation (not @TestInstance and the like),
#   split into JVM tests (src/test/ outside bench), the benchmark harness's own tests (bench/src/test/)
#   and instrumentation tests (src/androidTest/). A parameterised test counts once.
# - Benchmark tasks: each bench/tasks/<suite>/<task>/task.json is one task. A task with
#   "tier": "hard" is in the hard tier; every other task is in the standard tier.
#
# Usage: tools/stats.sh (from anywhere inside the repository). Needs only git and awk.
set -eu

cd "$(git rev-parse --show-toplevel)"

# Sums the lines of the files named on stdin, one path per line.
count_lines() {
  awk '{ while ((getline line < $0) > 0) n++; close($0) } END { print n + 0 }'
}

# Counts the @Test annotations in the files named on stdin, one path per line.
count_tests() {
  awk '{
    while ((getline line < $0) > 0) if (line ~ /^[ \t]*@Test([^A-Za-z0-9_]|$)/) n++
    close($0)
  } END { print n + 0 }'
}

main_lines=$(git ls-files -- '*/src/main/*.kt' ':!build-logic/*' | count_lines)
test_lines=$(git ls-files -- '*/src/test/*.kt' '*/src/androidTest/*.kt' ':!build-logic/*' | count_lines)
jvm_tests=$(git ls-files -- '*/src/test/*.kt' ':!build-logic/*' ':!bench/*' | count_tests)
bench_tests=$(git ls-files -- 'bench/src/test/*.kt' | count_tests)
instr_tests=$(git ls-files -- '*/src/androidTest/*.kt' | count_tests)
all_tests=$((jvm_tests + bench_tests + instr_tests))

tasks=$(git ls-files -- 'bench/tasks/*/*/task.json' | awk 'END { print NR }')
hard_tasks=$(git ls-files -- 'bench/tasks/*/*/task.json' | awk '{
  while ((getline line < $0) > 0) if (line ~ /"tier"[ \t]*:[ \t]*"hard"/) { n++; break }
  close($0)
} END { print n + 0 }')
standard_tasks=$((tasks - hard_tasks))

modules=$(grep -c '^include(' settings.gradle.kts)

echo "Commit:                       $(git rev-parse --short HEAD)"
echo "Gradle modules:               $modules"
echo "Main Kotlin lines:            $main_lines"
echo "Test Kotlin lines:            $test_lines"
echo "@Test methods, all:           $all_tests"
echo "  JVM unit and screenshot:    $jvm_tests"
echo "  benchmark harness:          $bench_tests"
echo "  instrumentation:            $instr_tests"
echo "Benchmark tasks:              $tasks ($standard_tasks standard, $hard_tasks hard)"
