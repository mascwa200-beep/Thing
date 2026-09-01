#!/usr/bin/env bash
# Run a core:telemetry JUnit test locally, against the WHOLE core.
#
# ⚠️ Exactly one file in core:telemetry imports android.*, so the whole module compiles standalone
# once that one is excluded. That is far better than chasing transitive dependencies file by file.
# ⚠️ The compiler's OWN -cp needs stdlib + trove4j + annotations + coroutines + jsoup: omit one and
# kotlinc dies before compiling a line, which looks exactly like a clean pass. Every jar is asserted.
#
#   ./run.sh <Test.kt> [more tests...]
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
SER=$(find "$GC" -name 'kotlinx-serialization-core-jvm-*.jar' 2>/dev/null | head -1)
SERJ=$(find "$GC" -name 'kotlinx-serialization-json-jvm-*.jar' 2>/dev/null | head -1)
# ⚠️ A @Serializable class only gains its synthetic `serializer()` when this compiler plugin runs.
# Without it `GuideModels.kt` alone reports "illegal annotation class 'Serializable'" a dozen times
# and NOTHING compiles — which reads as a defect in the core and is a missing plugin.
SERP=$(find "$GC" -name 'kotlin-serialization-compiler-plugin-embeddable-*.jar' 2>/dev/null | head -1)
for j in "$KC" "$STD" "$TRV" "$ANN" "$COR" "$JU" "$HC" "$JSOUP" "$SER" "$SERJ" "$SERP"; do
  [ -n "$j" ] && [ -f "$j" ] || { echo "missing a required jar: '$j'"; exit 2; }
done

# ⚠️ `|| true` because **`grep -L` exits 1 even when it lists files** — its status follows "did any
# LINE match", and with -L none ever does. Under `set -e` that killed this script silently, with no
# output on either stream and exit 1, which reads exactly like a test failure. CLAUDE.md records this
# recipe used inline as an argument, where `set -e` does not reach a command substitution.
CORE=$(grep -rLE '^import android[.x]?' core/telemetry/src/main --include='*.kt' || true)
[ -n "$CORE" ] || { echo "found no core sources — check the path"; exit 2; }
OUT=$(mktemp -d)

java -cp "$KC:$STD:$TRV:$ANN:$COR:$JSOUP:$SER:$SERJ" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -nowarn -Xplugin="$SERP" -d "$OUT" -cp "$STD:$COR:$JSOUP:$SER:$SERJ:$JU:$HC" $CORE "$@" 2>&1 |
  grep -Ev '^(warning|info):' || true

# ⚠️ Assert the compile actually produced classes. A silent kotlinc failure is otherwise
# indistinguishable from a clean run, which this repo has been fooled by twice.
[ -n "$(find "$OUT" -name '*.class' -print -quit)" ] || { echo "nothing compiled"; exit 2; }

CLASSES=""
for t in "$@"; do CLASSES="$CLASSES dev.mascwa.pulse.core.telemetry.$(basename "$t" .kt)"; done
java -cp "$OUT:$STD:$COR:$JSOUP:$SER:$SERJ:$JU:$HC" org.junit.runner.JUnitCore $CLASSES 2>&1 |
  grep -v JAVA_TOOL_OPTIONS
