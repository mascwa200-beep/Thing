#!/usr/bin/env bash
# Run every local gate over EVERY Kotlin file this working tree has changed.
#
# ⚠️ This exists because hand-picking the file list cost a CI round. Three unresolved references
# shipped in HealthViewModel.kt because it was modified in the same commit as four other files and I
# passed only the four I happened to be thinking about. The list must come from git, never from
# memory — that is the whole point of this script and the only reason it is worth having.
#
#   tools/check_changed.sh            # everything changed since HEAD, staged or not, plus new files
#   tools/check_changed.sh --staged   # only what is staged, for a final pre-commit pass
#
# ⚠️ WHAT THIS CANNOT DO, so nobody reads a clean run as more than it is:
#
#   - android_resolve_check.sh DIFFERENCES unresolved names against HEAD. When HEAD itself is broken,
#     the error appears on both sides and cancels, so a FIX to a compile error reports identically to
#     no change at all. After fixing a CI compile failure, confirm it by compiling — extract the
#     shipped declaration and build it against real types — not by a clean run here.
#   - a NEW file has no baseline to cancel against, so everything in it is reported. Use the
#     use-vs-import audit for those instead.
#   - none of these gates runs the app, draws a screen, or opens a device.
set -uo pipefail
cd "$(dirname "$0")/.."

if [ "${1:-}" = "--staged" ]; then
    CHANGED=$(git diff --cached --name-only --diff-filter=d -- '*.kt')
else
    CHANGED=$( { git diff --name-only --diff-filter=d HEAD -- '*.kt'
                 git ls-files --others --exclude-standard -- '*.kt'; } | sort -u )
fi

if [ -z "$CHANGED" ]; then
    echo "no changed Kotlin files"
    exit 0
fi

echo "== changed Kotlin files =="
echo "$CHANGED" | sed 's/^/   /'
FAIL=0

# ---- 0. stray control bytes, over EVERY changed file, not just the Kotlin ------------------------
# ⚠️ First, because it is the only gate here that catches a defect the compiler cannot see at all. A
# NUL byte inside a Kotlin character literal compiles perfectly and IS the NUL character: `DeepSky.kt`
# shipped `' '` as its "no photometric band" sentinel with a NUL inside the quotes, and every row came
# out carrying NUL. Nothing warned. The only tell was `grep` answering "binary file matches" rather
# than showing the line, which reads as noise.
#
# Deliberately over every changed file rather than the Kotlin list above: the hazard is in writing the
# file, not in the language.
echo
echo "== control-byte gate =="
if [ "${1:-}" = "--staged" ]; then
    ALLCH=$(git diff --cached --name-only --diff-filter=d)
else
    ALLCH=$( { git diff --name-only --diff-filter=d HEAD
               git ls-files --others --exclude-standard; } | sort -u )
fi
# Binary assets have control bytes by definition and are not what this is looking for.
TEXTCH=$(echo "$ALLCH" | grep -E '\.(kt|kts|java|py|sh|yml|yaml|xml|json|md|txt|tsv|csv|pro|cpp|h|properties|gradle)$' || true)
if [ -z "$TEXTCH" ]; then
    echo "   (no changed text files)"
elif OUT=$(echo "$TEXTCH" | xargs -r python3 tools/nul_byte_check.py 2>&1); then
    echo "   ok    $OUT"
else
    echo "$OUT" | sed 's/^/   /'
    FAIL=1
fi

# ---- 1. imports and duplicate companions, per package the change touches -------------------------
echo
echo "== import gate =="
PKGS=$(echo "$CHANGED" | xargs -r -n1 dirname | sort -u)
for d in $PKGS; do
    OUT=$(python3 tools/kotlin_import_check.py "$d" 2>&1)
    if echo "$OUT" | grep -q "^clean"; then
        printf '   ok    %s\n' "$d"
    else
        printf '   CHECK %s\n' "$d"
        echo "$OUT" | grep -E "used but not imported|does not resolve|companion" | sed 's/^/         /'
        FAIL=1
    fi
done

# ---- 1b. one top-level name declared twice in a package ------------------------------------------
# ⚠️ Added after a CI round was spent on exactly this. Two file-private top-level declarations in
# different files are legal, which is why it looks safe; a private one beside anything more visible
# is a "Conflicting overloads" compile error. Neither the parse gate (no name resolution) nor the
# resolve gate (differences UNRESOLVED names, and this name resolved at HEAD) can see it.
echo
echo "== duplicate top-level declarations =="
DUP=$(python3 tools/kotlin_dup_decl_check.py $PKGS 2>&1)
if echo "$DUP" | grep -q "^no colliding"; then
    printf '   ok    none in the packages this change touches\n'
else
    echo "$DUP" | sed 's/^/   /'
    FAIL=1
fi

# ---- 1a2. an `internal` member of a shared core cannot be reached from another module ------------
# ⚠️ `internal` is MODULE-scoped. Nothing else here can catch a crossing: the parse gate does not
# resolve names, the resolve gate differences *unresolved* references and this is a VISIBILITY error,
# and `:app` unit tests cannot be built in this container at all. It has cost two red CI rounds
# (`Stardate.clockOf`, then `StarNames.properKeys` on run 2084) and the script says how.
#
# Runs unconditionally rather than on the changed set: the two halves of the mistake live in
# different modules, so a change to either one can create it.
echo
echo "== cross-module internal gate =="
if python3 tools/cross_module_internal_check.py > /tmp/cmi.txt 2>&1; then
    sed 's/^/   /' /tmp/cmi.txt
else
    sed 's/^/   /' /tmp/cmi.txt
    FAIL=1
fi

# ---- 1b. a function that reads a composition local must be @Composable ---------------------------
# ⚠️ Nothing else here can catch this: the message comes from the COMPOSE COMPILER PLUGIN, which the
# parse gate does not run, the resolve gate cannot see (it differences unresolved NAMES) and
# `android_compile_check.sh` does not load. A shipped `overColor` that read `Pulse.colors` without the
# annotation passed every gate below and failed CI on `:app:compileDebugKotlin`.
echo
echo "== composition-local gate =="
if echo "$CHANGED" | xargs -r python3 tools/compose_local_check.py > /tmp/composecheck.txt 2>&1; then
  echo "   ok    every changed function that reads a composition local is @Composable"
else
  echo "   NOT @Composable — this is the exact shape that fails :app:compileDebugKotlin:"
  sed 's/^/   /' /tmp/composecheck.txt
fi

# ---- 2. parse only: braces and syntax, in seconds ------------------------------------------------
# ⚠️ Says NOTHING about whether a name resolves. Two CI failures have gone straight past it.
echo
echo "== parse gate =="
G=/opt/gradle-8.14.3/lib
KOTLINC="$G/kotlin-compiler-embeddable-2.0.21.jar"
STDLIB=$(ls "$G"/kotlin-stdlib-*.jar 2>/dev/null | head -1)
TROVE=$(ls "$G"/trove4j-*.jar 2>/dev/null | head -1)
COROUT=$(find /root/.gradle ~/.gradle -name 'kotlinx-coroutines-core-jvm-*.jar' 2>/dev/null | head -1)
# ⚠️ Assert the jars exist. Without coroutines on the COMPILER's own -cp it dies before reading a
# line, and empty output is indistinguishable from a clean pass — that has been read as success once.
for j in "$KOTLINC" "$STDLIB" "$TROVE" "$COROUT"; do
    [ -f "$j" ] || { echo "   MISSING JAR: $j — the parse gate cannot run"; exit 2; }
done
PARSE=$(java -cp "$KOTLINC:$STDLIB:$TROVE:$G/annotations-24.0.1.jar:$COROUT" \
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -cp "$STDLIB" -d /tmp/parsecheck -nowarn \
    $CHANGED 2>&1 | grep -iE "expecting|unexpected token|unresolved reference '\\)'" | head -20)
if [ -n "$PARSE" ]; then
    echo "$PARSE" | sed 's/^/   /'
    FAIL=1
else
    echo "   ok    no syntax or brace errors"
fi

# ---- 3. resolution, app files only ---------------------------------------------------------------
echo
echo "== resolve gate (app files; see the caveats at the top) =="
APP=$(echo "$CHANGED" | grep '^app/' || true)
if [ -z "$APP" ]; then
    echo "   (no app files changed)"
else
    # shellcheck disable=SC2086
    bash tools/android_resolve_check.sh $APP 2>&1 | tail -12 | sed 's/^/   /'
fi

# ---- 4. :core:health, compiled in full ----------------------------------------------------------
# ⚠️ This is the strongest gate in this file and it exists because its absence cost a CI round.
# `Result.failure(...)` assigned to a `Result<*>?` cannot infer T; it parses, every name in it
# resolves, and it fails `:core:health:compileDebugKotlin`. Neither gate above can see that shape.
#
# ⚠️ The standing note that this module "cannot be built in this container" was WRONG, and believing
# it is what left the hole. The plain KMP DataStore AAR is manifest-only, so reaching for it made
# every store report hundreds of unresolved names and the conclusion drawn was that the module was
# out of reach. With the `-android`/`-jvm` variants the WHOLE module — stores, Health Connect
# bridge, the 1,370-line view model — compiles against the real platform in about twenty seconds.
#
# The two shared cores go on as COMPILED CLASSES, never sources, so a cross-module smart cast still
# fails here exactly as it does in CI. `:core:database` is the one exception: it is a Room module
# that cannot be built here, so its single file is folded in as a source and a smart cast across
# that one boundary would be invisible.
HEALTH_CHANGED=$(echo "$CHANGED" | grep '^core/health/src/main/' || true)
if [ -n "$HEALTH_CHANGED" ]; then
    echo
    echo "== :core:health full compile =="
    TCLASSES=core/telemetry/build/classes/kotlin/main
    FCLASSES=core/feeds/build/classes/kotlin/main
    # ⚠️ Rebuilt rather than assumed current: a stale class directory reports a member added to a
    # core this hour as unresolved, which is indistinguishable from a real defect.
    ./gradlew :core:telemetry:classes :core:feeds:classes \
        --configure-on-demand --no-configuration-cache -q >/tmp/coreclasses.txt 2>&1
    if [ ! -d "$TCLASSES" ] || [ ! -d "$FCLASSES" ]; then
        echo "   COULD NOT BUILD the shared cores — this gate did not run:"
        tail -5 /tmp/coreclasses.txt | sed 's/^/         /'
        FAIL=1
    else
        HEALTH_SRC=$(ls core/health/src/main/java/dev/mascwa/pulse/data/health/*.kt \
                        core/health/src/main/java/dev/mascwa/pulse/feature/health/*.kt)
        # shellcheck disable=SC2086
        OUT=$(bash tools/android_compile_check.sh -s -m "$TCLASSES" -m "$FCLASSES" \
            -l androidx.datastore:datastore-preferences-android:1.1.1 \
            -l androidx.datastore:datastore-preferences-core-jvm:1.1.1 \
            -l androidx.datastore:datastore-core-android:1.1.1 \
            -l androidx.datastore:datastore-core-okio-jvm:1.1.1 \
            -l androidx.core:core:1.15.0 \
            -l androidx.core:core-ktx:1.15.0 \
            -l androidx.lifecycle:lifecycle-viewmodel-android:2.8.7 \
            -l androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7 \
            -l androidx.activity:activity:1.9.3 \
            -l androidx.health.connect:connect-client:1.1.0-beta01 \
            -l androidx.sqlite:sqlite:2.4.0 \
            -l androidx.room:room-runtime:2.6.1 \
            -l androidx.room:room-common:2.6.1 \
            -l org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.7.3 \
            -l com.squareup.okhttp3:okhttp:4.12.0 \
            $HEALTH_SRC \
            core/database/src/main/java/dev/mascwa/pulse/data/food/db/FoodDatabase.kt 2>&1 \
            | grep -vE '^curl:')
        # ⚠️ Require the success line rather than treating quiet as clean. This script has reported
        # "compiles clean" having compiled nothing once already, and an empty result from a run that
        # died before reading a line looks identical to a pass.
        if echo "$OUT" | grep -q '^compiles clean'; then
            echo "   ok    the whole module compiles against the real platform classes"
        else
            echo "$OUT" | tail -12 | sed 's/^/   /'
            FAIL=1
        fi
    fi
fi

echo
[ "$FAIL" = 0 ] && echo "gates clean — CI is still the compile gate" || echo "REVIEW THE ABOVE"
exit "$FAIL"
