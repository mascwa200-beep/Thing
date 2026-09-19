#!/usr/bin/env bash
# Negative-test one rule of the coverage-gate family.
#
#   ./neg.sh <case>
#
# Each case asserts the baseline is GREEN first, perturbs the shipped source, asserts the
# perturbation MATCHED, runs the gate, and restores under a `trap ... EXIT`.
#
# ⚠️ One case per invocation, deliberately. A harness whose total runtime can exceed the tool's
# timeout is SIGTERMed, and `finally` inside python does not run — this project has left a
# perturbation in the tree exactly that way.
#
# ⚠️ The baseline check is not ceremony. A suite that is ALREADY failing reports every guard
# "awake" for a reason that has nothing to do with the perturbation — the fifth recorded way a
# green test proves nothing, and it has cost a full round here before.
set -uo pipefail
ROOT=/home/user/Thing
GATE=$ROOT/app/src/test/java/dev/mascwa/pulse/testing/SourceGate.kt
RULES=$ROOT/core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/AmbientRules.kt
MATRIX=$ROOT/app/src/main/java/dev/mascwa/pulse/jarvis/matrix/ActiveMatrixService.kt
ACT=$ROOT/app/src/main/java/dev/mascwa/pulse/data/sensing/AmbientActuator.kt
ORC=$ROOT/app/src/test/java/dev/mascwa/pulse/data/oracle/OracleSignalCoverageTest.kt
CASE=${1:?usage: neg.sh <case>}

BAK=$(mktemp -d)
for f in "$GATE" "$RULES" "$MATRIX" "$ACT" "$ORC"; do cp "$f" "$BAK/$(basename "$f")"; done
restore() {
  for f in "$GATE" "$RULES" "$MATRIX" "$ACT" "$ORC"; do cp "$BAK/$(basename "$f")" "$f"; done
  for f in "$GATE" "$RULES" "$MATRIX" "$ACT" "$ORC"; do
    cmp -s "$BAK/$(basename "$f")" "$f" || { echo "!! RESTORE FAILED — tree is perturbed: $f"; exit 9; }
  done
  rm -rf "$BAK"
}
trap restore EXIT

# ⚠️ A case that perturbs CORE SOURCE must run the core suite. The app-module runner compiles
# against `core/telemetry/build/classes/kotlin/main` — the COMPILED core — so an edit to core
# source is invisible to it and every app gate reports "asleep" for a reason that has nothing to do
# with the rule under test. Cost one run to discover.
case "$CASE" in
  dead-action|sweep-cannot-fail|new-unwired-action) WHICH=core ;;
  *) WHICH=app ;;
esac

run_suite() {
  if [ "$WHICH" = core ]; then
    ( cd "$ROOT" && timeout 600 ./scratchpad/coretest/run.sh \
        core/telemetry/src/test/java/dev/mascwa/pulse/core/telemetry/AmbientRulesTest.kt 2>&1 )
  else
    ( cd "$ROOT" && timeout 600 bash /tmp/rungates.sh 2>&1 )
  fi
}

BASE=$(run_suite)
if ! grep -q '^OK (' <<<"$BASE"; then
  echo "!! BASELINE IS NOT GREEN — nothing this run reports means anything"
  grep -E '^e:|error:|FAILURES|^[0-9]+\) ' <<<"$BASE" | head -5
  exit 1
fi
echo "baseline: $(grep -o 'OK ([0-9]* tests)' <<<"$BASE")"

python3 - "$CASE" "$GATE" "$RULES" "$MATRIX" "$ACT" "$ORC" <<'PY'
import sys, pathlib
case = sys.argv[1]
gate, rules, matrix, act, orc = [pathlib.Path(p) for p in sys.argv[2:]]
def sub(path, old, new, n=1):
    t = path.read_text()
    assert t.count(old) >= n, f"PERTURBATION DID NOT MATCH ({path.name}): {old[:70]!r}"
    path.write_text(t.replace(old, new, n))

if case == "new-unwired-action":
    # ⚠️ The CLEANEST isolation, and the true shape of what shipped five times: a member added to
    # the closed list, given a label, an undo and a ceiling, and asked for by no rule. Everything
    # else stays green — the existing thirty-odd tests are all about rules that DO fire, so none of
    # them can see this. That is precisely the gap.
    sub(rules, "    TORCH_ON(ActionTier.PHONE,",
               "    NEW_UNWIRED(ActionTier.APP, \"did a thing\", \"undo the thing\", 10 * MINUTE),\n"
               "    TORCH_ON(ActionTier.PHONE,")
elif case == "dead-action":
    # An action a rule used to ask for and now nothing does — the shape five deleted members shipped in.
    sub(rules, "out += intent(s, AmbientAction.TORCH_ON,", "if (false) out += intent(s, AmbientAction.TORCH_ON,")
elif case == "sweep-cannot-fail":
    # If `decide` returned something for every input, "every action is asked for" would pass while
    # testing nothing. The guard is that a situation with no rule asks for nothing at all.
    sub(rules, "private fun company(s: AmbientSignals)",
               "private fun walkingBogus(s: AmbientSignals): List<AmbientIntent> =\n"
               "        listOf(intent(s, AmbientAction.STAY_SILENT, \"bogus\"))\n\n"
               "    private fun company(s: AmbientSignals)")
    sub(rules, "            else -> emptyList()", "            Situation.WALKING -> walkingBogus(s)\n            else -> emptyList()")
elif case == "strip-off":
    sub(gate, "    fun stripComments(src: String): String = src\n"
              "        .replace(Regex(\"\"\"/\\*.*?\\*/\"\"\", RegexOption.DOT_MATCHES_ALL), \" \")\n"
              "        .replace(Regex(\"\"\"//[^\\n]*\"\"\"), \" \")",
              "    fun stripComments(src: String): String = src")
elif case == "strip-order":
    sub(gate, "        .replace(Regex(\"\"\"/\\*.*?\\*/\"\"\", RegexOption.DOT_MATCHES_ALL), \" \")\n"
              "        .replace(Regex(\"\"\"//[^\\n]*\"\"\"), \" \")",
              "        .replace(Regex(\"\"\"//[^\\n]*\"\"\"), \" \")\n"
              "        .replace(Regex(\"\"\"/\\*.*?\\*/\"\"\", RegexOption.DOT_MATCHES_ALL), \" \")")
elif case == "no-require":
    sub(gate, "        require(dir.isDirectory) {", "        if (false) require(dir.isDirectory) {")
elif case == "lost-consumer":
    # An action wired to nothing: the hold shows in the scanner and on the board, and nothing holds.
    sub(matrix, "if (AmbientHolds.isHeld(AmbientAction.STAY_SILENT)) return",
                "if (false) return")
elif case == "undocumented-exemption":
    # An exemption whose named place does not explain itself — the loophole an allowlist rots through.
    sub(act, "**has no consumer**", "**is not wired**")
elif case == "stale-exemption":
    # The other direction: the action gets a consumer and the exemption is left behind, so the gate
    # quietly stops covering it for ever after.
    sub(matrix, "    private fun handleBattery(", "    private fun unused() = AmbientAction.SPEAK_DONT_BUZZ\n\n    private fun handleBattery(")
elif case == "oracle-nostrip":
    sub(orc, "        val src = SourceGate.stripComments(rawSrc)", "        val src = rawSrc")
else:
    raise SystemExit(f"unknown case {case}")
print(f"perturbed: {case}")
PY
[ $? -eq 0 ] || exit 1

OUT=$(run_suite)
if grep -qE '^e:|error:|NOTHING COMPILED' <<<"$OUT"; then
  echo "  !! DID NOT COMPILE — this is not evidence a guard fired"
  grep -E '^e:|error:|NOTHING COMPILED' <<<"$OUT" | head -4
elif grep -q 'FAILURES!!!' <<<"$OUT"; then
  echo "  GUARD AWAKE — which test failed:"
  grep -E '^[0-9]+\) ' <<<"$OUT" | head -4
  grep -E 'AssertionError' <<<"$OUT" | head -2 | cut -c1-200
else
  echo "  !! GUARD ASLEEP — the suite still passes with this rule removed"
fi
