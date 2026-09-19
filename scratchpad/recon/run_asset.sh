#!/usr/bin/env bash
set -uo pipefail
cd "$(dirname "$0")/../.."
KC=/opt/gradle-8.14.3/lib/kotlin-compiler-embeddable-2.0.21.jar
STD=/opt/gradle-8.14.3/lib/kotlin-stdlib-2.0.21.jar
TRV=/opt/gradle-8.14.3/lib/trove4j-1.0.20200330.jar
ANN=/opt/gradle-8.14.3/lib/annotations-24.0.1.jar
GC="$HOME/.gradle/caches/modules-2/files-2.1"
COR=$(find "$GC" -name 'kotlinx-coroutines-core-jvm-*.jar'|head -1)
JU=$(find "$GC" -name 'junit-4.13.2.jar'|head -1)
HC=$(find "$GC" -name 'hamcrest-core-1.3.jar'|head -1)
OUT=$(mktemp -d)
# Recon.kt is the only core file this test needs; compile it plus the test.
java -cp "$KC:$STD:$TRV:$ANN:$COR" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -nowarn \
  -d "$OUT" -cp "$STD:$JU:$HC" \
  core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/Recon.kt \
  app/src/test/java/dev/mascwa/pulse/data/recon/ReconOuiAssetTest.kt 2>&1 | grep -Ev '^(warning|info):' || true
[ -n "$(find "$OUT" -name '*.class' -print -quit)" ] || { echo "NOTHING COMPILED"; exit 3; }
# The test reads File("src/main/assets/recon/oui.tsv") — cwd must be app/ for that to resolve.
cd app && java -cp "$OUT:$STD:$JU:$HC" org.junit.runner.JUnitCore \
  dev.mascwa.pulse.data.recon.ReconOuiAssetTest 2>&1 | grep -v JAVA_TOOL_OPTIONS
