#!/usr/bin/env bash
# Negative-test one rule of SettingsFindabilityTest.
#
#   ./neg.sh <case>
#
# Each case perturbs the shipped source, asserts the perturbation MATCHED (a substitution that
# silently applied to nothing reports a guard "awake" when nothing was tested), runs the gate, and
# restores under a `trap ... EXIT` so a kill or a timeout cannot leave the tree perturbed.
#
# ⚠️ One case per invocation, deliberately. A harness whose total runtime can exceed the tool's
# timeout is SIGTERMed, and `finally` inside python does not run — this project has left a
# perturbation in the tree exactly that way.
set -uo pipefail
ROOT=/home/user/Thing
DIR=$ROOT/desktop/src/main/kotlin/dev/mascwa/pulse/desktop/Directory.kt
SCR=$ROOT/desktop/src/main/kotlin/dev/mascwa/pulse/desktop/feature/settings/SettingsScreen.kt
TST=$ROOT/desktop/src/test/kotlin/dev/mascwa/pulse/desktop/feature/settings/SettingsFindabilityTest.kt
CASE=${1:?usage: neg.sh <case>}
BAK=$(mktemp -d)
cp "$DIR" "$BAK/Directory.kt"; cp "$SCR" "$BAK/SettingsScreen.kt"; cp "$TST" "$BAK/Test.kt"
restore() {
  cp "$BAK/Directory.kt" "$DIR"; cp "$BAK/SettingsScreen.kt" "$SCR"; cp "$BAK/Test.kt" "$TST"
  cmp -s "$BAK/Directory.kt" "$DIR" && cmp -s "$BAK/SettingsScreen.kt" "$SCR" \
    && cmp -s "$BAK/Test.kt" "$TST" || { echo "!! RESTORE FAILED — tree is perturbed"; exit 9; }
  rm -rf "$BAK"
}
trap restore EXIT

python3 - "$CASE" "$DIR" "$SCR" "$TST" <<'PY'
import sys, pathlib, re
case, dirp, scrp, tstp = sys.argv[1], *[pathlib.Path(p) for p in sys.argv[2:]]

def sub(path, old, new, n=1):
    t = path.read_text()
    assert t.count(old) >= n, f"PERTURBATION DID NOT MATCH ({path.name}): {old[:70]!r}"
    path.write_text(t.replace(old, new, n))

if case == "vocabulary":
    # The tree as it stood before this change: five terms, none of them naming a control.
    t = dirp.read_text()
    m = re.search(r'DeskEntry\(Screen\.SETTINGS, "Settings", "Every switch and preference",\n'
                  r'\s*listOf\(.*?\n\s*\)\),', t, re.S)
    assert m, "PERTURBATION DID NOT MATCH: the Settings DeskEntry"
    dirp.write_text(t.replace(m.group(0),
        'DeskEntry(Screen.SETTINGS, "Settings", "Every switch and preference",\n'
        '            listOf("preferences", "options", "config", "units", "location")),'))
elif case == "comments":
    # Stop blanking comments: the APPEARANCE paragraph's quoted switches become "controls".
    sub(tstp, "two == \"//\" -> {", "false -> {")
elif case == "template":
    # Let a template label through: `$m MIN` becomes a word the gate demands be findable.
    sub(tstp, "if ('$' in label) continue", "if (false) continue")
elif case == "stale":
    # A pinned label that is actually reachable must be reported, or the list rots.
    sub(tstp, '"Keep it in front" to', '"Fahrenheit" to "not a real reason", "Keep it in front" to')
elif case == "declaration":
    sub(tstp, 'Regex("""\\bfun\\s+(<[^>]*>\\s*)?$""")', 'Regex("""\\bNOSUCHKEYWORD$""")')
else:
    raise SystemExit(f"unknown case {case}")
print(f"perturbed: {case}")
PY
[ $? -eq 0 ] || exit 1

cd "$ROOT"
OUT=$(timeout 540 ./gradlew :desktop:test --tests '*SettingsFindabilityTest*' \
        --configure-on-demand --no-configuration-cache --rerun-tasks -q 2>&1)
RC=$?
XML=$ROOT/desktop/build/test-results/test/TEST-dev.mascwa.pulse.desktop.feature.settings.SettingsFindabilityTest.xml
if [ $RC -ne 0 ] && ! grep -qE 'error:|Compilation error' <<<"$OUT"; then
  python3 - "$XML" <<'PY'
import sys, xml.etree.ElementTree as ET, pathlib
p = pathlib.Path(sys.argv[1])
if not p.exists():
    print("  !! NO XML — did not compile, or the filter matched nothing. NOT a guard firing."); raise SystemExit(2)
r = ET.parse(p).getroot()
failed = [tc.get('name') for tc in r.iter('testcase') if tc.find('failure') is not None]
print(f"  GUARD AWAKE — {len(failed)} failed: {', '.join(failed)}")
for tc in r.iter('testcase'):
    f = tc.find('failure')
    if f is not None:
        print(f"    {tc.get('name')}: {(f.get('message') or '')[:300]}")
PY
elif grep -qE 'error:|Compilation error' <<<"$OUT"; then
  echo "  !! DID NOT COMPILE — this is not evidence a guard fired"
  grep -E 'error:' <<<"$OUT" | head -5
else
  echo "  !! GUARD ASLEEP — the suite still passes with this rule removed"
fi
