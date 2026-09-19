#!/usr/bin/env bash
# Negative-test PassThrottle.due. Baseline must be green FIRST; every perturbation asserts it
# matched the source before anything runs; the restore is in a trap so a kill cannot leave the
# tree perturbed (CLAUDE.md records that exact failure), and is byte-compared afterwards.
set -uo pipefail
cd /home/user/Thing
SRC=core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/PassThrottle.kt
BAK=$(mktemp); cp "$SRC" "$BAK"
trap 'cp "$BAK" "$SRC"; rm -f "$BAK"' EXIT

run() { timeout 600 ./gradlew :core:telemetry:test --tests "*PassThrottleTest*" \
          --configure-on-demand --no-configuration-cache -q >/tmp/nt.log 2>&1; }

echo "== baseline =="
if run; then echo "baseline GREEN"; else echo "BASELINE RED — stop, nothing below means anything"; exit 1; fi

perturb() { # name  from  to  expect(fail|pass)
  python3 - "$SRC" "$2" "$3" <<'PY'
import sys
p,a,b = sys.argv[1], sys.argv[2], sys.argv[3]
s = open(p).read()
assert s.count(a) == 1, f"perturbation did not match uniquely ({s.count(a)}x): {a!r}"
open(p,'w').write(s.replace(a,b))
PY
  [ $? -eq 0 ] || { echo "  !! perturbation FAILED TO APPLY — verdict would be meaningless"; return 1; }
  if run; then got=pass; else got=fail; fi
  if [ "$got" = "$4" ]; then echo "  $1 -> $got (as expected)"; else echo "  $1 -> $got  ** UNEXPECTED, expected $4 **"; fi
  cp "$BAK" "$SRC"
}

echo "== R1 never-run: KDoc CLAIMS this is belt-and-braces, so removing it should change NOTHING =="
perturb "drop rule 1" "        if (lastMs <= 0L) return true
" "" pass

echo "== R2 backwards clock: load-bearing, removing it must fail its own test =="
perturb "drop rule 2" "        if (nowMs < lastMs) return true
" "" fail

echo "== R3 the boundary: >= must not become > =="
perturb "floor becomes exclusive" "return nowMs - lastMs >= floorMs" "return nowMs - lastMs > floorMs" fail

cp "$BAK" "$SRC"
if cmp -s "$BAK" "$SRC"; then echo "== source restored byte-identical =="; else echo "== !! SOURCE NOT RESTORED =="; fi
