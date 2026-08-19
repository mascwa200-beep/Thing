#!/usr/bin/env bash
# Compile and run the SHIPPED Readability core over a directory of real fetched pages.
#
# ⚠️ The compiler's OWN -cp needs stdlib + trove4j + annotations + coroutines. Omit one and kotlinc
# dies before it compiles a line, which looks exactly like a clean pass — so every jar is asserted.
# The TARGET -cp needs jsoup, which is the module's one third-party dependency.
#
#   ./run.sh <pages-dir>            # run the probe
#   ./run.sh <pages-dir> <Test.kt>  # run a JUnit test instead
set -euo pipefail
cd "$(dirname "$0")/../.."

KC=/opt/gradle-8.14.3/lib/kotlin-compiler-embeddable-2.0.21.jar
STD=/opt/gradle-8.14.3/lib/kotlin-stdlib-2.0.21.jar
TRV=/opt/gradle-8.14.3/lib/trove4j-1.0.20200330.jar
ANN=/opt/gradle-8.14.3/lib/annotations-24.0.1.jar
GC="$HOME/.gradle/caches/modules-2/files-2.1"
COR=$(find "$GC" -name 'kotlinx-coroutines-core-jvm-*.jar' 2>/dev/null | head -1)
JU=$(find "$GC" -name 'junit-4.13.2.jar' 2>/dev/null | head -1)
HC=$(find "$GC" -name 'hamcrest-core-1.3.jar' 2>/dev/null | head -1)
JSOUP=${JSOUP:-/tmp/reader/jsoup.jar}
if [ ! -f "$JSOUP" ]; then
  JSOUP=$(find "$GC" -name 'jsoup-*.jar' 2>/dev/null | head -1)
fi
for j in "$KC" "$STD" "$TRV" "$ANN" "$COR" "$JSOUP"; do
  [ -n "$j" ] && [ -f "$j" ] || { echo "missing a required jar: '$j'"; exit 2; }
done

SRC=core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/Readability.kt
OUT=$(mktemp -d)
PAGES=${1:-/tmp/reader/pages}
TEST=${2:-}

if [ -n "$TEST" ]; then
  EXTRA="$TEST"
  TARGET_CP="$STD:$JSOUP:$JU:$HC"
else
  EXTRA="scratchpad/reader/Probe.kt"
  TARGET_CP="$STD:$JSOUP"
fi

java -cp "$KC:$STD:$TRV:$ANN:$COR" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -nowarn -d "$OUT" -cp "$TARGET_CP" "$SRC" "$EXTRA" 2>&1 |
  grep -Ev '^(warning|info):' || true

if [ -n "$TEST" ]; then
  CLASS=$(basename "$TEST" .kt)
  java -cp "$OUT:$STD:$JSOUP:$JU:$HC" org.junit.runner.JUnitCore \
    "dev.mascwa.pulse.core.telemetry.$CLASS" 2>&1 | grep -v JAVA_TOOL_OPTIONS
else
  java -cp "$OUT:$STD:$JSOUP" ProbeKt "$PAGES" 2>&1 | grep -v JAVA_TOOL_OPTIONS
fi
