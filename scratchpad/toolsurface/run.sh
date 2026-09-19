#!/usr/bin/env bash
# Run AgentToolSurfaceTest locally. It imports no Android (File + regex + JUnit + RecordKind), so
# kotlinc plus JUnit is enough — the recorded recipe.
#
# ⚠️ Its relative paths resolve from the MODULE dir, which is where Gradle runs a test from, so the
# JVM must be started in app/. Started from the repo root it sweeps nothing, finds no problems and
# passes — the vacuous green every one of these gates carries a self-check against.
#
# ⚠️ The error pattern is '(^e: |: error:)', NOT '^(e|error):'. kotlinc reports
# "Foo.kt:48:31: error: unresolved reference" — the FILE comes first — so the anchored pattern that
# several sibling scripts carry never fires however broken the code is. Proven, twice.
set -uo pipefail
cd /home/user/Thing
G=/opt/gradle-8.14.3/lib
GC=/root/.gradle/caches/modules-2/files-2.1
STD=$G/kotlin-stdlib-2.0.21.jar
JUNIT=$(find "$GC" -name 'junit-4.13.2.jar' | head -1)
HAM=$(find "$GC" -name 'hamcrest-core-1.3.jar' | head -1)
COR=$(find "$GC" -name 'kotlinx-coroutines-core-jvm-*.jar' | head -1)
JSOUP=$(find "$GC" -name 'jsoup-*.jar' | head -1)
SER=$(find "$GC/org.jetbrains.kotlinx" -name 'kotlinx-serialization-core-jvm-*.jar' | head -1)
SERJ=$(find "$GC/org.jetbrains.kotlinx" -name 'kotlinx-serialization-json-jvm-*.jar' | head -1)
SPLUG=$(find "$GC/.." -name 'kotlin-serialization-compiler-plugin-embeddable-*.jar' | head -1)
for j in "$STD" "$JUNIT" "$HAM" "$COR" "$JSOUP" "$SER" "$SERJ" "$SPLUG"; do
  [ -n "$j" ] && [ -f "$j" ] || { echo "missing a required jar: '$j' — kotlinc would die before reading a line"; exit 2; }
done

# The whole core, minus the one file that imports android.* — the recorded one-shot recipe.
# ⚠️ `grep -L` exits 1 when a file MATCHED (its status tracks matches, not output), so the `|| true`
# is load-bearing under pipefail.
CORE=$(grep -rLE '^import android[.x]?' core/telemetry/src/main --include='*.kt' || true)
[ -n "$CORE" ] || { echo "found no core sources"; exit 2; }

OUT=/tmp/toolsurface; rm -rf "$OUT"; mkdir -p "$OUT"
TCP="$STD:$JUNIT:$HAM:$JSOUP:$SER:$SERJ"
java -cp "$G/kotlin-compiler-embeddable-2.0.21.jar:$STD:$G/trove4j-1.0.20200330.jar:$G/annotations-24.0.1.jar:$COR" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -nowarn -Xplugin="$SPLUG" -d "$OUT" -cp "$TCP" \
  $CORE \
  app/src/test/java/dev/mascwa/pulse/testing/SourceGate.kt \
  app/src/test/java/dev/mascwa/pulse/jarvis/agent/AgentToolSurfaceTest.kt \
  > "$OUT/compile.log" 2>&1
if grep -qE '(^e: |: error:)' "$OUT/compile.log"; then
  grep -E '(^e: |: error:)' "$OUT/compile.log" | head -20
  echo "COMPILE FAILED"; exit 1
fi
[ -n "$(find "$OUT" -name '*.class' -print -quit)" ] || { echo "NOTHING COMPILED — not a pass"; exit 1; }

cd app
java -cp "$OUT:$TCP" org.junit.runner.JUnitCore \
  dev.mascwa.pulse.jarvis.agent.AgentToolSurfaceTest 2>&1 | grep -v JAVA_TOOL_OPTIONS | tail -25
