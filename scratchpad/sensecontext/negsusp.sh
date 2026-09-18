#!/usr/bin/env bash
# Negative test for AppSuspension — safety rule 5's only enforcement point.
# One case per invocation; restore under a shell trap, byte-compared afterwards.
set -uo pipefail
cd "$(dirname "$0")/../.."

SRC=core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/AppSuspension.kt
TEST=core/telemetry/src/test/java/dev/mascwa/pulse/core/telemetry/AppSuspensionTest.kt
BAK=$(mktemp); cp "$SRC" "$BAK"
trap 'cp "$BAK" "$SRC"; if cmp -s "$BAK" "$SRC"; then echo "  restored byte-identical"; else echo "  !! RESTORE FAILED"; fi; rm -f "$BAK"' EXIT

run() { ./scratchpad/coretest/run.sh "$TEST" 2>&1 | grep -v JAVA_TOOL_OPTIONS; }

echo "== baseline =="
run | grep -q "^OK (" || { echo "BASELINE NOT GREEN — every case below is meaningless."; exit 2; }
echo "baseline green"

case "${1:?case}" in
  1) FROM='        return launchable.filter { it.isNotBlank() && it !in keep }.distinct()'
     TO='        return launchable.filter { it.isNotBlank() }.distinct()'
     NAME='the keep-list is honoured at all' ;;
  2) FROM='        if (dialer.isNullOrBlank() || launcher.isNullOrBlank()) return emptyList()'
     TO='        if (dialer.isNullOrBlank() || launcher.isNullOrBlank()) return launchable.distinct()'
     NAME='an unknown way back refuses rather than suspending everything' ;;
  *) echo "no such case"; exit 2 ;;
esac

python3 - "$SRC" "$FROM" "$TO" <<'PY'
import sys, io
p, a, b = sys.argv[1], sys.argv[2], sys.argv[3]
s = io.open(p, encoding='utf-8').read()
n = s.count(a)
assert n == 1, f"perturbation did not match the source exactly once (found {n})"
io.open(p, 'w', encoding='utf-8').write(s.replace(a, b))
print("  perturbation applied")
PY
[ $? -eq 0 ] || exit 2

echo "[$1] $NAME"
out=$(run)
if echo "$out" | grep -q "^OK ("; then
  echo "  ASLEEP — the rule was broken and nothing failed"
else
  echo "  awake — failed: $(echo "$out" | sed -nE 's/^[0-9]+\) ([^(]+)\(.*/\1/p' | tr '\n' '|')"
fi
