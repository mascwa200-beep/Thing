#!/bin/bash
# Compile the SHIPPED GuideSearch.kt plus a throwaway main, and run it over the REAL index.
set -e
GC=$HOME/.gradle/caches/modules-2/files-2.1
KC=/opt/gradle-8.14.3/lib/kotlin-compiler-embeddable-2.0.21.jar
STD=/opt/gradle-8.14.3/lib/kotlin-stdlib-2.0.21.jar
TRV=/opt/gradle-8.14.3/lib/trove4j-1.0.20200330.jar
ANN=/opt/gradle-8.14.3/lib/annotations-24.0.1.jar
COR=$(find $GC -name 'kotlinx-coroutines-core-jvm-*.jar' | head -1)
OUT=/home/user/Thing/scratchpad/kb/out
rm -rf "$OUT"; mkdir -p "$OUT"
# NOTE: the jars go on the COMPILER's own -cp. Omitting trove4j/annotations is what was long
# misdiagnosed as an "IR-lowering crash"; omitting coroutines kills it before it compiles a line.
java -cp "$KC:$STD:$TRV:$ANN:$COR" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -nowarn -d "$OUT" -cp "$STD" \
  /home/user/Thing/core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/GuideSearch.kt \
  /home/user/Thing/scratchpad/kb/Probe.kt 2>&1 | grep -v '^warning:' || true
java -cp "$OUT:$STD" ProbeKt
