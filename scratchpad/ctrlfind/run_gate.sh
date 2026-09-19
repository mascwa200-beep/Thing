#!/usr/bin/env bash
# Run SettingsSectionCoverageTest locally. It reads the source as text and touches no Android API,
# so kotlinc + JUnit is enough.
#
# ⚠️ The JVM must start in the MODULE directory — the test's File(...) paths are relative, and that
# is where Gradle resolves them from. Run it anywhere else and every read fails as "not found",
# which looks like the source being wrong rather than the harness.
# ⚠️ The compiler's own -cp needs stdlib + trove4j + annotations + coroutines. Omit one and kotlinc
# dies in CoreApplicationEnvironment before reading a line — indistinguishable from a clean pass.
set -euo pipefail
ROOT=/home/user/Thing
G=/opt/gradle-8.14.3/lib
STD=$(ls $G/kotlin-stdlib-*.jar | head -1)
TROVE=$(ls $G/trove4j-*.jar | head -1)
ANN=$(ls $G/annotations-*.jar | head -1)
KC=$(ls $G/kotlin-compiler-embeddable-*.jar | head -1)
COR=$(find /root/.gradle/caches -name 'kotlinx-coroutines-core-jvm-*.jar' 2>/dev/null | head -1)
JUNIT=$(find /root/.gradle/caches -name 'junit-4.13.2.jar' 2>/dev/null | head -1)
HAM=$(find /root/.gradle/caches -name 'hamcrest-core-1.3.jar' 2>/dev/null | head -1)
for j in "$STD" "$TROVE" "$ANN" "$KC" "$COR" "$JUNIT" "$HAM"; do
  [ -n "$j" ] && [ -f "$j" ] || { echo "MISSING JAR (the compiler would die before reading a line): '$j'"; exit 1; }
done
OUT=$ROOT/scratchpad/ctrlfind/out
rm -rf "$OUT" && mkdir -p "$OUT"
java -cp "$KC:$STD:$TROVE:$ANN:$COR" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  "$ROOT/app/src/test/java/dev/mascwa/pulse/feature/settings/SettingsSectionCoverageTest.kt" \
  -cp "$STD:$JUNIT" -d "$OUT" -nowarn 2>&1 | grep -E '^(e:|error:)' && { echo "COMPILE FAILED"; exit 1; }
[ -f "$OUT/dev/mascwa/pulse/feature/settings/SettingsSectionCoverageTest.class" ] \
  || { echo "NO CLASSES PRODUCED — the compiler did not run"; exit 1; }
cd "$ROOT/app"     # <- the module directory, see above
java -cp "$OUT:$STD:$JUNIT:$HAM" org.junit.runner.JUnitCore \
  dev.mascwa.pulse.feature.settings.SettingsSectionCoverageTest
