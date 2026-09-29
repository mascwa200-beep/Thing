#!/usr/bin/env bash
# Compile + run a pure :app JUnit test (no Android import) with jars from the Gradle distribution.
# Usage: run.sh <Test.kt> <main sources...>. Asserts the compiler actually produced classes.
set -uo pipefail
L=/opt/gradle-8.14.3/lib
KC=$L/kotlin-compiler-embeddable-2.0.21.jar; STD=$L/kotlin-stdlib-2.0.21.jar
TRV=$(ls $L/trove4j-*.jar); ANN=$L/annotations-24.0.1.jar; COR=$L/kotlinx-coroutines-core-jvm-1.6.4.jar
JU=$L/junit-4.13.2.jar; HC=$L/hamcrest-core-1.3.jar
for j in $KC $STD $TRV $ANN $COR $JU $HC; do [ -f "$j" ] || { echo "missing jar $j"; exit 2; }; done
T="$1"; shift
OUT=$(mktemp -d)
java -cp "$KC:$STD:$TRV:$ANN:$COR" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -nowarn -d "$OUT" -cp "$STD:$JU:$HC" "$@" "$T" 2>&1 | grep -Ev '^(warning|info):'
[ -n "$(find "$OUT" -name '*.class' -print -quit)" ] || { echo "NOTHING COMPILED"; exit 3; }
PKG=$(grep -m1 -E '^package ' "$T" | sed -E 's/^package +//')
java -cp "$OUT:$STD:$JU:$HC" org.junit.runner.JUnitCore "$PKG.$(basename "$T" .kt)" 2>&1 | grep -v JAVA_TOOL_OPTIONS
