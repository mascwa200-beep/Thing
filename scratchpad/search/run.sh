#!/usr/bin/env bash
# Compile and run a core:telemetry JUnit test locally, with the WHOLE core on the classpath.
#
# ⚠️ Exactly one file in core:telemetry imports android.* (DeviceContextProvider.kt), so excluding it
# lets the entire module compile standalone — far better than chasing transitive dependencies one at
# a time, which is how three earlier attempts were wasted.
#
# ⚠️ The compiler's OWN -cp needs stdlib + trove4j + annotations + coroutines. Omit one and kotlinc
# dies before it compiles a line, which looks exactly like a clean pass — so every jar is asserted.
#
#   ./run.sh <TestClass.kt> [more.kt ...]
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
for j in "$KC" "$STD" "$TRV" "$ANN" "$COR" "$JU" "$HC" "$JSOUP"; do
  [ -n "$j" ] && [ -f "$j" ] || { echo "missing a required jar: '$j'"; exit 2; }
done

# Every core file that does not reach into the platform.
CORE=$(grep -rLE '^import android[.x]?' core/telemetry/src/main --include='*.kt')
OUT=$(mktemp -d)

java -cp "$KC:$STD:$TRV:$ANN:$COR" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  $CORE "$@" -cp "$STD:$JU:$HC:$JSOUP:$COR" -d "$OUT" -nowarn 2>&1 | grep -v "^warning:" || true

classes=""
for f in "$@"; do
  n=$(basename "$f" .kt)
  [ -f "$OUT/dev/mascwa/pulse/core/telemetry/$n.class" ] && classes="$classes dev.mascwa.pulse.core.telemetry.$n"
done
[ -n "$classes" ] || { echo "nothing compiled — see the errors above"; exit 1; }

java -cp "$OUT:$STD:$JU:$HC:$JSOUP:$COR" org.junit.runner.JUnitCore $classes
