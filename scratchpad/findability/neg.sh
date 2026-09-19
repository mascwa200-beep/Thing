#!/usr/bin/env bash
# Negative-test the findability gate. Baseline must be green FIRST, every perturbation must be
# asserted to have matched the source, and every file is restored by a trap so a timeout cannot
# leave the tree perturbed (a recorded failure mode in this repo).
set -uo pipefail
cd "$(dirname "$0")/../.."
SCREEN=app/src/main/java/dev/mascwa/pulse/feature/settings/SettingsScreen.kt
TABLE=app/src/main/java/dev/mascwa/pulse/feature/settings/SettingsSections.kt
INDEX=app/src/main/java/dev/mascwa/pulse/data/search/DeviceSearchIndex.kt
BK=$(mktemp -d)
cp "$SCREEN" "$BK/s"; cp "$TABLE" "$BK/t"; cp "$INDEX" "$BK/i"
restore() { cp "$BK/s" "$SCREEN"; cp "$BK/t" "$TABLE"; cp "$BK/i" "$INDEX"; }
trap 'restore; rm -rf "$BK"' EXIT

run() { bash /tmp/runcov.sh >/tmp/cov.log 2>&1; grep -qE '^OK \(' /tmp/cov.log; }

echo "== baseline =="
if run; then echo "   baseline GREEN"; else echo "   BASELINE RED — every verdict below would be worthless"; sed -n '/JUnit/,$p' /tmp/cov.log | head -20; exit 1; fi

case_() { # name  python-perturbation  expected-failing-test-substring
  local name="$1" py="$2" want="$3"
  restore
  python3 - "$py" <<'PY'
import sys,subprocess
exec(sys.argv[1])
PY
  if [ $? -ne 0 ]; then echo "   [$name] PERTURBATION DID NOT APPLY — verdict void"; return; fi
  if run; then
    echo "   [$name] ASLEEP — the suite still passes with the rule broken"
  else
    if grep -q "$want" /tmp/cov.log; then
      echo "   [$name] awake — failed on: $want"
    else
      echo "   [$name] failed, but NOT on the expected test (check /tmp/cov.log)"
      grep -E '^[0-9]+\) ' /tmp/cov.log | head -3
    fi
  fi
  restore
}

echo "== perturbations =="
case_ "literal back at a call site" '
p="app/src/main/java/dev/mascwa/pulse/feature/settings/SettingsScreen.kt"
s=open(p).read(); old="vis(SettingsSections.VIEWSCREEN)"
assert old in s, "perturbation did not match the source"
open(p,"w").write(s.replace(old, "vis(SettingsCategory.SECURITY, \"viewscreen sponsor skip\")", 1))
' "no section keeps its search vocabulary"

case_ "a table entry gates nothing" '
p="app/src/main/java/dev/mascwa/pulse/feature/settings/SettingsSections.kt"
s=open(p).read(); old="VIEWSCREEN, AMBIENT_SENSING"
assert old in s, "perturbation did not match the source"
open(p,"w").write(s.replace(old, "AMBIENT_SENSING", 1))
' "every call site names a real table entry"

case_ "duplicate-row filter removed" '
p="app/src/main/java/dev/mascwa/pulse/feature/settings/SettingsSections.kt"
s=open(p).read(); old="ALL.filter { it.title != it.category.title }"
assert old in s, "perturbation did not match the source"
open(p,"w").write(s.replace(old, "ALL", 1))
' "the table declares its own quirks"

case_ "sections emitted as FEATURE again" '
p="app/src/main/java/dev/mascwa/pulse/data/search/DeviceSearchIndex.kt"
s=open(p).read(); old="kind = RecordKind.SETTING"
assert old in s, "perturbation did not match the source"
open(p,"w").write(s.replace(old, "kind = RecordKind.FEATURE"))
' "the search index reads the table"

case_ "index stops reading the table" '
p="app/src/main/java/dev/mascwa/pulse/data/search/DeviceSearchIndex.kt"
s=open(p).read(); old="SettingsSections.searchRecords()"
assert old in s, "perturbation did not match the source"
open(p,"w").write(s.replace(old, "emptyList<Triple<String,String,String>>()", 1))
' "the search index reads the table"

echo "== multi-line tolerance (a POSITIVE property, not a perturbation) =="
restore
python3 - <<'PY'
p="app/src/main/java/dev/mascwa/pulse/feature/settings/SettingsScreen.kt"
s=open(p).read(); old="vis(SettingsSections.VIEWSCREEN)"
assert old in s, "did not match"
open(p,"w").write(s.replace(old, "vis(\n                    SettingsSections.VIEWSCREEN,\n                )", 1))
PY
if run; then echo "   awake — the paren-balanced reader still counts 30 across a multi-line site"
else echo "   BROKEN — the reader cannot handle a multi-line site"; grep -E '^[0-9]+\) |expected' /tmp/cov.log | head -5; fi
restore
echo "== done; tree restored =="
