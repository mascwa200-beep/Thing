#!/usr/bin/env bash
set -uo pipefail
cd "$(dirname "$0")/../.."
SRC=core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/Recon.kt
TEST=core/telemetry/src/test/java/dev/mascwa/pulse/core/telemetry/ReconTest.kt
BAK=$(mktemp); cp "$SRC" "$BAK"; trap 'cp "$BAK" "$SRC"; rm -f "$BAK"' EXIT
run(){ bash scratchpad/coretest/run.sh "$TEST" 2>&1 | grep -v JAVA_TOOL_OPTIONS; }
echo "== baseline =="; run | grep -q "^OK" && echo "  GREEN" || { echo "  RED"; run|tail; exit 1; }
pat='val broadcast = network or (mask.inv() and 0xFFFFFFFFL)'
grep -qF "$pat" "$SRC" || { echo "  did not match"; exit 1; }
python3 - "$SRC" <<'PY'
import sys;p=sys.argv[1];s=open(p).read()
s=s.replace('val broadcast = network or (mask.inv() and 0xFFFFFFFFL)','val broadcast = network or mask.inv()',1)
open(p,'w').write(s)
PY
out=$(run)
echo "$out" | grep -q "aTwentyFourSweeps" && echo "  R1 AWAKE" || { echo "  R1 STILL ASLEEP"; echo "$out"|tail; }
cp "$BAK" "$SRC"
run | grep -q "^OK" && echo "== restored GREEN ==" || echo "== restore FAILED =="
