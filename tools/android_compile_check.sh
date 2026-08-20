#!/usr/bin/env bash
#
# Fully compile Android source files here, with no Android SDK.
#
# WHY THIS EXISTS
# ---------------
# The standing position in this repo has been that `:app` cannot be compiled locally, so CI is the
# only gate for anything touching `android.*`. That is true of the SDK — but not of the platform
# classes themselves. **Robolectric publishes the whole android.jar to Maven Central** as
# `org.robolectric:android-all`, and `javap`/`kotlinc` are perfectly happy with it.
#
# So for a file whose only non-Kotlin dependency is the platform, this is not an approximation of
# CI's compile — it is the same compile, three minutes earlier. It found nothing wrong the day it was
# written, which is the point: it can now be run BEFORE pushing rather than after.
#
# WHAT IT COVERS, HONESTLY
# ------------------------
# Roughly 39 of the app's 333 files import `android.*` and nothing from androidx or a third party;
# those compile with no extra arguments. Anything else needs its libraries adding with -l, which
# works (androidx and most Android libraries publish AARs to Google's Maven), but has to be done per
# file and gets impractical for Compose UI. For those, `tools/android_resolve_check.sh` and CI remain
# the gates. This does not replace either; it is a stronger check where it applies.
#
# ⚠️ It compiles. It does not run, and it knows nothing about resources, the manifest, or R8. A green
# result here means the code is well-typed against the real platform, not that it behaves.
#
# Usage:
#   tools/android_compile_check.sh <file.kt> [more.kt ...]
#   tools/android_compile_check.sh -l androidx.media3:media3-common:1.5.1 <file.kt>
#
#   -l <group:artifact:version>   add a library to the compile classpath (jar or aar, from Maven
#                                 Central then Google's Maven). Repeatable.
#
# ⚠️ A Kotlin Multiplatform artifact's plain AAR contains **only a manifest** — the Android classes
# live in a separate `-android` variant. `lifecycle-runtime-compose:2.8.7` unpacks to 229 bytes and
# nothing else; `lifecycle-runtime-compose-android:2.8.7` is the one with the code in it. If a -l
# resolves but the symbols still do not, try the `-android` suffix before concluding anything.
set -uo pipefail

G=/opt/gradle-8.14.3/lib
GC=/root/.gradle/caches/modules-2/files-2.1
CACHE="${ANDROID_JAR_CACHE:-/tmp/android-compile-check}"
# Pinned so a run is reproducible. Any android-all works; this one is API 35, matching compileSdk.
ANDROID_ALL_VERSION="15-robolectric-13954326"

mkdir -p "$CACHE"
ANDROID_JAR="$CACHE/android-all-$ANDROID_ALL_VERSION.jar"

libs=()
while [ $# -gt 0 ] && [ "$1" = "-l" ]; do
  libs+=("$2")
  shift 2
done
[ $# -ge 1 ] || { echo "usage: $0 [-l group:artifact:version ...] <file.kt> [more.kt ...]"; exit 64; }

if [ ! -f "$ANDROID_JAR" ]; then
  echo "fetching the platform classes (~186 MB, once per cache) …" >&2
  url="https://repo1.maven.org/maven2/org/robolectric/android-all/$ANDROID_ALL_VERSION/android-all-$ANDROID_ALL_VERSION.jar"
  curl -fsS -o "$ANDROID_JAR" "$url" || { echo "could not fetch $url"; rm -f "$ANDROID_JAR"; exit 1; }
fi

# Resolve each -l onto the classpath. An AAR is a zip with the real classes inside it, so it is
# unpacked rather than used directly — kotlinc will silently ignore an aar handed to -cp.
extra=""
for coord in "${libs[@]:-}"; do
  [ -n "$coord" ] || continue
  IFS=':' read -r grp art ver <<<"$coord"
  path="${grp//.//}/$art/$ver/$art-$ver"
  for ext in jar aar; do
    f="$CACHE/$art-$ver.$ext"
    if [ ! -f "$f" ]; then
      for repo in "https://repo1.maven.org/maven2" "https://dl.google.com/dl/android/maven2"; do
        curl -fsS -o "$f" "$repo/$path.$ext" && break || rm -f "$f"
      done
    fi
    if [ -f "$f" ]; then
      if [ "$ext" = "aar" ]; then
        d="$CACHE/$art-$ver-unpacked"
        [ -d "$d" ] || { mkdir -p "$d" && (cd "$d" && unzip -qo "$f" classes.jar); }
        extra="$extra:$d/classes.jar"
      else
        extra="$extra:$f"
      fi
      break
    fi
  done
  case "$extra" in *"$art-$ver"*) ;; *) echo "could not resolve $coord"; exit 1 ;; esac
done

# ⚠️ The compiler's own -cp needs kotlin-stdlib + trove4j + annotations + kotlinx-coroutines. Omit
# one and it dies before compiling a line — which looks exactly like a clean pass, hence the check
# below. A silent false pass is worse than no check at all.
COR=$(find "$GC/org.jetbrains.kotlinx" -name 'kotlinx-coroutines-core-jvm-*.jar' 2>/dev/null | head -1)
SER=$(find "$GC/org.jetbrains.kotlinx" -name 'kotlinx-serialization-core-jvm-*.jar' 2>/dev/null | head -1)
COMPILER="$G/kotlin-compiler-embeddable-2.0.21.jar:$G/kotlin-stdlib-2.0.21.jar:$G/trove4j-1.0.20200330.jar:$G/annotations-24.0.1.jar:$COR"
TARGET_CP="$ANDROID_JAR:$G/kotlin-stdlib-2.0.21.jar:$COR:$SER$extra"

out=$(java -cp "$COMPILER" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
      -nowarn -d "$(mktemp -d)" -cp "$TARGET_CP" "$@" 2>&1)

errors=$(grep -E '^([^:]+\.kt):[0-9]+:[0-9]+: error:' <<<"$out")

# ⚠️ A @Composable REACHING the backend is a PASS, and conflating it with a missing jar made this
# gate report a genuinely clean file as a failure. Kotlin compiles in two halves: the frontend
# resolves names and types, the backend lowers IR to bytecode. Compose functions cannot be lowered
# without the Compose compiler plugin, which is not on this classpath and cannot usefully be — so a
# clean frontend followed by `Backend Internal error: Exception during IR lowering` is exactly what
# a correct Compose file looks like here, and it is the strongest check available for one.
#
# The distinction that matters is whether the frontend had anything to say. A missing jar dies
# before resolving a line and reports NoClassDefFoundError; that one really is "did not run".
if grep -q 'Exception during IR lowering' <<<"$out" && [ -z "$errors" ]; then
  echo "frontend clean — names and types resolve against the real platform ($# file(s))"
  echo "  (backend IR lowering failed, which is expected for @Composable without the Compose plugin)"
  exit 0
fi

if grep -q 'NoClassDefFoundError\|^exception:' <<<"$out"; then
  echo "COMPILER DID NOT RUN — a jar is missing from its own -cp. This is NOT a pass."
  grep -m2 'NoClassDefFoundError\|^exception:' <<<"$out"
  exit 2
fi

if [ -n "$errors" ]; then
  echo "COMPILE ERRORS against the real platform classes:"
  echo "$errors"
  exit 1
fi
echo "compiles clean against android-all $ANDROID_ALL_VERSION ($# file(s))"
