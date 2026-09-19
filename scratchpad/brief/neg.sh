#!/usr/bin/env bash
# Negative-test one rule of UnifiedBriefTest's new kind-coverage pair.
#
# One case per invocation, and the restore is a shell `trap ... EXIT` byte-compared, because a
# harness whose runtime exceeds the tool timeout is SIGTERMed and a language-level finally does not
# run. Output is NOT filtered: a perturbation that fails to COMPILE is not evidence a guard is
# awake, and a grep for assertion text cannot see a compiler error — that read as a sleeping guard
# once already this session.
#
# Usage: ./neg.sh <1|2>
set -uo pipefail
ROOT=/home/user/Thing
CASE="${1:?usage: neg.sh <1|2>}"
MAIN="$ROOT/core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/UnifiedBrief.kt"

run() {
  (cd "$ROOT" && ./gradlew :core:telemetry:test --tests '*UnifiedBriefTest*' \
      --configure-on-demand --no-configuration-cache --console=plain 2>&1 | tail -5)
  python3 - <<'PY'
import glob, xml.etree.ElementTree as ET
fs = glob.glob('/home/user/Thing/core/telemetry/build/test-results/test/*UnifiedBrief*.xml')
if not fs:
    print("NO RESULT FILE — did it compile? (a perturbation that does not build proves nothing)")
else:
    for f in fs:
        r = ET.parse(f).getroot()
        print(f"  {r.get('tests')} tests, {r.get('failures')} failures, {r.get('errors')} errors")
        for tc in r.iter('testcase'):
            fail = tc.find('failure')
            if fail is not None:
                print("  FAILED:", tc.get('name'))
                print("   ", (fail.get('message') or '').strip().replace('\n', ' ')[:300])
PY
}

echo "--- baseline (must be green, or every later verdict is worthless)"
BASE=$(run); echo "$BASE"
grep -q '0 failures, 0 errors' <<<"$BASE" || { echo "BASELINE NOT GREEN — stopping"; exit 1; }

cp "$MAIN" /tmp/brief.bak
trap 'cp /tmp/brief.bak "$MAIN"; cmp -s /tmp/brief.bak "$MAIN" && echo "restored byte-identical" || echo "RESTORE FAILED"' EXIT

python3 - "$CASE" "$MAIN" <<'PY'
import sys
case, path = sys.argv[1], sys.argv[2]
EDITS = {
    # THE REAL DRIFT: a ninth kind, and nobody adds it to DROPPABLE. Every other test in the file
    # builds a fixture of named kinds, so all of them stay green while this kind quietly becomes
    # undroppable and outranks AGENDA on a full board.
    "1": ("enum class BriefRowKind { ALERT, NEWS, MARKETS, WEATHER, AGENDA, ADVISORY, LESSON, HEALTH }",
          "enum class BriefRowKind { ALERT, NEWS, MARKETS, WEATHER, AGENDA, ADVISORY, LESSON, HEALTH, TRAFFIC }",
          "a ninth kind arrives with no DROPPABLE entry"),
    # Shed order is the whole judgement about what matters; swapping two entries must not pass.
    "2": ("BriefRowKind.LESSON, BriefRowKind.HEALTH, BriefRowKind.MARKETS, BriefRowKind.NEWS,",
          "BriefRowKind.HEALTH, BriefRowKind.LESSON, BriefRowKind.MARKETS, BriefRowKind.NEWS,",
          "the shed order is silently reversed for its first two entries"),
}
old, new, why = EDITS[case]
s = open(path).read()
n = s.count(old)
assert n == 1, f"PERTURBATION DID NOT MATCH THE SOURCE ({n} occurrences) — verdict worthless"
open(path, "w").write(s.replace(old, new))
print("perturbation applied:", why)
PY
[ $? -eq 0 ] || exit 1

echo "--- perturbed"
run
