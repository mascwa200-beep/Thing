#!/usr/bin/env bash
# Negative-test one S7 load-bearing rule: assert the baseline suite is green, perturb the shipped
# source (python asserts the anchor matched EXACTLY once), run the suite, name which tests failed,
# and restore the file in this shell's EXIT trap, byte-compared — so a killed run cannot leave a
# perturbation in the tree.
#
#   ./scratchpad/sky/neg_s7.sh <case>        one case per invocation, deliberately
#
# ⚠️ Perturbs files IN PLACE: run it alone, never beside a compile check or a Gradle build.
set -uo pipefail
cd "$(dirname "$0")/../.."
CASE="${1:?case name}"
S=core/sky/src/main/java/dev/mascwa/pulse/sky
T=core/sky/src/test/java/dev/mascwa/pulse/sky
CT=core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry
CTT=core/telemetry/src/test/java/dev/mascwa/pulse/core/telemetry

case "$CASE" in
  # S7a — the aberration step itself.
  comet_no_aberration)
    FILE=$CT/Comets.kt; OLD='        Ephemeris.aberrate(dir, jd)
'; NEW=''
    RUN="./scratchpad/coretest/run.sh $CTT/CometsTest.kt"
    EXPECT="every fixture is aberrated away from its astrometric place|every branch of the solver reproduces JPL" ;;
  # S7b — the limit itself. ⚠️ The catalogue-magnitude cut ALONE cannot be perturbed into a failure:
  # it is an early-out the second cut subsumes (m >= m0, so m0 > limit implies m > limit), and a first
  # version of this case removed only that line and reported ASLEEP — the recorded mechanism #2, a
  # perturbation that touches the code without removing the property. Both cuts go, so nothing
  # holds the limit.
  hit_no_catalogue_cut)
    FILE=$S/StarHitTest.kt; OLD='            if (m0 > limit || m0 >= bestDrawn) continue'; NEW='            if (m0 >= bestDrawn) continue'
    RUN="/tmp/skytest.sh dev.mascwa.pulse.sky.StarHitTestTest $S/StarHitTest.kt $S/StarLayer.kt $S/SkyFrame.kt -- $T/StarHitTestTest.kt"
    EXPECT="a star past the catalogue cut is never named"
    SECOND_OLD='            if (m > limit || m >= bestDrawn) continue'; SECOND_NEW='            if (m >= bestDrawn) continue' ;;
  # S7b — the extincted cut in the hit test.
  hit_no_air_cut)
    FILE=$S/StarHitTest.kt; OLD='            if (m > limit || m >= bestDrawn) continue'; NEW='            if (m >= bestDrawn) continue'
    RUN="/tmp/skytest.sh dev.mascwa.pulse.sky.StarHitTestTest $S/StarHitTest.kt $S/StarLayer.kt $S/SkyFrame.kt -- $T/StarHitTestTest.kt"
    EXPECT="a star the air dims past the cut is not named" ;;
  # S7b — ranking by the drawn magnitude rather than the catalogue one.
  hit_rank_by_catalogue)
    FILE=$S/StarHitTest.kt; OLD='            bestDrawn = m
'; NEW='            bestDrawn = m0
'
    RUN="/tmp/skytest.sh dev.mascwa.pulse.sky.StarHitTestTest $S/StarHitTest.kt $S/StarLayer.kt $S/SkyFrame.kt -- $T/StarHitTestTest.kt"
    EXPECT="the brightest DRAWN star wins" ;;
  # S7c — the air added to the deep-sky magnitude before the cut.
  dso_no_extinction)
    FILE=$S/DeepSkyLayer.kt; OLD='            magnitude[i].toDouble() + extinctionMag, majorArcmin[i].toDouble(), limit, fovDeg,'; NEW='            magnitude[i].toDouble(), majorArcmin[i].toDouble(), limit, fovDeg,'
    RUN="/tmp/skytest.sh dev.mascwa.pulse.sky.DeepSkyLayerTest $S/DeepSkyLayer.kt -- $T/DeepSkyLayerTest.kt"
    EXPECT="the air is added to the catalogue magnitude before the cut" ;;
  *) echo "unknown case $CASE"; exit 2 ;;
esac

run_suite() { eval "$RUN" 2>&1 | grep -vE '^Picked up|^warning:|JAVA_TOOL'; }

echo "== $CASE  ($FILE)"
BASE=$(run_suite)
echo "$BASE" | grep -q '^OK (' || { echo "BASELINE NOT GREEN — refusing to test:"; echo "$BASE" | tail -5; exit 2; }
echo "baseline: $(echo "$BASE" | grep '^OK (')"

BAK=$(mktemp)
cp "$FILE" "$BAK"
restore() { cp "$BAK" "$FILE"; cmp -s "$BAK" "$FILE" && echo "restored byte-identical" || echo "RESTORE FAILED — check $FILE"; }
trap restore EXIT

FILE="$FILE" OLD="$OLD" NEW="$NEW" SECOND_OLD="${SECOND_OLD:-}" SECOND_NEW="${SECOND_NEW:-}" python3 - <<'EOF'
import os
p = os.environ['FILE']; old = os.environ['OLD']; new = os.environ['NEW']
src = open(p, encoding='utf-8').read()
n = src.count(old)
assert n == 1, f"anchor matched {n} times, not exactly once"
src = src.replace(old, new)
old2 = os.environ['SECOND_OLD']
if old2:
    n2 = src.count(old2)
    assert n2 == 1, f"second anchor matched {n2} times, not exactly once"
    src = src.replace(old2, os.environ['SECOND_NEW'])
open(p, 'w', encoding='utf-8').write(src)
print("perturbation applied" + (" (both cuts)" if old2 else ""))
EOF
[ $? -eq 0 ] || { echo "PERTURBATION DID NOT APPLY"; exit 2; }

OUT=$(run_suite)
if echo "$OUT" | grep -q '^OK ('; then
  echo "ASLEEP — the suite stayed green: $(echo "$OUT" | grep '^OK (')"
  exit 1
fi
echo "awake — failures:"
echo "$OUT" | grep -E '^[0-9]+\) ' | sed 's/^/   /'
if echo "$OUT" | grep -E '^[0-9]+\) ' | grep -qE "$EXPECT"; then
  echo "   (names the expected test)"
else
  echo "   ⚠️ did NOT fail the expected test ($EXPECT) — read the failures above"
fi
