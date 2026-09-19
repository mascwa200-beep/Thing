#!/usr/bin/env bash
# Negative-test one rule of the two Oracle reachability gates.
#
#   ./neg.sh <case>
#
# Each case perturbs the shipped source, asserts the perturbation MATCHED (a substitution that
# silently applied to nothing reports a guard "awake" when nothing was tested), runs the gate, and
# restores under a `trap ... EXIT` so a kill or a timeout cannot leave the tree perturbed.
#
# ⚠️ One case per invocation, deliberately. A harness whose total runtime can exceed the tool's
# timeout is SIGTERMed, and `finally` inside python does not run — this project has left a
# perturbation in the tree exactly that way.
set -uo pipefail
ROOT=/home/user/Thing
ORACLE=$ROOT/core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/Oracle.kt
ENGINE=$ROOT/app/src/main/java/dev/mascwa/pulse/data/oracle/OracleEngine.kt
COV=$ROOT/app/src/test/java/dev/mascwa/pulse/data/oracle/OracleSignalCoverageTest.kt
CASE=${1:?usage: neg.sh <case>}
BAK=$(mktemp -d)
cp "$ORACLE" "$BAK/O.kt"; cp "$ENGINE" "$BAK/E.kt"; cp "$COV" "$BAK/C.kt"
restore() {
  cp "$BAK/O.kt" "$ORACLE"; cp "$BAK/E.kt" "$ENGINE"; cp "$BAK/C.kt" "$COV"
  cmp -s "$BAK/O.kt" "$ORACLE" && cmp -s "$BAK/E.kt" "$ENGINE" && cmp -s "$BAK/C.kt" "$COV" \
    || { echo "!! RESTORE FAILED — tree is perturbed"; exit 9; }
  rm -rf "$BAK"
}
trap restore EXIT

WHICH=core
python3 - "$CASE" "$ORACLE" "$ENGINE" "$COV" <<'PY'
import sys, pathlib
case, o, e, c = sys.argv[1], *[pathlib.Path(p) for p in sys.argv[2:]]
def sub(path, old, new, n=1):
    t = path.read_text()
    assert t.count(old) >= n, f"PERTURBATION DID NOT MATCH ({path.name}): {old[:70]!r}"
    path.write_text(t.replace(old, new, n))

if case == "dead-rule":
    # A rule that can never fire: the shape `tempoNudge` and the vitals gate really shipped in.
    sub(o, "    private fun windDown(s: OracleSignals): Insight? {\n",
           "    private fun windDown(s: OracleSignals): Insight? {\n        if (true) return null\n")
elif case == "rare-rule":
    # Not dead, just vanishingly rare — the case the FLOOR exists for, as distinct from the
    # reachability assertion above it.
    #
    # ⚠️ The modulus is chosen by arithmetic, not by taste. `windDown` requires hour >= 23, so
    # minuteOfDay is 1380..1439; `% 30 == 0` keeps two of those sixty minutes, cutting its ~1780
    # hits in 120,000 draws to roughly 59 — under the floor of 120 and comfortably above zero.
    # The first attempt used `% 997`, and NEITHER 0 nor 997 lies in 1380..1439, so the rule fired
    # exactly zero times and the reachability test caught it instead: the floor was never
    # exercised and reported awake on a case it never saw.
    sub(o, "        if (!s.isNight || s.hourOfDay < 23) return null",
           "        if (!s.isNight || s.hourOfDay < 23 || s.minuteOfDay % 30 != 0) return null")
elif case == "limit":
    # `divine` ranks and truncates; sweeping at the DEFAULT limit hides rules that did fire.
    sub(o, "        val seen = LinkedHashMap<String, Int>()", "        val seen = LinkedHashMap<String, Int>()")
    raise SystemExit("case `limit` belongs to the core test file, not Oracle.kt")
elif case == "unpopulated-signal":
    # A field declared and never set by the engine — exactly `windKmh`.
    sub(e, "windKmh = ", "windKmhDISABLED = ")
elif case == "bogus-exemption":
    # An exemption naming a platform that does NOT populate the field: the loophole that would let
    # an allowlist hide a field set by nobody.
    #
    # ⚠️ ADDED, never substituted for a real entry. The first attempt REPLACED `ledgerHeadline`,
    # which simply removed a legitimate exemption — so the coverage test failed on ledgerHeadline
    # and the exemption-verification test was never reached, reporting "awake" on a guard it had
    # not touched. `batteryPct` is chosen because it is set by the phone (so the coverage test
    # stays green and cannot mask this) and measurably NOT set by the desktop gatherer, which is
    # exactly the false claim the check exists to refuse.
    sub(c, '        "ledgerBits" to desktopGatherer,',
           '        "ledgerBits" to desktopGatherer,\n        "batteryPct" to desktopGatherer,')
else:
    raise SystemExit(f"unknown case {case}")
print(f"perturbed: {case}")
PY
[ $? -eq 0 ] || exit 1

case "$CASE" in
  dead-rule|rare-rule) WHICH=core ;;
  *) WHICH=app ;;
esac

cd "$ROOT"
if [ "$WHICH" = core ]; then
  OUT=$(timeout 540 ./scratchpad/coretest/run.sh \
      core/telemetry/src/test/java/dev/mascwa/pulse/core/telemetry/OracleReachabilityTest.kt 2>&1)
else
  OUT=$(timeout 540 bash /tmp/runorc.sh 2>&1)
fi

if grep -qE '^e:|error:|NOTHING COMPILED' <<<"$OUT"; then
  echo "  !! DID NOT COMPILE — this is not evidence a guard fired"
  grep -E '^e:|error:|NOTHING COMPILED' <<<"$OUT" | head -4
elif grep -q 'FAILURES!!!' <<<"$OUT"; then
  echo "  GUARD AWAKE — which test failed:"
  grep -E '^[0-9]+\) ' <<<"$OUT" | head -4
  grep -E 'AssertionError' <<<"$OUT" | head -2 | cut -c1-220
else
  echo "  !! GUARD ASLEEP — the suite still passes with this rule removed"
fi
