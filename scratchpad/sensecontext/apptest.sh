#!/usr/bin/env bash
# Compile and run an :app-module JUnit test that imports NO Android, against the whole core.
#
#   ./apptest.sh app/src/test/.../HoldCapabilityTest.kt app/src/main/.../HoldCapability.kt
#
# ⚠️ The package is derived from each test file rather than hardcoded — the coretest runner assumes
# dev.mascwa.pulse.core.telemetry, and an app-module test is somewhere else entirely.
# ⚠️ Same jar discipline as scratchpad/coretest/run.sh, and for the same reason: omit one and kotlinc
# dies before reading a line, and a grep for "error:" then finds nothing — which looks like a pass.
set -euo pipefail
cd "$(dirname "$0")/../.."

KC=/opt/gradle-8.14.3/lib/kotlin-compiler-embeddable-2.0.21.jar
STD=/opt/gradle-8.14.3/lib/kotlin-stdlib-2.0.21.jar
TRV=/opt/gradle-8.14.3/lib/trove4j-1.0.20200330.jar
ANN=/opt/gradle-8.14.3/lib/annotations-24.0.1.jar
GC="$HOME/.gradle/caches/modules-2/files-2.1"
COR=$(find "$GC" -name 'kotlinx-coroutines-core-jvm-*.jar' | head -1)
JU=$(find "$GC" -name 'junit-4.13.2.jar' | head -1)
HC=$(find "$GC" -name 'hamcrest-core-1.3.jar' | head -1)
JSOUP=$(find "$GC" -name 'jsoup-*.jar' | head -1)
SER=$(find "$GC/org.jetbrains.kotlinx" -name 'kotlinx-serialization-core-jvm-*.jar' | head -1)
SERJ=$(find "$GC/org.jetbrains.kotlinx" -name 'kotlinx-serialization-json-jvm-*.jar' | head -1)
SPLUG=$(find "$HOME/.gradle/caches/modules-2" -name 'kotlin-serialization-compiler-plugin-embeddable-*.jar' | head -1)
for j in "$KC" "$STD" "$TRV" "$ANN" "$COR" "$JU" "$HC" "$JSOUP" "$SER" "$SERJ" "$SPLUG"; do
  [ -n "$j" ] && [ -f "$j" ] || { echo "missing a required jar: '$j'"; exit 2; }
done
[ $# -ge 1 ] || { echo "usage: apptest.sh <Test.kt> [src.kt ...]"; exit 2; }

CORE=$(grep -rLE '^import android[.x]?' core/telemetry/src/main --include='*.kt' || true)
[ -n "$CORE" ] || { echo "found no core sources"; exit 2; }
OUT=$(mktemp -d)
TARGET_CP="$STD:$JSOUP:$JU:$HC:$SER:$SERJ"
java -cp "$KC:$STD:$TRV:$ANN:$COR" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -nowarn -Xplugin="$SPLUG" -d "$OUT" -cp "$TARGET_CP" $CORE "$@" 2>&1 |
  grep -Ev '^(warning|info):' || true
[ -n "$(find "$OUT" -name '*.class' -print -quit)" ] || { echo "NOTHING COMPILED — see above"; exit 3; }

for t in "$@"; do
  case "$t" in *Test.kt) ;; *) continue ;; esac
  PKG=$(sed -nE 's/^package +([A-Za-z0-9_.]+).*/\1/p' "$t" | head -1)
  [ -n "$PKG" ] || { echo "no package line in $t"; exit 2; }
  java -cp "$OUT:$TARGET_CP" org.junit.runner.JUnitCore \
    "$PKG.$(basename "$t" .kt)" 2>&1 | grep -v JAVA_TOOL_OPTIONS
done
