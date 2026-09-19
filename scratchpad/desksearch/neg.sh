#!/usr/bin/env bash
# Negative-test the rules DesktopScreenSearchTest guards.
#
# Baseline was asserted green before this ran. Each case: perturb ONE rule, assert the substitution
# actually landed, run the suite, and require the NAMED test to be the one that fails — "the build
# failed" is not evidence the right guard fired. The restore is a shell `trap … EXIT`, so a timeout
# or a kill cannot leave a perturbation in the tree, and it is byte-compared afterwards.
set -uo pipefail
cd "$(dirname "$0")/../.."
ROOT=$PWD

IDX=desktop/src/main/kotlin/dev/mascwa/pulse/desktop/search/DesktopSearchIndex.kt
CORE=core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/DeviceSearch.kt
BK=$(mktemp -d)
cp "$IDX" "$BK/idx.kt"
cp "$CORE" "$BK/core.kt"

restore() {
  cp "$BK/idx.kt" "$IDX"; cp "$BK/core.kt" "$CORE"
  cmp -s "$BK/idx.kt" "$IDX" && cmp -s "$BK/core.kt" "$CORE" \
    && echo "restored byte-identical" || echo "⚠️ RESTORE FAILED — CHECK THE TREE"
}
trap restore EXIT

run_case() {  # name, file, old, new, expected-failing-test
  local name=$1 file=$2 old=$3 new=$4 want=$5
  echo "=============================================================="
  echo "CASE: $name"
  # ⚠️ old/new as SEPARATE arguments. A single string split on "\x00" cannot work: a NUL byte does
  # not survive argv, so the split silently found one field and every case reported itself invalid.
  python3 - "$file" "$old" "$new" <<'PY'
import sys, pathlib
path, old, new = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3]
src = path.read_text()
assert src.count(old) == 1, f"perturbation did not match uniquely ({src.count(old)}x): {old!r}"
path.write_text(src.replace(old, new))
print("  perturbation applied")
PY
  [ $? -eq 0 ] || { echo "  ⚠️ PERTURBATION INVALID — case proves nothing"; return; }

  rm -rf desktop/build/test-results/test
  ./gradlew :desktop:test --configure-on-demand --no-configuration-cache -q >/dev/null 2>&1
  local failed
  failed=$(python3 - <<'PY'
import glob, re
names = []
for f in glob.glob("desktop/build/test-results/test/*.xml"):
    for m in re.finditer(r'<testcase name="([^"]*)"[^>]*>\s*<failure', open(f, encoding="utf-8").read()):
        names.append(m.group(1))
print("|".join(sorted(set(names))) or "(none)")
PY
)
  echo "  failing tests: $failed"
  if [[ "$failed" == *"$want"* ]]; then echo "  ✅ AWAKE — '$want' failed"; else echo "  ❌ ASLEEP — expected '$want'"; fi
  cp "$BK/idx.kt" "$IDX"; cp "$BK/core.kt" "$CORE"
}

# R1 — the search terms live in `headings`, where the scorer reads them and the row does not draw them.
run_case "terms are dropped from the record" "$IDX" \
  "headings = e.searchTerms," \
  "headings = emptyList()," \
  "a screen is found by a word someone would actually type for it"

# R2 — a result is resolved back to a screen by ITS OWN id, so a guide cannot be read as a screen.
run_case "screenOf stops checking the id" "$IDX" \
  "Screen.entries.firstOrNull { it.name == result.id }" \
  "Screen.entries.firstOrNull { true }" \
  "a record that is not a screen resolves to nothing"

# R3 — the reason the two corpora are searched apart is the core's per-kind cap. Perturb the cap and
# the measurement that justifies the separation must stop holding.
run_case "the per-kind cap stops binding" "$CORE" \
  "const val DEFAULT_PER_KIND = 4" \
  "const val DEFAULT_PER_KIND = 12" \
  "folding screens into the library corpus would cost the library its places"

echo "=============================================================="
