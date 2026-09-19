#!/usr/bin/env bash
# Does the new gate actually catch the defect it was written for? Baseline green first; each
# perturbation asserts it matched; restore in a trap and byte-compare.
set -uo pipefail
cd /home/user/Thing
RW=app/src/main/java/dev/mascwa/pulse/notifications/RefreshWorker.kt
TEST=app/src/test/java/dev/mascwa/pulse/data/settings/ThrottleStampCoverageTest.kt
B1=$(mktemp); B2=$(mktemp); cp "$RW" "$B1"; cp "$TEST" "$B2"
trap 'cp "$B1" "$RW"; cp "$B2" "$TEST"; rm -f "$B1" "$B2"' EXIT

echo "== baseline =="
./scratchpad/throttle/run_gate.sh 2>/dev/null | tail -3

echo
echo "== A: put the tree back as it was — the three reads removed (the real defect) =="
python3 - <<'PY'
p='app/src/main/java/dev/mascwa/pulse/notifications/RefreshWorker.kt'
s=open(p).read()
subs=[('PassThrottle.due(settings.lastCuriosityMs, now, CURIOSITY_MIN_GAP_MS)','true'),
      ('PassThrottle.due(settings.lastLedgerAnchorMs, now, LEDGER_ANCHOR_MIN_GAP_MS)','true'),
      ('!PassThrottle.due(settings.lastAttestationCheckMs, now, ATTESTATION_MIN_GAP_MS)','false')]
for a,b in subs:
    assert s.count(a)==1, f"perturbation did not match uniquely: {a!r}"
    s=s.replace(a,b)
open(p,'w').write(s)
print("  (all three reads removed)")
PY
./scratchpad/throttle/run_gate.sh 2>/dev/null | tail -14
cp "$B1" "$RW"

echo
echo "== B: break the declaration search — the SELF-CHECK must fail, not pass vacuously =="
python3 - <<'PY'
p='app/src/test/java/dev/mascwa/pulse/data/settings/ThrottleStampCoverageTest.kt'
s=open(p).read()
a=r'"""\bval\s+(last[A-Za-z0-9]*Ms)\s*:\s*Long\b"""'
assert s.count(a)==1, "self-check perturbation did not match"
open(p,'w').write(s.replace(a, r'"""\bval\s+(zzzNoSuchThing)\s*:\s*Long\b"""'))
PY
./scratchpad/throttle/run_gate.sh 2>/dev/null | tail -10
cp "$B2" "$TEST"

cmp -s "$B1" "$RW" && cmp -s "$B2" "$TEST" && echo && echo "== both files restored byte-identical =="
