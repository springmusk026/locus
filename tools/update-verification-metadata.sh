#!/usr/bin/env bash
# Regenerates gradle/verification-metadata.xml, the SHA-256 of every dependency, plugin and
# build-logic artifact Gradle may download. Run it from the repository root after changing a
# dependency or plugin version (CONTRIBUTING.md, "Dependency verification").
#
# It starts from an empty file, so checksums of versions no longer used drop out, then runs every
# Gradle task CI and the release workflow run, plus resolveAllForVerification
# (gradle/verification/resolve-all.init.gradle) for the rest. Review the diff before committing it:
# each new checksum is a download you're vouching for.
set -euo pipefail

cd "$(dirname "$0")/.."
FILE=gradle/verification-metadata.xml

# An empty Gradle user home, as on CI: with a warm cache Gradle can reuse some metadata (a plugin's
# BOM from the plugin portal, say) without recording it, and CI then fails on the missing entry.
# VERIFICATION_GRADLE_USER_HOME keeps one between runs if you need to.
if [ -n "${VERIFICATION_GRADLE_USER_HOME:-}" ]; then
  export GRADLE_USER_HOME="$VERIFICATION_GRADLE_USER_HOME"
else
  GRADLE_USER_HOME=$(mktemp -d "${TMPDIR:-/tmp}/locus-verification.XXXXXX")
  export GRADLE_USER_HOME
  trap 'rm -rf "$GRADLE_USER_HOME"' EXIT
fi

cat > "$FILE" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<verification-metadata xmlns="https://schema.gradle.org/dependency-verification" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="https://schema.gradle.org/dependency-verification https://schema.gradle.org/dependency-verification/dependency-verification-1.4.xsd">
   <configuration>
      <verify-metadata>true</verify-metadata>
      <verify-signatures>false</verify-signatures>
      <!-- Sources and javadoc are only downloaded by IDEs, never on a build classpath. -->
      <trusted-artifacts>
         <trust file=".*-javadoc[.]jar" regex="true"/>
         <trust file=".*-sources[.]jar" regex="true"/>
      </trusted-artifacts>
   </configuration>
   <components/>
</verification-metadata>
XML

# GRADLE overrides the Gradle command (default ./gradlew), e.g. a wrapper that queues builds.
# --no-configuration-cache: a configuration-cache hit resolves nothing, so nothing would be recorded.
# -Plocus.unsigned=true: the release workflow's build (no signing config).
${GRADLE:-./gradlew} --no-daemon --no-configuration-cache --write-verification-metadata sha256 \
  -I gradle/verification/resolve-all.init.gradle -Plocus.unsigned=true \
  help resolveAllForVerification \
  testDebugUnitTest :core:agent:test :core:llm:test :core:model:test \
  :koverHtmlReportUnit :koverXmlReportUnit lintDebug \
  :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest \
  "$@"

# aapt2 is per host OS and the build above fetched only this machine's. Add the other two.
AAPT2=$(sed -nE 's/.*artifact name="aapt2-(.+)-(osx|linux|windows)\.jar".*/\1/p' "$FILE" | sort -u | paste -sd, -)
if [ -z "$AAPT2" ]; then
  echo "No aapt2 in $FILE; did the build above run?" >&2
  exit 1
fi
${GRADLE:-./gradlew} --no-daemon --no-configuration-cache --write-verification-metadata sha256 \
  -I gradle/verification/resolve-all.init.gradle "-Plocus.aapt2Versions=$AAPT2" resolveAapt2ForAllHosts

echo "Updated $FILE. Check it with: ./gradlew help (verification is on for every build)."
