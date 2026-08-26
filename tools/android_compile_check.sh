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
plugins=()
modules=()
module_cp=""
while [ $# -gt 0 ]; do
  case "$1" in
    -l) libs+=("$2"); shift 2 ;;
    # ⚠️ @Serializable classes only gain a synthetic `serializer()` when the kotlinx-serialization
    # compiler plugin runs. Without -s, every `.serializer()` in a store reports "unresolved
    # reference" and a dozen inference failures cascade off it — which reads exactly like a real
    # defect and is not one.
    -s) SERIALIZATION_PLUGIN=1; shift ;;
    # ⚠️ A project module, as COMPILED CLASSES — never as sources. Passing another module's .kt
    # files folds it into one compilation unit, which makes it one module, which makes a
    # cross-module smart-cast error vanish: the gate would then pass on code CI rejects. That trap
    # is documented on tools/kotlin_jvm_check.sh and is the same one here.
    #
    # Build the directory first — `./gradlew :core:feeds:compileKotlin --configure-on-demand
    # --no-configuration-cache` — then pass
    # `-m core/feeds/build/classes/kotlin/main`. Both shared cores are plain Kotlin/JVM modules, so
    # this needs no Android SDK.
    -m) modules+=("$2"); shift 2 ;;
    *) break ;;
  esac
done
# ⚠️ Flags AFTER the first file used to be passed to the compiler as source paths. kotlinc then
# stopped at "source file or directory not found" — a message this script's error grep does not
# match — and the run reported "compiles clean" having compiled nothing. A gate that reports its own
# misconfiguration as a pass is worse than no gate, so a stray flag is now refused outright.
for a in "$@"; do
  case "$a" in
    -*) echo "flags must come BEFORE the file list; found '$a' after it" >&2; exit 64 ;;
    *) [ -f "$a" ] || { echo "no such source file: $a" >&2; exit 66 ; } ;;
  esac
done
for m in "${modules[@]:-}"; do
  [ -n "$m" ] || continue
  # ⚠️ A path that does not exist would silently contribute nothing, and every symbol it was meant
  # to supply would be reported unresolved — indistinguishable from a real defect, and the exact
  # shape of failure this gate exists to avoid producing.
  [ -d "$m" ] || { echo "-m $m is not a directory — build the module first" >&2; exit 71; }
  module_cp="$module_cp:$m"
done
if [ -n "${SERIALIZATION_PLUGIN:-}" ]; then
  sp=$(find "$HOME/.gradle/caches/modules-2" -name 'kotlin-serialization-compiler-plugin-embeddable-*.jar' 2>/dev/null | head -1)
  [ -n "$sp" ] || { echo "-s asked for but the serialization compiler plugin is not in the Gradle cache" >&2; exit 70; }
  plugins+=("-Xplugin=$sp")
fi
[ $# -ge 1 ] || { echo "usage: $0 [-s] [-l group:artifact:version ...] [-m module/build/classes/kotlin/main ...] <file.kt> [more.kt ...]"; exit 64; }

# ⚠️ **Forgetting -s on a file that uses @Serializable produces a page of convincing false findings,
# and this says so rather than letting somebody chase them.** Without the plugin the compiler has no
# generated `serializer()` and no generated members, so every property of every serializable class
# reads as unresolved — which looks exactly like a real defect and cost several rounds once. It is a
# notice, not a refusal: a caller may deliberately be checking something else in the same file.
if [ -z "${SERIALIZATION_PLUGIN:-}" ]; then
  for f in "$@"; do
    [ -f "$f" ] || continue
    if grep -q '@Serializable' "$f" 2>/dev/null; then
      echo "note: $f uses @Serializable and -s was not passed — expect false 'unresolved reference'" >&2
      echo "      reports on its generated members and on serializer(). Re-run with -s." >&2
      break
    fi
  done
fi

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
# ⚠️ jsoup is a DEPENDENCY OF :core:telemetry (Readability.kt), so any run that passes the whole
# core fails wholesale without it — and the resulting hundreds of errors are all in Readability,
# which reads like a real finding and buries whatever you were actually checking. Cost two rounds
# once. Kept alongside the coroutines/serialization jars for exactly the same reason.
JSOUP=$(find "$GC/org.jsoup" -name 'jsoup-*.jar' 2>/dev/null | head -1)
# ⚠️ kotlinx-serialization-JSON as well as -core, and the distinction is not cosmetic. `@Serializable`
# and `KSerializer` live in core; `Json` itself lives in json, and `DiskCache` — which almost every
# repository in this project reaches through — names `Json` directly. Without this the whole feeds
# module fails on an unresolved `Json`, every `serializer()` after it cascades, and the report reads
# as dozens of real findings in the file you were actually checking. Verified by control run: the
# same file at HEAD, which CI compiles green, produced the identical errors until this line existed.
SERJ=$(find "$GC/org.jetbrains.kotlinx" -name 'kotlinx-serialization-json-jvm-*.jar' 2>/dev/null | head -1)
COMPILER="$G/kotlin-compiler-embeddable-2.0.21.jar:$G/kotlin-stdlib-2.0.21.jar:$G/trove4j-1.0.20200330.jar:$G/annotations-24.0.1.jar:$COR"
TARGET_CP="$ANDROID_JAR:$G/kotlin-stdlib-2.0.21.jar:$COR:$SER:$SERJ:$JSOUP$extra$module_cp"

out=$(java -cp "$COMPILER" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
      -nowarn -d "$(mktemp -d)" -cp "$TARGET_CP" ${plugins[@]+"${plugins[@]}"} "$@" 2>&1)

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

# ⚠️ Belt and braces for the same class of failure: any `error:` line the file:line:col grep above
# could not attribute to a source file. kotlinc emits these for problems with the INVOCATION rather
# than the code (a missing source path, a bad argument), and they mean nothing was compiled.
if grep -qE '^error:' <<<"$out"; then
  echo "COMPILER REFUSED THE INVOCATION — nothing was compiled. This is NOT a pass."
  grep -m3 -E '^error:' <<<"$out"
  exit 2
fi

if [ -n "$errors" ]; then
  echo "COMPILE ERRORS against the real platform classes:"
  echo "$errors"
  exit 1
fi
echo "compiles clean against android-all $ANDROID_ALL_VERSION ($# file(s))"
