#!/usr/bin/env bash
# Negative-test the rules SettingsSectionCoverageTest's control gate rests on.
#
# Baseline asserted green first. Each case perturbs ONE rule, asserts the substitution actually
# landed (a perturbation that silently fails to match reports a sleeping guard as awake), runs the
# suite, and requires the NAMED test to be the one that fails — "the build failed" is not evidence.
# Restore is a shell `trap … EXIT` and is byte-compared, so a timeout cannot leave a perturbation.
set -uo pipefail
ROOT=/home/user/Thing
cd "$ROOT"
T=app/src/test/java/dev/mascwa/pulse/feature/settings/SettingsSectionCoverageTest.kt
S=app/src/main/java/dev/mascwa/pulse/feature/settings/SettingsSections.kt
BK=$(mktemp -d); cp "$T" "$BK/t.kt"; cp "$S" "$BK/s.kt"
restore() {
  cp "$BK/t.kt" "$T"; cp "$BK/s.kt" "$S"
  cmp -s "$BK/t.kt" "$T" && cmp -s "$BK/s.kt" "$S" \
    && echo "restored byte-identical" || echo "⚠️ RESTORE FAILED — CHECK THE TREE"
}
trap restore EXIT

run_case() {  # name, file, old, new, expected-failing-test-fragment
  local name=$1 file=$2 old=$3 new=$4 want=$5
  echo "=============================================================="
  echo "CASE: $name"
  # ⚠️ old/new as SEPARATE arguments: bash cannot pass a NUL through argv, so a single
  # "old\x00new" string silently arrives as one field and every case reports itself invalid.
  python3 - "$file" "$old" "$new" <<'PY' || { echo "  ⚠️ PERTURBATION INVALID — proves nothing"; return; }
import sys, pathlib
p, old, new = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3]
s = p.read_text()
assert s.count(old) == 1, f"matched {s.count(old)}x, need exactly 1: {old[:60]!r}"
p.write_text(s.replace(old, new)); print("  perturbation applied")
PY
  local out
  out=$(./scratchpad/ctrlfind/run_gate.sh 2>&1)
  if echo "$out" | grep -q '^OK ('; then
    echo "  ❌ ASLEEP — suite still green with the rule removed"
  else
    local failed
    failed=$(echo "$out" | grep -oE '^[0-9]+\) [a-zA-Z ]+' | sed 's/^[0-9]*) //' | tr '\n' '|')
    echo "  failing: ${failed:-<compile error>}"
    if echo "$failed" | grep -qi "$want"; then echo "  ✅ AWAKE — '$want' failed"
    else echo "  ⚠️ failed, but not '$want'"; fi
  fi
  cp "$BK/t.kt" "$T"; cp "$BK/s.kt" "$S"
}

case "${1:-all}" in
a)
run_case "a word added for a control is taken back out" "$S" \
  "newsapi fred eia finnhub openweathermap nasa" "newsapi fred openweathermap nasa" \
  "found by its own name"
run_case "bodyEnd stops excluding the private helpers" "$T" \
  "if (at > end) continue" "if (false) continue" \
  "shapes that have broken it before"
;;
b)
run_case "the generic-declaration control is dropped from the list" "$T" \
  '"EditableValueRow", "SingleChoiceRow", "AddTextRow")' '"EditableValueRow", "AddTextRow")' \
  "shapes that have broken it before"
run_case "the hyphen-collapsed token form is removed" "$T" \
  "return (plain + joined).toSet()" "return plain.toSet()" \
  "found by its own name"
;;
c)
run_case "reaches() drops the stem rule" "$T" \
  "                token.length >= 4 &&" "                false &&" \
  "found by its own name"
run_case "the fold is reverted to a plain filter" "$S" \
  "val (folded, ownRow) = ALL.partition { it.title == it.category.title }" \
  "val folded = emptyList<SettingsSection>(); val ownRow = ALL.filter { it.title != it.category.title }" \
  "declares its own quirks"
;;
esac
echo "=============================================================="
