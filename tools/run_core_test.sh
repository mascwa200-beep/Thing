#!/usr/bin/env bash
#
# Run one :core:telemetry test class locally, with no Gradle and no Android SDK.
#
# WHY THIS EXISTS
# ---------------
# :core:telemetry is a plain Kotlin/JVM module, so kotlinc plus JUnit on the command line compiles
# and runs it exactly as CI does — in about a minute rather than in a CI round. Every pure core in
# this repo is developed against this, and every "negative-tested against a baseline asserted green
# first" claim in the history was produced by it.
#
# Usage:
#   tools/run_core_test.sh <fully.qualified.TestClass> <path/to/TestClass.kt>
#
# ⚠️ THE MISSING-JAR TRAP, which this asserts against. Omit one jar from the COMPILER's own -cp and
# kotlinc dies inside CoreApplicationEnvironment before reading a line of source. The output is
# then empty, and empty is indistinguishable from a clean compile. Every jar is checked to exist
# before anything runs, and the class file is checked to exist afterwards.
#
# ⚠️ THE TRUNCATION TRAP, which is why the tail is generous. This ended `| tail -25` for a long
# time. That is fine for a pass and quietly wrong for a failure: JUnit prints `There were N
# failures:` and then a numbered header per failure followed by a full stack trace, so four
# failures overflow twenty-five lines and the cut lands PAST the first header. The first failure's
# stack trace survives while its NAME does not. A harness parsing failure names off this stream
# then loses exactly one failure, and any check keyed on "did the test I expected fail?" reports a
# false negative that looks like a defective test rather than a truncated pipe. That cost a full
# round; do not lower this number to tidy the output.
set -uo pipefail

[ $# -eq 2 ] || { echo "usage: $0 <fq.TestClass> <path/to/Test.kt>"; exit 2; }
CLASS="$1"
TEST="$2"
[ -f "$TEST" ] || { echo "no such test source: $TEST"; exit 2; }

G=/opt/gradle-8.14.3/lib

# ⚠️ Exactly one file in core/telemetry imports android.*, so compiling "everything that does not"
# gives the whole core in one shot. Chasing transitive dependencies file by file does not work —
# the cores reference each other freely.
CORE=$(grep -rLE '^import android[.x]?' core/telemetry/src/main --include='*.kt' | tr '\n' ' ')

JSOUP=$(find /root/.gradle -name 'jsoup-*.jar' 2>/dev/null | head -1)
SER=$G/kotlinx-serialization-core-jvm-1.6.2.jar
SERJ=$G/kotlinx-serialization-json-jvm-1.6.2.jar
JUNIT=$(find /root/.gradle -name 'junit-4.13.2.jar' 2>/dev/null | head -1)
HAM=$(find /root/.gradle -name 'hamcrest-core-1.3.jar' 2>/dev/null | head -1)
COR=$(find $G -name 'kotlinx-coroutines-core-jvm*.jar' | head -1)
PLUG=$(find /root/.gradle -name 'kotlin-serialization-compiler-plugin-embeddable-2.0.21.jar' 2>/dev/null | head -1)

for j in "$JSOUP" "$SER" "$SERJ" "$JUNIT" "$HAM" "$COR" "$PLUG"; do
  [ -f "$j" ] || { echo "MISSING JAR: $j"; exit 2; }
done

OUT=${CORE_RUN_DIR:-/tmp/corerun}
rm -rf "$OUT" && mkdir -p "$OUT"

# ⚠️ trove4j, annotations and coroutines go on the COMPILER's classpath, not the target's. The
# long-blamed "standalone kotlinc IR-lowering crash" was only ever one of these missing.
java -cp "$G/kotlin-compiler-embeddable-2.0.21.jar:$G/kotlin-stdlib-2.0.21.jar:$G/trove4j-1.0.20200330.jar:$G/annotations-24.0.1.jar:$COR" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  $CORE "$TEST" -cp "$G/kotlin-stdlib-2.0.21.jar:$JSOUP:$SER:$SERJ:$JUNIT:$HAM" \
  -Xplugin="$PLUG" -d "$OUT/out" -nowarn 2>&1 | grep -E "error:" | head -20

CLS="$OUT/out/$(echo "$CLASS" | tr . /).class"
[ -f "$CLS" ] || { echo "NO CLASS — compiler did not produce $CLS"; exit 1; }

java -cp "$OUT/out:$G/kotlin-stdlib-2.0.21.jar:$JSOUP:$SER:$SERJ:$JUNIT:$HAM" \
  org.junit.runner.JUnitCore "$CLASS" 2>&1 | grep -v "JAVA_TOOL_OPTIONS" | tail -400
