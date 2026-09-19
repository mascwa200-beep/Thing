#!/usr/bin/env bash
# Does the desktop Settings entry steal a word another screen owns, and do the trapped control
# words now reach it?
#
# Extracts the SHIPPED DESK_GROUPS literals, then runs the SHIPPED GuideSearch + DeviceSearch over
# them. Stronger than a unit test: it scores the strings that are actually in the tree.
#
# ⚠️ The compiler's OWN -cp needs stdlib + trove4j + annotations + coroutines. Omit one and kotlinc
# dies in CoreApplicationEnvironment before reading a line — which looks exactly like a clean pass.
# So every jar is asserted, and so is the fact that classes came out.
set -euo pipefail
cd "$(dirname "$0")"
ROOT=$(cd ../.. && pwd)

python3 - "$ROOT" <<'PY'
import re, sys, pathlib
root = pathlib.Path(sys.argv[1])
src = (root / "desktop/src/main/kotlin/dev/mascwa/pulse/desktop/Directory.kt").read_text()

def strip(s):
    s = re.sub(r'/\*.*?\*/', ' ', s, flags=re.S)
    return re.sub(r'//[^\n]*', ' ', s)
d = strip(src)

rows, group = [], None
for m in re.finditer(r'DeskGroup\("([^"]*)"|DeskEntry\(Screen\.(\w+),\s*"([^"]*)",\s*"([^"]*)"'
                     r'(?:,\s*\n?\s*listOf\(([^)]*)\))?', d):
    if m.group(1):
        group = m.group(1); continue
    scr, label, desc, terms = m.group(2), m.group(3), m.group(4), m.group(5)
    words = " ".join(re.findall(r'"([^"]*)"', terms or ""))
    rows.append((scr, group or "?", label, desc, words))

# ⚠️ Assert the extractor sees things known to be present, before its silence means anything.
assert len(rows) >= 27, f"parsed only {len(rows)} entries"
by = {r[0]: r for r in rows}
assert by["SETTINGS"][2] == "Settings", "Settings entry not parsed"
assert "units" in by["SETTINGS"][4], "Settings search terms not parsed"
assert "somafm" in by["RADIO"][4], "a multi-word listOf did not parse"
assert "long watch" in by["ANOMALIES"][4], "ANOMALIES' 'long watch' term not parsed"
pathlib.Path("screens.tsv").write_text("".join("\t".join(r) + "\n" for r in rows))
print(f"extracted {len(rows)} screens from the real Directory.kt")
PY

G=/opt/gradle-8.14.3/lib
STD=$(ls $G/kotlin-stdlib-*.jar | head -1)
TROVE=$(ls $G/trove4j-*.jar | head -1)
ANN=$(ls $G/annotations-*.jar | head -1)
KC=$(ls $G/kotlin-compiler-embeddable-*.jar | head -1)
COR=$(find /root/.gradle/caches -name 'kotlinx-coroutines-core-jvm-*.jar' 2>/dev/null | head -1)
for j in "$STD" "$TROVE" "$ANN" "$KC" "$COR"; do
  [ -n "$j" ] && [ -f "$j" ] || { echo "MISSING JAR (the compiler would die before reading a line): $j"; exit 1; }
done
CORE=$ROOT/core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry
rm -rf out && mkdir -p out
java -cp "$KC:$STD:$TROVE:$ANN:$COR" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  "$CORE/GuideSearch.kt" "$CORE/DeviceSearch.kt" Probe.kt \
  -cp "$STD" -d out -nowarn 2>&1 | grep -E '^(e:|error:)' && { echo "COMPILE FAILED"; exit 1; }
[ -f out/ProbeKt.class ] || { echo "NO CLASSES PRODUCED — the compiler did not run"; exit 1; }
java -cp "out:$STD" ProbeKt
