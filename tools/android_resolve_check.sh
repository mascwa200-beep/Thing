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
COMPILER="$G/kotlin-compiler-embeddable-2.0.21.jar:$G/kotlin-stdlib-2.0.21.jar:$G/trove4j-1.0.20200330.jar:$G/annotations-24.0.1.jar:$COR"
TARGET_CP="$COR:$SER:$JSOUP:$G/kotlin-stdlib-2.0.21.jar"

# The whole pure core, so its types DO resolve — exactly one file in it imports android.*.
mapfile -t CORE < <(grep -rLE '^import android[.x]?' core/telemetry/src/main --include='*.kt')

[ $# -ge 1 ] || { echo "usage: $0 <file.kt> [more.kt ...]"; exit 64; }

# Unresolved names for a given set of files, one per line, sorted and deduplicated.
unresolved() {
  local out
  out=$(java -cp "$COMPILER" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
        -nowarn -d "$(mktemp -d)" -cp "$TARGET_CP" "${CORE[@]}" "$@" 2>&1)
  if grep -q 'NoClassDefFoundError\|^exception:' <<<"$out"; then
    echo "!!COMPILER-DID-NOT-RUN"
    grep -m2 'NoClassDefFoundError\|^exception:' <<<"$out" >&2
    return
  fi
  grep -oiE "unresolved reference '[A-Za-z_][A-Za-z0-9_]*'" <<<"$out" | sort -u
}

work=$(mktemp -d)
baseline=()
for f in "$@"; do
  dst="$work/$f"
  mkdir -p "$(dirname "$dst")"
  # A file that is new in the working tree has no baseline; compare it against nothing.
  if git show "HEAD:$f" > "$dst" 2>/dev/null; then baseline+=("$dst"); fi
done

now=$(unresolved "$@")
if grep -q '!!COMPILER-DID-NOT-RUN' <<<"$now"; then
  echo "COMPILER DID NOT RUN — a jar is missing from its own -cp. This is NOT a pass."
  exit 2
fi

if [ ${#baseline[@]} -eq 0 ]; then
  echo "No committed baseline for these files; showing every unresolved name (platform noise included):"
  echo "$now"
  exit 0
fi

then_=$(unresolved "${baseline[@]}")
new=$(comm -23 <(echo "$now") <(echo "$then_"))

if [ -n "$new" ]; then
  echo "NEW unresolved names since HEAD — these are almost certainly real:"
  echo "$new"
  exit 1
fi
echo "no new unresolved names since HEAD ($(echo "$then_" | wc -l) platform names unresolved either way)"
