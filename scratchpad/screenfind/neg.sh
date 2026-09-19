#!/usr/bin/env bash
# Negative-test one rule of synonymsAreMatchedButNeverPartOfTheDrawnSummary.
#
# One case per invocation, because a harness whose total runtime can exceed the tool timeout is
# SIGTERMed and `finally` does not run — the recorded sixth way a green test proves nothing. The
# restore is a shell `trap ... EXIT` for the same reason, and it is byte-compared.
#
# Usage: ./neg.sh <1|2|3|4|5>
set -euo pipefail
ROOT=/home/user/Thing
CASE="${1:?usage: neg.sh <1|2|3|4|5>}"

CORE="$ROOT/core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/DeviceSearch.kt"
GS="$ROOT/core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/GuideSearch.kt"

run() {
  cd "$ROOT"
  timeout 900 ./gradlew :core:telemetry:test --tests '*DeviceSearchTest*' \
    --configure-on-demand --no-configuration-cache --rerun-tasks -q >/dev/null 2>&1 || true
  python3 - <<'PY'
import glob, xml.etree.ElementTree as ET
tot = fail = 0
names = []
for f in glob.glob('core/telemetry/build/test-results/test/*DeviceSearch*.xml'):
    r = ET.parse(f).getroot()
    tot += int(r.get('tests')); fail += int(r.get('failures')) + int(r.get('errors'))
    for tc in r.iter('testcase'):
        if [c for c in tc if c.tag in ('failure','error')]:
            names.append(tc.get('name'))
print(f"tests={tot} failures={fail}")
for n in names: print("  FAILED:", n)
PY
}

echo "--- baseline (must be green, or every later verdict is worthless)"
BASE=$(run); echo "$BASE"
grep -q 'failures=0' <<<"$BASE" || { echo "BASELINE NOT GREEN — stopping"; exit 1; }

case "$CASE" in
  1) TARGET="$CORE"; OLD='            headings = terms,'; NEW='            headings = emptyList(),'
     WHY="of() stops carrying the terms at all" ;;
  2) TARGET="$CORE"; OLD='            summary = body,'; NEW='            summary = (listOf(body) + terms).joinToString(" "),'
     WHY="of() joins the terms back into the drawn summary (the shipped phone defect)" ;;
  3) TARGET="$GS"; OLD='    private const val W_HEADING = 5'; NEW='    private const val W_HEADING = 2'
     WHY="a heading stops outweighing a summary" ;;
  4) TARGET="$CORE"; OLD='        FEATURE("Feature", "menu", destination = true),'; NEW='        FEATURE("Feature", "menu"),'
     WHY="a screen stops being a destination, so it sinks back into the flat list" ;;
  5) TARGET="$CORE"; OLD='        GUIDE("Guide", "survival"),'; NEW='        GUIDE("Guide", "survival", destination = true),'
     WHY="a reading is marked a destination — the mistake with consequences, since its id is a document id and a tap would navigate to nonsense" ;;
  *) echo "unknown case"; exit 1 ;;
esac

cp "$TARGET" /tmp/neg.bak
trap 'cp /tmp/neg.bak "$TARGET"; cmp -s /tmp/neg.bak "$TARGET" && echo "restored byte-identical" || echo "RESTORE FAILED"' EXIT

grep -qF "$OLD" "$TARGET" || { echo "PERTURBATION DID NOT MATCH THE SOURCE — verdict worthless"; exit 1; }
python3 - "$TARGET" "$OLD" "$NEW" <<'PY'
import sys
p, old, new = sys.argv[1], sys.argv[2], sys.argv[3]
s = open(p).read()
assert s.count(old) == 1, f"expected exactly 1 occurrence, found {s.count(old)}"
open(p, 'w').write(s.replace(old, new))
print("perturbation applied:", old.strip(), "->", new.strip())
PY

echo "--- perturbed: $WHY"
run
