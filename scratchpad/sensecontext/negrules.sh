#!/usr/bin/env bash
# Run the AmbientRules negative tests. See negsit.sh beside it for why the restore is a shell trap
# and why the baseline is asserted green before the first case.
set -uo pipefail
cd "$(dirname "$0")/../.."

SRC=core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/AmbientRules.kt
BAK=$(mktemp)
cp "$SRC" "$BAK"
trap 'cp "$BAK" "$SRC"; rm -f "$BAK"' EXIT

echo "== baseline =="
if ! ./scratchpad/coretest/run.sh core/telemetry/src/test/java/dev/mascwa/pulse/core/telemetry/AmbientRulesTest.kt 2>&1 | grep -q "^OK ("; then
  echo "BASELINE IS NOT GREEN — every case below would be meaningless. Stopping."
  exit 2
fi
echo "baseline green"
echo

rc=0
for i in "$@"; do
  python3 scratchpad/sensecontext/negrules.py "$i" || rc=1
  cmp -s "$SRC" "$BAK" || { echo "  !! the source was NOT restored after case $i"; rc=1; }
done
exit $rc
