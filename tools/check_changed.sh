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

echo
[ "$FAIL" = 0 ] && echo "gates clean — CI is still the compile gate" || echo "REVIEW THE ABOVE"
exit "$FAIL"
