#!/usr/bin/env bash
# Negative-test one rule of AgentToolSurfaceTest.
#
# One case per invocation: a harness whose total runtime can exceed the tool timeout is SIGTERMed
# and a language-level `finally` does not run — the recorded sixth way a green test proves nothing.
# The restore is a shell `trap ... EXIT` for that reason, and it is byte-compared.
#
# ⚠️ The perturbation strings live in python heredocs rather than shell variables. My first version
# put them in `case` arms and died on its own quoting: an apostrophe inside a double-quoted WHY, and
# a regex full of parens inside a `case` body. A harness that cannot parse itself proves nothing.
#
# Usage: ./neg.sh <1|2|3|4|5>
set -uo pipefail
ROOT=/home/user/Thing
CASE="${1:?usage: neg.sh <1|2|3|4|5>}"

LINK="$ROOT/app/src/main/java/dev/mascwa/pulse/jarvis/agent/DeviceActionTools.kt"
TOOL="$ROOT/app/src/main/java/dev/mascwa/pulse/jarvis/agent/DeviceSearchTool.kt"
TEST="$ROOT/app/src/test/java/dev/mascwa/pulse/jarvis/agent/AgentToolSurfaceTest.kt"

case "$CASE" in
  1) TARGET="$LINK" ;;
  2|5) TARGET="$TOOL" ;;
  3|4) TARGET="$TEST" ;;
  *) echo "unknown case"; exit 1 ;;
esac
[ "$CASE" = 5 ] && TARGET="$TEST"

echo "--- baseline (must be green, or every later verdict is worthless)"
BASE=$("$ROOT/scratchpad/toolsurface/run.sh" 2>&1); echo "$BASE"
grep -q '^OK (4 tests)' <<<"$BASE" || { echo "BASELINE NOT GREEN — stopping"; exit 1; }

cp "$TARGET" /tmp/ts.bak
trap 'cp /tmp/ts.bak "$TARGET"; cmp -s /tmp/ts.bak "$TARGET" && echo "restored byte-identical" || echo "RESTORE FAILED"' EXIT

python3 - "$CASE" "$TARGET" <<'PY'
import sys
case, path = sys.argv[1], sys.argv[2]
EDITS = {
    # The ACTUAL shipped defect: OpenLinkTool takes the name OpenScreenTool already has.
    "1": ('    override val name = "open_url"',
          '    override val name = "open"',
          "the real collision is back: two registered tools both called open"),
    # The usage stops naming documents/screens/settings. ⚠️ This class's own KDoc names all three,
    # so it ALSO proves comments are stripped before the prose is searched — without that, the gate
    # would find the words in the documentation of the thing rather than the thing.
    "2": ('"tasks, profile, findings, ingested documents, and the app\'s own screens and settings), "',
          '"tasks, profile, findings), "',
          "usage stops naming three kinds (and the KDoc still does, so this tests comment-stripping too)"),
    # A kind the index emits has no word in the map at all — the branch that forces a decision.
    "3": ('        RecordKind.SETTING to "settings",\n', '',
          "a kind the index emits has no entry in namedInUsageAs"),
    # The map promises a corpus the index does not emit — the direction an allowlist usually loses.
    "4": ('        RecordKind.GUIDE to "guides",\n',
          '        RecordKind.GUIDE to "guides",\n        RecordKind.STUDY to "study cards",\n',
          "the map names STUDY, which the phone index does not emit"),
    # The tool-name extractor matches nothing. The self-check must catch it rather than reporting a
    # clean registry — the vacuous green every one of these gates is built against.
    "5": ('override\\s+val\\s+name\\s*=', 'override\\s+val\\s+nameZZZ\\s*=',
          "the tool-name extractor matches nothing"),
}
old, new, why = EDITS[case]
s = open(path).read()
n = s.count(old)
assert n == 1, f"PERTURBATION DID NOT MATCH THE SOURCE ({n} occurrences) — verdict worthless"
open(path, "w").write(s.replace(old, new))
print("perturbation applied:", why)
PY
[ $? -eq 0 ] || exit 1

echo "--- perturbed"
"$ROOT/scratchpad/toolsurface/run.sh" 2>&1 | tail -30
