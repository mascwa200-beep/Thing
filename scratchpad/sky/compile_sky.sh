#!/usr/bin/env bash
# Type-check the WHOLE of :core:sky (and the pure core it sits on) against the real platform and
# the real Compose 1.7.6 / lifecycle 2.8.7 artifacts — the recipe the S1/S2 star-map slices used,
# written down so S3–S6 do not retype fourteen coordinates.
#
#   ./scratchpad/sky/compile_sky.sh [extra.kt ...]      # e.g. a typed probe
#
# ⚠️ Every KMP artifact goes on with its `-android`/`-jvm` variant; the bare coordinate is a
# manifest-only AAR and resolves to nothing (tools/android_compile_check.sh says so at length).
set -euo pipefail
cd "$(dirname "$0")/../.."
SKY=$(find core/sky/src/main -name '*.kt')
CORE=$(grep -rLE '^import android[.x]?' core/telemetry/src/main --include='*.kt' || true)
[ -n "$SKY" ] && [ -n "$CORE" ] || { echo "found no sources — is the path right?"; exit 2; }
exec tools/android_compile_check.sh -s \
  -l androidx.compose.runtime:runtime-android:1.7.6 \
  -l androidx.compose.runtime:runtime-saveable-android:1.7.6 \
  -l androidx.compose.ui:ui-android:1.7.6 \
  -l androidx.compose.ui:ui-geometry-android:1.7.6 \
  -l androidx.compose.ui:ui-graphics-android:1.7.6 \
  -l androidx.compose.ui:ui-unit-android:1.7.6 \
  -l androidx.compose.ui:ui-text-android:1.7.6 \
  -l androidx.compose.foundation:foundation-android:1.7.6 \
  -l androidx.compose.foundation:foundation-layout-android:1.7.6 \
  -l androidx.lifecycle:lifecycle-viewmodel-android:2.8.7 \
  -l androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7 \
  -l androidx.lifecycle:lifecycle-runtime-android:2.8.7 \
  -l androidx.lifecycle:lifecycle-runtime-compose-android:2.8.7 \
  -l androidx.lifecycle:lifecycle-common-jvm:2.8.7 \
  $SKY $CORE "$@"
