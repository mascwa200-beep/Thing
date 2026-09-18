#!/usr/bin/env bash
# Negative tests for HoldCapability's two load-bearing rules. Same discipline as negrules.sh:
# the baseline is asserted green FIRST (a suite already failing makes every case meaningless), the
# perturbation is asserted to have matched the source, and the restore is a shell trap so a kill
# between cases cannot leave a perturbation in the tree.
set -uo pipefail
cd "$(dirname "$0")/../.."

SRC=app/src/main/java/dev/mascwa/pulse/data/sensing/HoldCapability.kt
TEST=app/src/test/java/dev/mascwa/pulse/data/sensing/HoldCapabilityTest.kt
BAK=$(mktemp)
cp "$SRC" "$BAK"
trap 'cp "$BAK" "$SRC"; rm -f "$BAK"' EXIT

run() { ./scratchpad/sensecontext/apptest.sh "$TEST" "$SRC" 2>&1 | grep -v JAVA_TOOL_OPTIONS; }

echo "== baseline =="
run | tail -3
run | grep -q "^OK (" || { echo "BASELINE IS NOT GREEN — stopping"; exit 2; }
echo

rc=0
case_() { # name, old, new, must-fail-substring
  local name="$1" old="$2" new="$3" want="$4"
  python3 - "$SRC" "$old" "$new" <<'PY' || { echo "[$name] PERTURBATION DID NOT MATCH — case invalid"; rc=1; return; }
import sys, pathlib
p, old, new = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3]
s = p.read_text()
n = s.count(old)
assert n == 1, f"{n} occurrences"
p.write_text(s.replace(old, new))
PY
  local out; out=$(run)
  cp "$BAK" "$SRC"
  if grep -q "^OK (" <<<"$out"; then
    echo "[$name]  ⚠️ GUARD ASLEEP — the suite passed with the rule removed"; rc=1
  elif grep -q "$want" <<<"$out"; then
    echo "[$name]  awake — failed: $(grep -oE '^[0-9]+\) [^(]+' <<<"$out" | tr '\n' ' ')"
  else
    echo "[$name]  ⚠️ failed, but NOT the named test"; echo "$out" | head -20; rc=1
  fi
}

[ "${1:-all}" = "2" ] || case_ "any layer can block, not only the first" \
  'layers.firstNotNullOfOrNull { it.cannotDo(i.action) }' \
  'layers.first().cannotDo(i.action)' \
  'any layer can block'

[ "${1:-all}" = "1" ] || case_ "a blocked hold is MOVED to refused, never dropped" \
  'return copy(allowed = allowed.filter { it.action !in barred }, refused = refused + blocked)' \
  'return copy(allowed = allowed.filter { it.action !in barred })' \
  'refused with its reason, not dropped'

cmp -s "$SRC" "$BAK" || { echo "!! the source was NOT restored"; rc=1; }
exit $rc
