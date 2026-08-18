#!/bin/bash
# Compile the whole core (minus its one android-importing file) plus a test, and run it.
set -e
GC=$HOME/.gradle/caches/modules-2/files-2.1
KC=/opt/gradle-8.14.3/lib/kotlin-compiler-embeddable-2.0.21.jar
STD=/opt/gradle-8.14.3/lib/kotlin-stdlib-2.0.21.jar
TRV=/opt/gradle-8.14.3/lib/trove4j-1.0.20200330.jar
ANN=/opt/gradle-8.14.3/lib/annotations-24.0.1.jar
COR=$(find $GC -name 'kotlinx-coroutines-core-jvm-*.jar' | head -1)
JUNIT=$(find $GC -name 'junit-4.13.2.jar' | head -1)
HAM=$(find $GC -name 'hamcrest-core-1.3.jar' | head -1)
[ -n "$JUNIT" ] || { echo "junit not in gradle cache"; exit 1; }
OUT=/home/user/Thing/scratchpad/kb/coreout; rm -rf "$OUT"; mkdir -p "$OUT"
SRC=$(grep -rLE '^import android[.x]?' core/telemetry/src/main --include='*.kt')
java -cp "$KC:$STD:$TRV:$ANN:$COR" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -nowarn -d "$OUT" -cp "$STD:$JUNIT:$HAM:$COR" $SRC "$@" 2>&1 | grep -Ev '^(warning|info):' || true
java -cp "$OUT:$STD:$JUNIT:$HAM:$COR" org.junit.runner.JUnitCore \
  $(for f in "$@"; do basename "$f" .kt | sed 's|^|dev.mascwa.pulse.core.telemetry.|'; done)
