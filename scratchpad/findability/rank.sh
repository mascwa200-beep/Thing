#!/usr/bin/env bash
# Does a real screen ever lose its place to a Settings row, and do the trapped words now reach one?
#
# Extracts the SHIPPED literals out of FeatureCatalog.kt / Directory.kt / SettingsCategory.kt /
# SettingsSections.kt, then runs the SHIPPED GuideSearch + DeviceSearch over them. Stronger than a
# unit test: it scores the strings that are actually in the tree rather than a paraphrase, which is
# the only way to answer a ranking question honestly.
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
app = root / "app/src/main/java/dev/mascwa/pulse"

def strip(s):
    s = re.sub(r'/\*.*?\*/', ' ', s, flags=re.S)
    return re.sub(r'//[^\n]*', ' ', s)

# --- real features: FeatureCatalog.OFF_MENU + every Directory MenuEntry -----------------------
fc = strip((app / "data/usage/FeatureCatalog.kt").read_text())
off = re.findall(r'FeatureMeta\(Routes\.(\w+),\s*"([^"]*)",\s*"([^"]*)"\)', fc)
assert len(off) >= 8, f"OFF_MENU parsed {len(off)}"

dr = strip((app / "navigation/Directory.kt").read_text())
entries = []
for m in re.finditer(r'MenuEntry\(\s*"([^"]*)",\s*"([^"]*)",\s*Routes\.(\w+)\s*(?:,\s*listOf\(([^)]*)\))?', dr, re.S):
    label, desc, route, terms = m.groups()
    words = " ".join(re.findall(r'"([^"]*)"', terms or ""))
    # ⚠️ The id is the Routes CONSTANT lowercased, which is this repo's route-naming convention.
    # Ids are never scored, so this only has to be good enough to compare a destination against a
    # `settings?cat=…` row — an exact route table would need the Kotlin object evaluated.
    entries.append((route.lower(), label, f"{desc} {words}".strip()))
assert len(entries) >= 25, f"Directory parsed {len(entries)} entries"

feats = [(r.lower(), l, p) for r, l, p in off] + entries
pathlib.Path("features.tsv").write_text("".join("\t".join(x) + "\n" for x in feats))

# --- the 10 Settings categories, exactly as DeviceSearchIndex emits them ----------------------
sc = strip((app / "feature/settings/SettingsCategory.kt").read_text())
cats = re.findall(
    r'^\s{4}([A-Z]+)\("([^"]*)",\s*"([^"]*)",\s*"([^"]*)",\s*[^,\n]+,\s*\n?\s*"([^"]*)"\)', sc, re.M)
assert len(cats) == 10, f"parsed {len(cats)} categories"
assert any("accessibility" in b for _, _, b, _, _ in cats), "category blurb extractor is broken"
cat_rows_raw = [(f"settings?cat={n.lower()}", f"Settings · {t}", f"{b} {k}") for n, t, b, _, k in cats]
pathlib.Path("cats_raw.tsv").write_text("".join("\t".join(x) + "\n" for x in cat_rows_raw))
cat_titles = {t for _, t, _, _, _ in cats}

# --- the sections, exactly as SettingsSections.searchRecords() emits them ---------------------
ss = strip((app / "feature/settings/SettingsSections.kt").read_text())
sec = re.findall(
    r'val [A-Z_]+ = SettingsSection\(\s*"[a-z_]+",\s*SettingsCategory\.([A-Z]+),\s*"([^"]*)",\s*\n'
    r'((?:\s*"(?:[^"\\]|\\.)*"\s*[+,]\s*\n)+)', ss)
assert len(sec) == 26, f"parsed {len(sec)} sections"
assert any("sponsorblock" in k for _, _, k in sec), "section keyword extractor is broken"

STEM_FLOOR = 4
def already_said(t, b):
    return t == b or (len(t) >= STEM_FLOOR and len(b) >= STEM_FLOOR and (t.startswith(b) or b.startswith(t)))

def row(route, name, words):
    """Mirrors SettingsSections.row(): body = words MINUS anything the title already claims,
    using GuideSearch's own stem rule rather than plain equality."""
    title = f"Settings · {name}"
    said = [w for w in re.split(r'[^a-z0-9]+', title.lower()) if w]
    body = " ".join(w for w in re.split(r'\s+', words)
                    if w and not any(already_said(t, w.lower()) for t in said))
    return (route, title, body)

sec_raw, sec_parsed = [], []
for cat, title, kwexpr in sec:
    kw = "".join(re.findall(r'"((?:[^"\\]|\\.)*)"', kwexpr))
    sec_parsed.append((cat, title, kw))
    if title in cat_titles:
        continue                      # searchRecords() skips these: they duplicate the category row
    sec_raw.append((f"settings?cat={cat.lower()}", f"Settings · {title}", f"{title} {kw}"))
pathlib.Path("sections_raw.tsv").write_text("".join("\t".join(x) + "\n" for x in sec_raw))

after = [row(f"settings?cat={n.lower()}", t, f"{b} {k}") for n, t, b, _, k in cats]
after += [row(f"settings?cat={c.lower()}", t, kw) for c, t, kw in sec_parsed if t not in cat_titles]
pathlib.Path("settings_after.tsv").write_text("".join("\t".join(x) + "\n" for x in after))
print(f"extracted: {len(feats)} features, {len(cat_rows_raw)} categories, {len(sec_raw)} sections "
      f"({26 - len(sec_raw)} skipped as duplicates of their category row); AFTER rows: {len(after)}")

# --- the gap words: section vocabulary absent from everything device search could see ---------
visible = set()
for text in (fc, dr, sc):
    visible |= set(re.findall(r'[a-z0-9-]+', " ".join(re.findall(r'"((?:[^"\\\n]|\\.)*)"', text)).lower()))
secwords = set()
for _, _, kw in sec_parsed:
    secwords |= set(re.findall(r'[a-z0-9-]+', kw.lower()))
gap = sorted(w for w in secwords if w not in visible and len(w) > 2)
pathlib.Path("gapwords.tsv").write_text("\n".join(gap) + "\n")
print(f"gap words (section vocabulary invisible to device search): {len(gap)}")
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
