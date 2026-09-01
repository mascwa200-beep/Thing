#!/usr/bin/env bash
# Compile the WHOLE :core:telemetry plus a probe file carrying a `main`, and run it.
#   ./runmain.sh Probe.kt dev.mascwa.pulse.core.telemetry.ProbeKt
# Same jar discipline as coretest/run.sh — every jar asserted, and a compiler that produced no
# classes is reported rather than surfacing later as "class not found".
set -euo pipefail
cd "$(dirname "$0")/../.."
KC=/opt/gradle-8.14.3/lib/kotlin-compiler-embeddable-2.0.21.jar
STD=/opt/gradle-8.14.3/lib/kotlin-stdlib-2.0.21.jar
TRV=/opt/gradle-8.14.3/lib/trove4j-1.0.20200330.jar
ANN=/opt/gradle-8.14.3/lib/annotations-24.0.1.jar
GC="$HOME/.gradle/caches/modules-2/files-2.1"
COR=$(find "$GC" -name 'kotlinx-coroutines-core-jvm-*.jar' 2>/dev/null | head -1)
JSOUP=$(find "$GC" -name 'jsoup-*.jar' 2>/dev/null | head -1)
SER=$(find "$GC/org.jetbrains.kotlinx" -name 'kotlinx-serialization-core-jvm-*.jar' 2>/dev/null | head -1)
SERJ=$(find "$GC/org.jetbrains.kotlinx" -name 'kotlinx-serialization-json-jvm-*.jar' 2>/dev/null | head -1)
SPLUG=$(find "$HOME/.gradle/caches/modules-2" -name 'kotlin-serialization-compiler-plugin-embeddable-*.jar' 2>/dev/null | head -1)
for j in "$KC" "$STD" "$TRV" "$ANN" "$COR" "$JSOUP" "$SER" "$SERJ" "$SPLUG"; do
  [ -n "$j" ] && [ -f "$j" ] || { echo "missing a required jar: '$j'"; exit 2; }
done
[ $# -ge 2 ] || { echo "usage: runmain.sh <Probe.kt> <MainClass>"; exit 2; }
PROBE="$1"; shift
MAIN="$1"; shift
CORE=$(grep -rLE '^import android[.x]?' core/telemetry/src/main --include='*.kt' || true)
[ -n "$CORE" ] || { echo "found no core sources — is the path right?"; exit 2; }
OUT=$(mktemp -d)
TARGET_CP="$STD:$JSOUP:$SER:$SERJ"
java -cp "$KC:$STD:$TRV:$ANN:$COR" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -nowarn -Xplugin="$SPLUG" -d "$OUT" -cp "$TARGET_CP" $CORE "$PROBE" 2>&1 |
  grep -Ev '^(warning|info):' || true
[ -n "$(find "$OUT" -name '*.class' -print -quit)" ] || { echo "NOTHING COMPILED — see errors above"; exit 3; }
java -cp "$OUT:$TARGET_CP" "$MAIN" "$@" 2>&1 | grep -v JAVA_TOOL_OPTIONS
