#!/usr/bin/env bash
# Type-check the WHOLE :sky application against the real platform, real Compose 1.7.6, real
# Material 3 1.3.1, activity, lifecycle and okhttp — the recipe the S10 slices used and S6 needed
# again for its Material date and time pickers, written down so it is not retyped.
#
#   ./scratchpad/sky/compile_sky_app.sh [extra.kt ...]      # e.g. a typed probe
#
# What it proves: every name, signature and argument type in sky/src/main, core/sky/src/main and
# core/update/src/main resolves — a `@Composable` cannot be LOWERED without the Compose plugin, so
# "frontend clean" is what a passing tree of composables looks like here (android_compile_check.sh
# tells the two apart). What it does not see: resources, the manifest, R8, anything AGP generates.
# `BuildConfig` is the stub below; a real one carrying a different type would not be caught.
#
# ⚠️ The two plain-JVM cores go on as COMPILED CLASSES, never as sources: sources fold them into one
# compilation unit, which makes a cross-module smart cast silently legal here while CI rejects it.
# ⚠️ Every KMP artifact goes on with its `-android`/`-jvm` variant; the bare coordinate is a
# manifest-only AAR and resolves to nothing.
set -uo pipefail
cd "$(dirname "$0")/../.."

TCLASSES=core/telemetry/build/classes/kotlin/main
FCLASSES=core/feeds/build/classes/kotlin/main

# Rebuilt rather than assumed current: a stale class directory reports a member added this hour as
# unresolved, which reads exactly like a defect.
./gradlew :core:telemetry:classes :core:feeds:classes \
    --configure-on-demand --no-configuration-cache -q >/tmp/skyclasses.txt 2>&1
if [ ! -d "$TCLASSES" ] || [ ! -d "$FCLASSES" ]; then
    echo "COULD NOT BUILD the shared cores — this gate did not run:"
    tail -5 /tmp/skyclasses.txt
    exit 2
fi

STUB=/tmp/sky-stub
mkdir -p "$STUB"
cat > "$STUB/BuildConfig.kt" <<'EOF'
package dev.mascwa.sky
object BuildConfig {
    const val VERSION_NAME: String = "0"
    const val VERSION_CODE: Int = 0
}
EOF

SRC=$(find sky/src/main/java core/sky/src/main core/update/src/main -name '*.kt' 2>/dev/null)
SRC="$SRC $STUB/BuildConfig.kt"

# ⚠️ `-t 17` because the cores go on as classes built at 17 (sky/build.gradle.kts compiles at 17
# too); without it the gate's 1.8 default refuses to inline `Constellations.walkEdge` and reports
# two errors on a tree CI compiles clean.
# shellcheck disable=SC2086
OUT=$(bash tools/android_compile_check.sh -s -t 17 -m "$TCLASSES" -m "$FCLASSES" \
    -l androidx.core:core:1.15.0 \
    -l androidx.core:core-ktx:1.15.0 \
    -l androidx.annotation:annotation-jvm:1.9.1 \
    -l androidx.annotation:annotation:1.9.1 \
    -l androidx.annotation:annotation-experimental:1.4.1 \
    -l androidx.savedstate:savedstate:1.2.1 \
    -l androidx.activity:activity:1.9.3 \
    -l androidx.activity:activity-compose:1.9.3 \
    -l androidx.lifecycle:lifecycle-common-jvm:2.8.7 \
    -l androidx.lifecycle:lifecycle-runtime-android:2.8.7 \
    -l androidx.lifecycle:lifecycle-runtime-ktx-android:2.8.7 \
    -l androidx.lifecycle:lifecycle-runtime-compose-android:2.8.7 \
    -l androidx.lifecycle:lifecycle-viewmodel-android:2.8.7 \
    -l androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7 \
    -l androidx.lifecycle:lifecycle-viewmodel-compose-android:2.8.7 \
    -l androidx.compose.runtime:runtime-android:1.7.6 \
    -l androidx.compose.runtime:runtime-saveable-android:1.7.6 \
    -l androidx.compose.ui:ui-android:1.7.6 \
    -l androidx.compose.ui:ui-text-android:1.7.6 \
    -l androidx.compose.ui:ui-graphics-android:1.7.6 \
    -l androidx.compose.ui:ui-unit-android:1.7.6 \
    -l androidx.compose.ui:ui-geometry-android:1.7.6 \
    -l androidx.compose.foundation:foundation-android:1.7.6 \
    -l androidx.compose.foundation:foundation-layout-android:1.7.6 \
    -l androidx.compose.material3:material3-android:1.3.1 \
    -l io.coil-kt:coil-base:2.7.0 \
    -l org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.7.3 \
    -l com.squareup.okhttp3:okhttp:4.12.0 \
    $SRC "$@" 2>&1 | grep -vE '^curl:')

# Require a success line rather than treating quiet as clean: the gate has reported clean having
# compiled nothing, and a run that died before reading a line prints nothing either.
if echo "$OUT" | grep -qE '^(compiles clean|frontend clean)'; then
    echo "$OUT" | grep -E '^(compiles clean|frontend clean)'
    exit 0
fi
echo "$OUT"
exit 1
