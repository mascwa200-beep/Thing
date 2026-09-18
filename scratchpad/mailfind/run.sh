#!/usr/bin/env bash
# Is the mail setting findable, and did the surface that already worked survive?
#
#   ./run.sh              run against the tree as it stands
#   ./run.sh --control    run against the PRE-FIX strings, to prove the fix is what does it
#
# Extracts the SHIPPED literals out of SettingsCategory.kt / SettingsScreen.kt / Directory.kt, then
# runs the SHIPPED GuideSearch + DeviceSearch over them. That is stronger than a unit test: it
# checks the strings that are in the tree rather than a paraphrase of them, and it is what found
# that "email" returned literally nothing before the change.
#
# ⚠️ The compiler's OWN -cp needs stdlib + trove4j + annotations + coroutines, and the core has a
# kotlinx-serialization dependency so the serialization compiler plugin is required too. Omit one
# and kotlinc dies before it reads a line, which looks exactly like a clean pass — so every jar is
# asserted and so is the fact that classes came out.
set -euo pipefail
cd "$(dirname "$0")"
ROOT=$(cd ../.. && pwd)
CONTROL=${1:-}

python3 - "$ROOT" "$CONTROL" <<'PY'
import re, sys, pathlib
root, control = pathlib.Path(sys.argv[1]), sys.argv[2] == "--control"

src = (root / "app/src/main/java/dev/mascwa/pulse/feature/settings/SettingsCategory.kt").read_text()
body = re.sub(r'//[^\n]*', '', src)          # so a commented-out entry cannot be picked up
pat = re.compile(r'^\s*([A-Z_]+)\("([^"]*)",\s*"([^"]*)",\s*"([^"]*)",\s*[^,]+,?\s*(?:"([^"]*)")?\s*\),\s*$', re.M)
cats = pat.findall(body)
assert len(cats) == 10, f"expected 10 categories, parsed {len(cats)}"
if control:
    cats = [(n, t, "Refresh · watchlist · feeds · mutes" if n == "CONTENT" else b, g, k)
            for n, t, b, g, k in cats]
pathlib.Path("cats.tsv").write_text("".join("\t".join(r) + "\n" for r in cats))

screen = (root / "app/src/main/java/dev/mascwa/pulse/feature/settings/SettingsScreen.kt").read_text()
vis = re.findall(r'vis\(\s*SettingsCategory\.([A-Z_]+),\s*"([^"]*)"', screen, re.S)
seen, sections = set(), []
for c, k in vis:
    if c == "CONTENT" and k not in seen:
        seen.add(k); sections.append(k)
assert len(sections) == 5, f"expected 5 CONTENT sections, parsed {len(sections)}"
pathlib.Path("content_sections.tsv").write_text("".join(k + "\n" for k in sections))

dirs = re.sub(r'//[^\n]*', '', (root / "app/src/main/java/dev/mascwa/pulse/navigation/Directory.kt").read_text())
entries = re.findall(r'MenuEntry\(\s*"([^"]*)",\s*"([^"]*)",\s*Routes\.([A-Z_]+),\s*listOf\(([^)]*)\)', dirs, re.S)
assert entries, "parsed no MenuEntry rows"
rows = []
for label, desc, route, terms in entries:
    ts = re.findall(r'"([^"]*)"', terms)
    if control and route == "SETTINGS":
        ts = [t for t in ts if t not in {"email", "mail", "texts", "sms", "inbox", "notifications"}]
    rows.append(f"{label}\t{desc}\t{' '.join(ts)}")
pathlib.Path("menu.tsv").write_text("\n".join(rows) + "\n")
print(f"extracted {len(cats)} categories, {len(sections)} CONTENT sections, {len(entries)} menu entries"
      + (" (CONTROL: pre-fix strings)" if control else ""))
PY

KC=/opt/gradle-8.14.3/lib/kotlin-compiler-embeddable-2.0.21.jar
STD=/opt/gradle-8.14.3/lib/kotlin-stdlib-2.0.21.jar
TRV=/opt/gradle-8.14.3/lib/trove4j-1.0.20200330.jar
ANN=/opt/gradle-8.14.3/lib/annotations-24.0.1.jar
GC="$HOME/.gradle/caches/modules-2/files-2.1"
COR=$(find "$GC" -name 'kotlinx-coroutines-core-jvm-*.jar' 2>/dev/null | head -1)
JSOUP=$(find "$GC" -name 'jsoup-*.jar' 2>/dev/null | head -1)
SER=$(find "$GC/org.jetbrains.kotlinx" -name 'kotlinx-serialization-core-jvm-*.jar' 2>/dev/null | head -1)
SERJ=$(find "$GC/org.jetbrains.kotlinx" -name 'kotlinx-serialization-json-jvm-*.jar' 2>/dev/null | head -1)
SPLUG=$(find "$HOME/.gradle/caches/modules-2" -name 'kotlin-serialization-compiler-plugin-embeddable-*.jar' 2>/dev/null | head -1)
for j in "$KC" "$STD" "$TRV" "$ANN" "$COR" "$JSOUP" "$SER" "$SERJ" "$SPLUG"; do
  [ -n "$j" ] && [ -f "$j" ] || { echo "missing a required jar: '$j'"; exit 2; }
done

# ⚠️ `|| true`: grep -L exits 1 when any file MATCHED, so a correct listing looks like a failure.
CORE=$(cd "$ROOT" && grep -rLE '^import android[.x]?' core/telemetry/src/main --include='*.kt' || true)
[ -n "$CORE" ] || { echo "found no core sources"; exit 2; }
OUT=$(mktemp -d)
CP="$STD:$JSOUP:$SER:$SERJ"
(cd "$ROOT" && java -cp "$KC:$STD:$TRV:$ANN:$COR" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -nowarn -Xplugin="$SPLUG" -d "$OUT" -cp "$CP" $CORE scratchpad/mailfind/Probe.kt 2>&1 |
  grep -Ev '^(warning|info):' || true)
[ -n "$(find "$OUT" -name '*.class' -print -quit)" ] || { echo "NOTHING COMPILED — see errors above"; exit 3; }
# ⚠️ From the repo root: the probe reads its TSVs at scratchpad/mailfind/*, relative to the CWD.
(cd "$ROOT" && java -cp "$OUT:$CP" dev.mascwa.pulse.core.telemetry.MailFindProbe 2>&1 | grep -v JAVA_TOOL_OPTIONS)
