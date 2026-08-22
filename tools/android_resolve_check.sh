#!/usr/bin/env bash
#
# Catch unresolved-name errors in touched ANDROID files locally, with no Android SDK.
#
# WHY THIS EXISTS
# ---------------
# This container can compile and run `core:telemetry` and `:desktop`, but not `:app` — no Android SDK.
# The fallback has been a parse-only kotlinc pass, which finds brace and syntax mistakes and says
# NOTHING about names. That gap has now cost two CI failures on this repo: `Unresolved reference 'c'`
# (a palette read in a composable that declares none) and `Unresolved reference 'Guide'` (a type used
# without an import). Both compile-clean under a parse gate and fail in CI three minutes later.
#
# HOW IT WORKS, AND WHY THE OBVIOUS VERSION DOES NOT
# --------------------------------------------------
# Compiling an Android file without the SDK produces ~100 unresolved-name errors, because every
# android/androidx/Compose symbol is missing. Grepping that for "real" errors does not work: the noise
# swamps the signal and any allowlist of platform prefixes is a guess that rots.
#
# So this does not filter — it DIFFERENCES. It compiles the file as committed at HEAD, compiles it as
# it stands in the working tree, and reports the unresolved names that appear only in the latter.
# Platform noise is identical in both and cancels exactly. What survives is a name your edit broke.
#
# ⚠️ Requires HEAD to be a version whose *own* names resolved — i.e. the last CI-green commit for that
# file. Comparing against a broken baseline hides the very error you are looking for.
#
# ⚠️ **Pass the defining file of any app-module symbol your edit newly references**, or you get a false
# positive. Only `core:telemetry` is on the classpath here, so an edit that starts calling, say,
# `StudyStore` or `LcarsButton` reports those (and every member reached through them) as "new" — they
# are unresolved because their module is absent, not because anything is wrong. Adding
# `.../data/study/StudyStore.kt` and `.../feature/common/LcarsGeometry.kt` to the argument list resolves
# them and the report goes quiet. A run that names types you know exist is telling you to widen the
# argument list, not that you have a bug.
#
# ⚠️ The compiler's own -cp needs kotlin-stdlib + trove4j + annotations + kotlinx-coroutines. Omit one
# and it dies before compiling a line, which looks exactly like a clean pass — hence the explicit
# did-it-actually-run check below. A silent false pass is worse than no check.
#
# Usage:  tools/android_resolve_check.sh <file.kt> [more.kt ...]
set -uo pipefail

G=/opt/gradle-8.14.3/lib
GC=/root/.gradle/caches/modules-2/files-2.1
COR=$(find "$GC/org.jetbrains.kotlinx" -name 'kotlinx-coroutines-core-jvm-*.jar' 2>/dev/null | head -1)
# kotlinx-serialization too: without it every @Serializable app model fails on the annotation,
# and the differencing then reports each NEWLY ADDED member of that model as unresolved. That is
# a pure false positive and it fires on the commonest edit there is — adding a field to a cached
# data class — so the jar belongs here rather than in a caveat.
SER=$(find "$GC/org.jetbrains.kotlinx" -name 'kotlinx-serialization-core-jvm-*.jar' 2>/dev/null | head -1)
# ⚠️ jsoup, because `core:telemetry` itself depends on it (Readability). Without it the WHOLE core
# fails to compile and every member of every core type cascades into the report — a run that named
# `Extraction.wordCount`, `Meta` and `truncated` as unresolved while the core compiled clean under
# the real build. A baseline that broad reports nothing useful, and a gate you learn to ignore is
# worse than no gate. Whenever the core takes a new dependency, add it here too.
JSOUP=$(find "$GC/org.jsoup" -name 'jsoup-*.jar' 2>/dev/null | head -1)
if [ -z "$JSOUP" ]; then JSOUP=$(find /tmp -name 'jsoup*.jar' 2>/dev/null | head -1); fi
# ⚠️ `:core:feeds` as COMPILED CLASSES, never as sources. It is the second plain-JVM module `:app`
# depends on — the HTTP client, the disk cache, `Async`, `Formatters`, `Geo` and 22 repositories —
# and without it every app file that touches one of them cascades exactly the way the jsoup note
# above describes. A run that named `Formatters` and `util` unresolved while the real build
# compiled clean is what put this line here.
#
# Classes rather than sources for the reason `tools/kotlin_jvm_check.sh` records at length: passing
# a module's sources folds it into THIS compilation unit, which makes it the same module as the
# file under test — and a cross-module smart-cast error then vanishes, so the gate would pass on
# code CI rejects. The three jars below are feeds' own dependencies, needed only so the signatures
# in those class files resolve.
# ⚠️ **A GENERATED CLASS CAN NEVER RESOLVE HERE, and the differencing does not save you from it.**
# `BuildConfig` and `R` are written by the build, so nothing local has them on any classpath. The
# differencing normally cancels that out — the complaint is present at HEAD too — but a file using
# one for the FIRST time has no baseline complaint to cancel against, so it is reported as new and
# looks exactly like a real defect.
#
# How to settle it in one command, rather than shrugging: compile a file that ALREADY uses the same
# generated class and ships green in CI, e.g.
#
#     tools/android_compile_check.sh app/src/main/java/dev/mascwa/pulse/crash/CrashReporter.kt
#
# That reports `unresolved reference 'BuildConfig'` on its own import line, which is proof the
# mechanism is generic rather than anything about your edit. Do check the member exists —
# VERSION_CODE, VERSION_NAME and the flavour fields are the only ones this build generates.

FEEDS=core/feeds/build/classes/kotlin/main
OKHTTP=$(find "$GC/com.squareup.okhttp3" -name 'okhttp-*.jar' 2>/dev/null | head -1)
OKIO=$(find "$GC/com.squareup.okio" -name 'okio-jvm-*.jar' 2>/dev/null | head -1)
SERJ=$(find "$GC/org.jetbrains.kotlinx" -name 'kotlinx-serialization-json-jvm-*.jar' 2>/dev/null | head -1)
if [ ! -d "$FEEDS" ]; then
  echo "note: $FEEDS not built — run ./gradlew :core:feeds:classes or expect false positives" >&2
fi
COMPILER="$G/kotlin-compiler-embeddable-2.0.21.jar:$G/kotlin-stdlib-2.0.21.jar:$G/trove4j-1.0.20200330.jar:$G/annotations-24.0.1.jar:$COR"
TARGET_CP="$COR:$SER:$SERJ:$JSOUP:$FEEDS:$OKHTTP:$OKIO:$G/kotlin-stdlib-2.0.21.jar"

# The whole pure core, so its types DO resolve — exactly one file in it imports android.*.
mapfile -t CORE < <(grep -rLE '^import android[.x]?' core/telemetry/src/main --include='*.kt')

[ $# -ge 1 ] || { echo "usage: $0 <file.kt> [more.kt ...]"; exit 64; }

# Every compiler complaint, LOCATION STRIPPED, one per line, sorted and deduplicated.
#
# It used to extract only "unresolved reference", which is blind to arity, argument-type,
# exhaustiveness and smart-cast errors — exactly the errors an edit to a CALL SITE produces. Taking
# every message widens it. Verified by perturbation: a wrong arity gives
# `too many arguments for 'fun videoId(url: String): String'.` and a wrong type gives
# `argument type mismatch: actual type is 'kotlin.Int', but 'kotlin.String' was expected.`, and
# neither was visible before.
#
# ⚠️ **BUT IT ONLY WORKS WHERE THE CALLEE RESOLVES, AND THAT IS A SMALL PART OF `:app`.** This was
# widened after `OnDemandController.stop()` reached CI against a declaration reading
# `stop(context: Context)` — and the widening does NOT catch that one. `OnDemandController.kt`
# cannot compile here at all (its androidx/media3 imports are absent), so `stop` is simply an
# unresolved name and the compiler never learns its signature to check it against. Confirmed by
# perturbing that exact line and watching the gate stay silent.
#
# So: arity and type errors are caught against `core:telemetry`, `core:feeds` and the Kotlin stdlib,
# and NOT against app-module, androidx or platform symbols. For those there is no local gate, and
# the only defence is the discipline CLAUDE.md already records — open the real declaration before
# writing the call.
#
# The location MUST come off. Differencing is the whole mechanism, and a message keyed by file:line
# never cancels — every line below an insertion shifts, so the entire file would read as new.
#
# ⚠️ Second limit, the same one the name-based version always had: the key is the message text, so
# if HEAD *already* produces an identical message somewhere in these files, a second one your edit
# introduces is masked. Platform noise cancelling is the same property that causes this. It is a
# filter for what changed, not a compile.
complaints() {
  local out
  out=$(java -cp "$COMPILER" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
        -nowarn -d "$(mktemp -d)" -cp "$TARGET_CP" "${CORE[@]}" "$@" 2>&1)
  if grep -q 'NoClassDefFoundError\|^exception:' <<<"$out"; then
    echo "!!COMPILER-DID-NOT-RUN"
    grep -m2 'NoClassDefFoundError\|^exception:' <<<"$out" >&2
    return
  fi
  # ⚠️ **TWO FORMATS, AND PICKING THE WRONG ONE MAKES THIS MATCH NOTHING.** The K2JVMCompiler CLI
  # invoked directly — which is what this script does — writes
  #     path/File.kt:LINE:COL: error: message
  # while GRADLE's Kotlin plugin writes
  #     e: file:///abs/path/File.kt:LINE:COL message
  # A first cut of this took the pattern from a CI log, so it matched only the Gradle form, extracted
  # zero lines here, and reported "no new complaints" for every possible edit. A check that cannot
  # fail is worse than no check, and it passed its own smoke test by looking clean. Both forms are
  # handled; the count assertion below is what would catch a third.
  grep -E '^([^ ]+\.kt:[0-9]+:[0-9]+: error: |e: )' <<<"$out" \
    | sed -E 's|^[^ ]+\.kt:[0-9]+:[0-9]+: error: ||; s|^e: file://[^ ]*:[0-9]+:[0-9]+ ||; s|^e: ||' \
    | sed -E 's/[[:space:]]+$//' \
    | sort -u
}

work=$(mktemp -d)
baseline=()
for f in "$@"; do
  dst="$work/$f"
  mkdir -p "$(dirname "$dst")"
  # A file that is new in the working tree has no baseline; compare it against nothing.
  if git show "HEAD:$f" > "$dst" 2>/dev/null; then baseline+=("$dst"); fi
done

now=$(complaints "$@")
if grep -q '!!COMPILER-DID-NOT-RUN' <<<"$now"; then
  echo "COMPILER DID NOT RUN — a jar is missing from its own -cp. This is NOT a pass."
  exit 2
fi

if [ ${#baseline[@]} -eq 0 ]; then
  echo "No committed baseline for these files; showing every complaint (platform noise included):"
  echo "$now"
  exit 0
fi

then_=$(complaints "${baseline[@]}")

# ⚠️ **THE EXTRACTOR MUST HAVE FOUND SOMETHING.** These are Android files compiled with no Android
# SDK on the classpath, so the baseline ALWAYS produces unresolved-reference errors — every
# `android.*` and `androidx.*` import is missing. Zero means the message pattern above stopped
# matching, not that the code is clean, and the difference would then be empty for any edit whatsoever.
# That exact failure shipped in a first cut of this widening, so it is asserted rather than trusted.
if [ -z "$then_" ]; then
  echo "EXTRACTOR MATCHED NOTHING — the compiler's message format changed. This is NOT a pass."
  echo "  An Android file with no SDK must produce unresolved-reference errors; zero means the"
  echo "  grep/sed in complaints() no longer matches. Print the raw output and fix the pattern."
  exit 2
fi

new=$(comm -23 <(echo "$now") <(echo "$then_"))

if [ -n "$new" ]; then
  echo "NEW compiler complaints since HEAD — these are almost certainly real:"
  echo "$new"
  exit 1
fi
echo "no new compiler complaints since HEAD ($(echo "$then_" | wc -l) present either way)"
