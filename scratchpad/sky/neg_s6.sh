#!/usr/bin/env bash
# Negative-test one S6 load-bearing rule: assert the baseline suite is green, perturb the shipped
# source (python asserts the anchor matched EXACTLY once), run the suite, name which tests failed,
# and restore the file — in this shell's EXIT trap, byte-compared, so a killed run cannot leave a
# perturbation in the tree.
#
#   ./scratchpad/sky/neg_s6.sh <case>        one case per invocation, deliberately
#
# Cases live in the python block below as (file, old, new, test class, expected failing tests).
set -uo pipefail
cd "$(dirname "$0")/../.."
CASE="${1:?case name}"
S=core/sky/src/main/java/dev/mascwa/pulse/sky
T=core/sky/src/test/java/dev/mascwa/pulse/sky
SKY="$S/GroundShape.kt $S/SkyCardText.kt $S/SkyGrids.kt $S/SkyLines.kt $S/SkyClock.kt"

run_suite() { # <TestClass>
  /tmp/skytest.sh "dev.mascwa.pulse.sky.$1" $SKY -- "$T/$1.kt" 2>&1 | grep -vE '^Picked up|^warning:'
}

# Which file and suite this case touches — printed by python so the table lives in one place.
read -r FILE CLASS < <(python3 scratchpad/sky/neg_s6_cases.py "$CASE" --where)
[ -n "$FILE" ] && [ -n "$CLASS" ] || { echo "unknown case $CASE"; exit 2; }

echo "== $CASE  ($FILE → $CLASS)"
BASE=$(run_suite "$CLASS")
echo "$BASE" | grep -q '^OK (' || { echo "BASELINE NOT GREEN — refusing to test:"; echo "$BASE" | tail -5; exit 2; }
echo "baseline: $(echo "$BASE" | grep '^OK (')"

BAK=$(mktemp)
cp "$FILE" "$BAK"
trap 'cp "$BAK" "$FILE"; cmp -s "$BAK" "$FILE" && echo "restored byte-identical" || echo "RESTORE FAILED"; rm -f "$BAK"' EXIT

python3 scratchpad/sky/neg_s6_cases.py "$CASE" --apply || { echo "PERTURBATION DID NOT APPLY"; exit 2; }

OUT=$(run_suite "$CLASS")
if echo "$OUT" | grep -qE '(^e: |: error:)'; then
  echo "INVALID PERTURBATION — did not compile (not evidence of anything):"
  echo "$OUT" | grep -E '(^e: |: error:)' | head -3
  exit 3
fi
echo "$OUT" | python3 scratchpad/sky/neg_s6_cases.py "$CASE" --judge
