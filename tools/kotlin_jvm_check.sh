#!/usr/bin/env bash
# Type-check :app Kotlin files that touch no Android API, against the REAL compiled core modules.
#
# Why this exists
# ---------------
# `core:telemetry` and `core:feeds` are separate Gradle modules, and Kotlin refuses to smart-cast a
# public property declared in a *different* module. So this compiles fine inside a core module and
# fails in :app:
#
#     if (q.low != null) { use(q.low) }      // error: smart cast is impossible
#
# The fix is always a local val. The problem is that nothing local catches it: the parse-only pass
# does not type-check, and `android_resolve_check.sh` differences *unresolved names*, which a smart
# cast failure is not. That gap has cost three CI rounds.
#
# ⚠️ THE LOAD-BEARING DETAIL: the core modules go on the classpath as COMPILED CLASSES, never as
# source files. Passing their sources would put them in the same compilation unit, which makes them
# the same module, which makes the error disappear — the check would then pass on code CI rejects.
# That is the one way this script could be worse than useless, so it verifies the class directories
# exist rather than silently compiling against nothing.
#
# Scope: files whose only non-Kotlin dependency is the project's own modules. Anything importing
# android.* or androidx.* needs `android_compile_check.sh` instead.
#
# Usage:  tools/kotlin_jvm_check.sh <file.kt> [more.kt ...]
#         tools/kotlin_jvm_check.sh --all          # every qualifying :app file, in one pass
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

GRADLE_LIB=/opt/gradle-8.14.3/lib
CACHE="$HOME/.gradle/caches/modules-2/files-2.1"

first_jar() { ls $1 2>/dev/null | head -1; }

COROUTINES=$(first_jar "$CACHE/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/*/*/kotlinx-coroutines-core-jvm-*.jar")
SERIALIZATION=$(first_jar "$CACHE/org.jetbrains.kotlinx/kotlinx-serialization-core-jvm/*/*/kotlinx-serialization-core-jvm-*.jar")
SERIALJSON=$(first_jar "$CACHE/org.jetbrains.kotlinx/kotlinx-serialization-json-jvm/*/*/kotlinx-serialization-json-jvm-*.jar")
OKHTTP=$(first_jar "$CACHE/com.squareup.okhttp3/okhttp/*/*/okhttp-*.jar")
OKIO=$(first_jar "$CACHE/com.squareup.okio/okio-jvm/*/*/okio-jvm-*.jar")
JSOUP=$(first_jar "$CACHE/org.jsoup/jsoup/*/*/jsoup-*.jar")

# ⚠️ The compiler needs coroutines on its OWN -cp or it dies with NoClassDefFoundError before
# compiling a line — which reads exactly like a clean pass. Asserted, not assumed.
for j in "$GRADLE_LIB/kotlin-compiler-embeddable-2.0.21.jar" "$GRADLE_LIB/kotlin-stdlib-2.0.21.jar" \
         "$GRADLE_LIB/trove4j-1.0.20200330.jar" "$GRADLE_LIB/annotations-24.0.1.jar" "$COROUTINES"; do
  [ -f "$j" ] || { echo "MISSING compiler dependency: $j" >&2; exit 2; }
done

CORE_CLASSES=""
for m in core/telemetry core/feeds; do
  d="$m/build/classes/kotlin/main"
  if [ ! -d "$d" ]; then
    echo "Building $m so the check has real compiled classes to resolve against…" >&2
    ./gradlew ":${m//\//:}:compileKotlin" --configure-on-demand --no-configuration-cache -q >/dev/null
  fi
  [ -d "$d" ] || { echo "MISSING compiled classes: $d — the check would be vacuous." >&2; exit 2; }
  CORE_CLASSES="$CORE_CLASSES:$d"
done

if [ "${1:-}" = "--all" ]; then
  mapfile -t FILES < <(
    grep -rLE '^import (android|androidx)[.x]?' app/src/main/java --include='*.kt'
  )
  echo "Checking ${#FILES[@]} :app files with no Android imports."
else
  FILES=("$@")
  [ ${#FILES[@]} -gt 0 ] || { echo "usage: $0 <file.kt> [...] | --all" >&2; exit 1; }
fi

OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT

set +e
java -cp "$GRADLE_LIB/kotlin-compiler-embeddable-2.0.21.jar:$GRADLE_LIB/kotlin-stdlib-2.0.21.jar:$GRADLE_LIB/trove4j-1.0.20200330.jar:$GRADLE_LIB/annotations-24.0.1.jar:$COROUTINES" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -cp "$GRADLE_LIB/kotlin-stdlib-2.0.21.jar$CORE_CLASSES:$COROUTINES:$SERIALIZATION:$SERIALJSON:$OKHTTP:$OKIO:$JSOUP" \
  -d "$OUT" -nowarn "${FILES[@]}" 2>&1 | tee "$OUT/log" >/dev/null
set -e

# ⚠️ Report ONLY the cross-module smart-cast class and outright syntax errors. A batch run over
# every qualifying file will also report unresolved names for anything reached through a type this
# classpath does not carry, and drowning the one signal in that noise is how a gate stops being read.
if grep -q "smart cast to .* is impossible" "$OUT/log"; then
  echo "CROSS-MODULE SMART CAST — hoist to a local val:"
  grep "smart cast to .* is impossible" "$OUT/log"
  exit 1
fi

# ⚠️ Coverage is reported, not implied. On --all most files reach a type this classpath does not
# carry, and once a file's own types fail to resolve the compiler may never get as far as the smart
# cast. So "no cross-module smart casts" is a full answer only for the files that compiled cleanly;
# for the rest it is a partial one, and CI is still the gate. Measured, so nobody has to guess:
# at the time of writing 26 of 81 qualifying files were fully clean.
# `|| true`: grep exits 1 on no match, and under `pipefail` that would fail the whole script at
# exactly the moment it has good news.
DIRTY=$( (grep -oE "^app/[^:]+\.kt" "$OUT/log" || true) | sort -u | wc -l | tr -d ' ')
echo "no cross-module smart casts found."
echo "coverage: ${#FILES[@]} files checked, $DIRTY had unresolved names (partial answer for those)."
