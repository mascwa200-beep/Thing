#!/usr/bin/env bash
# Run the app-module ThrottleStampCoverageTest locally. It has NO Android dependency (File + regex +
# JUnit), so kotlinc plus JUnit is enough — the recorded recipe. ⚠️ Its relative paths are resolved
# from the MODULE dir, which is where Gradle runs a test from, so the JVM must be started in app/.
set -uo pipefail
cd /home/user/Thing
G=/opt/gradle-8.14.3/lib
CACHE=/root/.gradle/caches/modules-2/files-2.1
JUNIT=$CACHE/junit/junit/4.13.2/8ac9e16d933b6fb43bc7f576336b8f4d7eb5ba12/junit-4.13.2.jar
HAM=$CACHE/org.hamcrest/hamcrest-core/1.3/42a25dc3219429f0e5d060061f71acb49bf010a0/hamcrest-core-1.3.jar
COROUT=$CACHE/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.9.0/9beade4c1c1569e4f36cbd2c37e02e3e41502601/kotlinx-coroutines-core-jvm-1.9.0.jar
OUT=/tmp/gateout; rm -rf "$OUT"; mkdir -p "$OUT"

# ⚠️ trove4j + annotations + coroutines belong on the COMPILER's own -cp or kotlinc dies in
# CoreApplicationEnvironment before reading a line — and an empty error grep then looks like a pass.
java -cp "$G/kotlin-compiler-embeddable-2.0.21.jar:$G/kotlin-stdlib-2.0.21.jar:$G/trove4j-1.0.20200330.jar:$G/annotations-24.0.1.jar:$COROUT" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -cp "$G/kotlin-stdlib-2.0.21.jar:$JUNIT:$HAM" -d "$OUT" -nowarn \
  app/src/test/java/dev/mascwa/pulse/data/settings/ThrottleStampCoverageTest.kt 2>&1 | grep -E "^(e|error):" && { echo "COMPILE FAILED"; exit 1; }
[ -n "$(ls -A "$OUT" 2>/dev/null)" ] || { echo "compiler produced NOTHING — not a pass"; exit 1; }

cd app
java -cp "$OUT:$G/kotlin-stdlib-2.0.21.jar:$JUNIT:$HAM" org.junit.runner.JUnitCore \
  dev.mascwa.pulse.data.settings.ThrottleStampCoverageTest 2>&1 | tail -22
