#!/usr/bin/env bash
# Negative-test Recon.kt's load-bearing rules: perturb the source, confirm the SPECIFIC test that
# names the rule fails, restore. Baseline is asserted green FIRST — a perturbation harness whose
# baseline is already red proves nothing. Each perturbation is asserted to have MATCHED the source
# before it is applied — a perturbation that changed nothing looks exactly like a guard that holds.
set -uo pipefail
cd "$(dirname "$0")/../.."

SRC=core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/Recon.kt
TEST=core/telemetry/src/test/java/dev/mascwa/pulse/core/telemetry/ReconTest.kt
BAK=$(mktemp)
cp "$SRC" "$BAK"
trap 'cp "$BAK" "$SRC"; rm -f "$BAK"' EXIT

run() { bash scratchpad/coretest/run.sh "$TEST" 2>&1 | grep -v JAVA_TOOL_OPTIONS; }

echo "== baseline =="
if run | grep -q "^OK"; then echo "  baseline GREEN"; else echo "  BASELINE RED — abort"; run | tail; exit 1; fi

# pattern  ->  replacement  ->  a test name that MUST then fail
check() {
  local pat="$1" rep="$2" mustfail="$3"
  cp "$BAK" "$SRC"
  grep -qF "$pat" "$SRC" || { echo "  ASLEEP: perturbation did not match source: $pat"; return; }
  # sed with a delimiter unlikely to appear
  python3 - "$SRC" "$pat" "$rep" <<'PY'
import sys
p=sys.argv[1]; pat=sys.argv[2]; rep=sys.argv[3]
s=open(p).read()
assert pat in s, "pattern vanished"
open(p,'w').write(s.replace(pat, rep, 1))
PY
  local out; out=$(run)
  if echo "$out" | grep -q "$mustfail"; then
    echo "  AWAKE: '$mustfail' fails when '${pat:0:32}...' is broken"
  else
    echo "  ⚠ ASLEEP: '$mustfail' still passed after breaking '${pat:0:40}'"
  fi
  cp "$BAK" "$SRC"
}

echo "== perturbations =="
check 'val broadcast = network or (mask.inv() and 0xFFFFFFFFL)' 'val broadcast = network or mask.inv()' 'aTwentyFourSweeps'
check 'if (hostCount > cap) 24 else prefixLen' 'prefixLen' 'aWideSubnetIsClamped'
check 'first and 0x02 != 0' 'first and 0x20 != 0' 'theUlBitDistinguishes'
check 'first and 0x01 != 0' 'first and 0x00 != 0' 'theUlBitDistinguishes'
check 'rssi >= -67 -> "nearby"' 'rssi >= -60 -> "nearby"' 'signalReadsAsABand'
check '"Open (encrypted)"' '"Open"' 'onlyAGenuinelyKeyless'

echo "== restore + confirm green =="
cp "$BAK" "$SRC"
if run | grep -q "^OK"; then echo "  restored GREEN"; else echo "  ⚠ restore FAILED"; fi
