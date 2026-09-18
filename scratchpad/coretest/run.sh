#!/usr/bin/env bash
# Compile and run any :core:telemetry JUnit test against the WHOLE core.
#
#   ./run.sh core/telemetry/src/test/.../SensoriumTest.kt [MoreTest.kt ...]
#
# ⚠️ Exactly one file in core/telemetry imports android.* (DeviceContextProvider), so every OTHER
# file compiles standalone — compiling the whole core in one shot beats chasing transitive deps.
# ⚠️ The compiler's OWN -cp needs stdlib + trove4j + annotations + coroutines. Omit one and kotlinc
# dies before it reads a line, and a grep for "error:" then finds nothing — which looks exactly like
# a clean pass. Every jar is asserted, and so is the fact that classes were actually produced.
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
JSOUP=$(find "$GC" -name 'jsoup-*.jar' 2>/dev/null | head -1)
# ⚠️ The core took a kotlinx-serialization dependency (GuideModels and friends are @Serializable), so
# both the runtime jars AND the compiler plugin are required. Without the plugin the annotation is
# reported as "illegal annotation class" and NOTHING compiles — see tools/android_compile_check.sh -s.
SER=$(find "$GC/org.jetbrains.kotlinx" -name 'kotlinx-serialization-core-jvm-*.jar' 2>/dev/null | head -1)
SERJ=$(find "$GC/org.jetbrains.kotlinx" -name 'kotlinx-serialization-json-jvm-*.jar' 2>/dev/null | head -1)
SPLUG=$(find "$HOME/.gradle/caches/modules-2" -name 'kotlin-serialization-compiler-plugin-embeddable-*.jar' 2>/dev/null | head -1)
for j in "$KC" "$STD" "$TRV" "$ANN" "$COR" "$JU" "$HC" "$JSOUP" "$SER" "$SERJ" "$SPLUG"; do
  [ -n "$j" ] && [ -f "$j" ] || { echo "missing a required jar: '$j'"; exit 2; }
done
[ $# -ge 1 ] || { echo "usage: run.sh <Test.kt> [Test.kt ...]"; exit 2; }

# ⚠️ `|| true` is load-bearing under `set -e`: **grep -L exits 1 when any file MATCHED** — its
# status tracks matches, not whether it printed anything — so the one core file that DOES import
# android.* makes a perfectly correct listing look like a failure and kills the script silently.
CORE=$(grep -rLE '^import android[.x]?' core/telemetry/src/main --include='*.kt' || true)
[ -n "$CORE" ] || { echo "found no core sources — is the path right?"; exit 2; }
OUT=$(mktemp -d)
TARGET_CP="$STD:$JSOUP:$JU:$HC:$SER:$SERJ"
java -cp "$KC:$STD:$TRV:$ANN:$COR" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -nowarn -Xplugin="$SPLUG" -d "$OUT" -cp "$TARGET_CP" $CORE "$@" 2>&1 |
  grep -Ev '^(warning|info):' || true

# A compiler that never ran produces no classes; without this the run below would just say
# "class not found" and the reason would be invisible.
[ -n "$(find "$OUT" -name '*.class' -print -quit)" ] || { echo "NOTHING COMPILED — see errors above"; exit 3; }

for t in "$@"; do
  CLASS=$(basename "$t" .kt)
  java -cp "$OUT:$TARGET_CP" org.junit.runner.JUnitCore \
    "dev.mascwa.pulse.core.telemetry.$CLASS" 2>&1 | grep -v JAVA_TOOL_OPTIONS
done
