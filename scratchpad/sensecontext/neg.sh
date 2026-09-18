#!/usr/bin/env bash
# Run the SenseContext negative tests, one case per python invocation.
#
# ⚠️ The restore is a shell `trap ... EXIT`, not a python `finally`. A harness whose total runtime
# can exceed the tool timeout gets SIGTERMed, `finally` does not run, and a perturbation is left in
# the tree — which has happened here once already. The trap fires whichever way this shell ends.
#
# ⚠️ The baseline is asserted GREEN before the first case. A suite that was already failing makes
# every case report "awake" for a reason that has nothing to do with the perturbation.
set -uo pipefail
cd "$(dirname "$0")/../.."

SRC=core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/SenseContext.kt
BAK=$(mktemp)
cp "$SRC" "$BAK"
trap 'cp "$BAK" "$SRC"; rm -f "$BAK"' EXIT

echo "== baseline =="
if ! ./scratchpad/coretest/run.sh core/telemetry/src/test/java/dev/mascwa/pulse/core/telemetry/SenseContextTest.kt 2>&1 | grep -q "^OK ("; then
  echo "BASELINE IS NOT GREEN — every case below would be meaningless. Stopping."
  exit 2
fi
echo "baseline green"
echo

rc=0
for i in "$@"; do
  python3 scratchpad/sensecontext/negtest.py "$i" || rc=1
  cmp -s "$SRC" "$BAK" || { echo "  !! the source was NOT restored after case $i"; rc=1; }
done
exit $rc
