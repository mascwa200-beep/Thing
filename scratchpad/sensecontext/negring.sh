#!/usr/bin/env bash
# Negative test for RingerPolicy. One case per invocation: a harness killed by a timeout does not
# run a python `finally`, so the restore is a shell trap and the file is byte-compared after it.
set -uo pipefail
cd "$(dirname "$0")/../.."

SRC=core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/RingerPolicy.kt
TEST=core/telemetry/src/test/java/dev/mascwa/pulse/core/telemetry/RingerPolicyTest.kt
BAK=$(mktemp)
cp "$SRC" "$BAK"
trap 'cp "$BAK" "$SRC"; if cmp -s "$BAK" "$SRC"; then echo "  restored byte-identical"; else echo "  !! RESTORE FAILED"; fi; rm -f "$BAK"' EXIT

run() { ./scratchpad/coretest/run.sh "$TEST" 2>&1 | grep -v JAVA_TOOL_OPTIONS; }

echo "== baseline =="
if ! run | grep -q "^OK ("; then echo "BASELINE NOT GREEN — every case below is meaningless."; exit 2; fi
echo "baseline green"

case "${1:?case number}" in
  1) FROM='asserting && current == Mode.NORMAL -> Mode.VIBRATE'
     TO='asserting -> Mode.VIBRATE'
     NAME='the assert gate: only from NORMAL' ;;
  2) FROM='!asserting && current == Mode.VIBRATE -> Mode.NORMAL'
     TO='!asserting -> Mode.NORMAL'
     NAME='the release gate: only from the VIBRATE we set' ;;
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
  echo "  ASLEEP — the rule was removed and nothing failed"
else
  # JUnit prints a Kotlin backtick name WITHOUT its backticks, as `name(fully.Qualified.Class)`.
  # An extraction that expects the backticks silently prints nothing, which reads as "awake but I
  # cannot tell you what fired" — and knowing WHICH test fired is the whole point of the check.
  echo "  awake — failed: $(echo "$out" | sed -nE 's/^[0-9]+\) ([^(]+)\(.*/\1/p' | tr '\n' '|')"
fi
