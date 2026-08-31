#!/usr/bin/env bash
# Run the app-module WaterStations asset test locally, against the WHOLE core and the REAL asset.
#
# ⚠️ The test resolves the asset with a path relative to the MODULE directory, exactly as every
# other bundled-asset test in this repository does, so the JVM has to be launched from `app/`.
# Compiling from the repo root and running from `app/` is the whole trick.
# ⚠️ The compiler's OWN -cp needs stdlib + trove4j + annotations + coroutines + jsoup, and the
# serialization plugin, or kotlinc dies before reading a line — and a grep for "error:" then finds
# nothing, which looks exactly like a clean pass. Every jar is asserted, and so is the fact that
# classes were actually produced.
set -euo pipefail
cd "$(dirname "$0")/../.."
ROOT=$PWD

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
SPLUG=$(find "$HOME/.gradle/caches/modules-2" -name 'kotlin-serialization-compiler-plugin-embeddable-*.jar' 2>/dev/null | head -1)
for j in "$KC" "$STD" "$TRV" "$ANN" "$COR" "$JU" "$HC" "$JSOUP" "$SER" "$SERJ" "$SPLUG"; do
  [ -n "$j" ] && [ -f "$j" ] || { echo "missing a required jar: '$j'"; exit 2; }
done

TEST=app/src/test/java/dev/mascwa/pulse/data/water/WaterStationsAssetTest.kt
# ⚠️ `|| true`: grep -L exits 1 when any file MATCHED — its status tracks matches, not output — so
# the one core file that DOES import android.* makes a correct listing look like a failure.
CORE=$(grep -rLE '^import android[.x]?' core/telemetry/src/main --include='*.kt' || true)
[ -n "$CORE" ] || { echo "found no core sources — is the path right?"; exit 2; }

OUT=$(mktemp -d)
TARGET_CP="$STD:$JSOUP:$JU:$HC:$SER:$SERJ"
java -cp "$KC:$STD:$TRV:$ANN:$COR" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -nowarn -Xplugin="$SPLUG" -d "$OUT" -cp "$TARGET_CP" $CORE "$TEST" 2>&1 |
  grep -Ev '^(warning|info):' || true
[ -n "$(find "$OUT" -name '*.class' -print -quit)" ] || { echo "NOTHING COMPILED — see errors above"; exit 3; }

cd "$ROOT/app"
java -cp "$OUT:$TARGET_CP" org.junit.runner.JUnitCore \
  dev.mascwa.pulse.data.water.WaterStationsAssetTest 2>&1 | grep -v JAVA_TOOL_OPTIONS
