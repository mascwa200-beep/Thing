#!/bin/bash
set -e
KC=/opt/gradle-8.14.3/lib/kotlin-compiler-embeddable-2.0.21.jar
STD=/opt/gradle-8.14.3/lib/kotlin-stdlib-2.0.21.jar
TRV=/opt/gradle-8.14.3/lib/trove4j-1.0.20200330.jar
ANN=/opt/gradle-8.14.3/lib/annotations-24.0.1.jar
COR=$(find $HOME/.gradle/caches/modules-2/files-2.1 -name 'kotlinx-coroutines-core-jvm-*.jar' | head -1)
OUT=/home/user/Thing/scratchpad/fallacy/out; rm -rf "$OUT"; mkdir -p "$OUT"
java -cp "$KC:$STD:$TRV:$ANN:$COR" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -nowarn -d "$OUT" -cp "$STD:$COR" \
  core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/Fallacies.kt \
  scratchpad/fallacy/Probe.kt 2>&1 | grep -Ev '^(warning|info):' || true
java -cp "$OUT:$STD:$COR" dev.mascwa.pulse.core.telemetry.ProbeKt "$@"
