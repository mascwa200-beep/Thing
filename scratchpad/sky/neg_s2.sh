#!/usr/bin/env bash
# Negative tests for S2 (refraction): each case perturbs ONE rule, asserts the perturbation applied,
# runs the suite that should catch it, and restores the file under a trap — byte-compared.
#   ./neg_s2.sh            runs every case
#   ./neg_s2.sh C          runs one
# ⚠️ The baseline must be green before this means anything; it is asserted first.
set -uo pipefail
cd "$(dirname "$0")/../.."
REF=core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/Refraction.kt
FRAME=core/sky/src/main/java/dev/mascwa/pulse/sky/SkyFrame.kt
REFTEST=core/telemetry/src/test/java/dev/mascwa/pulse/core/telemetry/RefractionTest.kt
FRAMETEST=core/sky/src/test/java/dev/mascwa/pulse/sky/SkyFrameRefractionTest.kt
BK=$(mktemp -d)
cp "$REF" "$BK/Refraction.kt"; cp "$FRAME" "$BK/SkyFrame.kt"
restore() { cp "$BK/Refraction.kt" "$REF"; cp "$BK/SkyFrame.kt" "$FRAME"; }
trap restore EXIT

run_ref()   { ./scratchpad/coretest/run.sh "$REFTEST" 2>&1 | grep -v JAVA_TOOL_OPTIONS; }
run_frame() { /tmp/skytest.sh dev.mascwa.pulse.sky.SkyFrameRefractionTest "$FRAME" -- "$FRAMETEST" 2>&1 | grep -v JAVA_TOOL_OPTIONS; }

perturb() { # file old new
  python3 - "$1" "$2" "$3" <<'EOF'
import sys
path, old, new = sys.argv[1], sys.argv[2], sys.argv[3]
src = open(path).read()
n = src.count(old)
assert n == 1, f"perturbation matched {n} times, not once: {old[:60]!r}"
open(path, "w").write(src.replace(old, new))
print("perturbation applied")
EOF
}

verdict() { # expected-test-substring output
  local expect="$1"; local out="$2"
  if echo "$out" | grep -q "OK ("; then echo "   ASLEEP — suite still green"; return; fi
  # ⚠️ kotlinc reports `file.kt:12:3: error:`; JUnit reports `AssertionError:` — a case-insensitive
  # "error:" matched the second and reported every awake guard as a compile failure.
  if echo "$out" | grep -qE '(^e: |: error:)'; then echo "   INVALID — did not compile:"; echo "$out" | grep -E '(^e: |: error:)' | head -3; return; fi
  local fails; fails=$(echo "$out" | grep -E '^[0-9]+\) ' | sed 's/^[0-9]*) //')
  if echo "$fails" | grep -q "$expect"; then echo "   AWAKE — failed: $(echo "$fails" | tr '\n' ' ')"; else echo "   WRONG TEST — failed: $(echo "$fails" | tr '\n' ' ')"; fi
}

case_() { # id file old new runner expected
  local id="$1" file="$2" old="$3" new="$4" runner="$5" expect="$6"
  echo "== $id =="
  restore
  perturb "$file" "$old" "$new" || { echo "   INVALID — perturbation did not apply"; return; }
  local out; out=$($runner)
  verdict "$expect" "$out"
  restore
  cmp -s "$BK/Refraction.kt" "$REF" && cmp -s "$BK/SkyFrame.kt" "$FRAME" && echo "   restored byte-identical" || echo "   RESTORE FAILED"
}

sel="${1:-ALL}"
want() { [ "$sel" = ALL ] || [ "$sel" = "$1" ]; }

echo "== baseline =="
b1=$(run_ref); echo "$b1" | grep -q "OK (" || { echo "BASELINE NOT GREEN (core)"; echo "$b1" | tail -5; exit 1; }
b2=$(run_frame); echo "$b2" | grep -q "OK (" || { echo "BASELINE NOT GREEN (sky)"; echo "$b2" | tail -5; exit 1; }
echo "   green: $(echo "$b1" | grep 'OK (') / $(echo "$b2" | grep 'OK (')"

want A && case_ A "$REF" \
  '        return if (arcmin > 0.0) arcmin / 60.0 else 0.0' \
  '        return arcmin / 60.0' \
  run_ref 'never negative'

want B && case_ B "$REF" \
  '        trueAltDeg < TAPER_TOP_DEG ->
            converged(TAPER_TOP_DEG) *
                (trueAltDeg - TAPER_BOTTOM_DEG) / (TAPER_TOP_DEG - TAPER_BOTTOM_DEG)' \
  '        trueAltDeg < TAPER_TOP_DEG -> converged(trueAltDeg)' \
  run_ref 'monotonic'

want C && case_ C "$REF" \
  '        if (apparentAltDeg <= TAPER_BOTTOM_DEG) return apparentAltDeg' \
  '        if (apparentAltDeg <= TAPER_BOTTOM_DEG) return apparentAltDeg
        return apparentAltDeg - bennettDeg(apparentAltDeg)' \
  run_ref 'round-trips'

want D && case_ D "$REF" \
  '        out[0] = vx * c + ux * s
        out[1] = vy * c + uy * s
        out[2] = vz * c + uz * s' \
  '        out[0] = vx * c - ux * s
        out[1] = vy * c - uy * s
        out[2] = vz * c - uz * s' \
  run_ref 'lift raises'

want E && case_ E "$REF" \
  '    const val TABLE_STEP_DEG = 0.05' \
  '    const val TABLE_STEP_DEG = 0.07' \
  run_ref 'table nodes'

want F && case_ F "$FRAME" \
  '        Refraction.lift(vx, vy, vz, zenithX, zenithY, zenithZ, s, bend, lifted)
        return SkyProjection.projectUnit(lifted[0], lifted[1], lifted[2], basis)' \
  '        return SkyProjection.projectUnit(vx, vy, vz, basis)' \
  run_frame 'apparent altitude'

want G && case_ G "$FRAME" \
  '    private val sinDrawnHorizon: Double = if (refracting) SIN_TRUE_AT_APPARENT_HORIZON else 0.0' \
  '    private val sinDrawnHorizon: Double = 0.0' \
  run_frame 'drawn line'

# H: the maximum bend is the taper top's; the bend at the horizon (0.48°) is smaller, so a run
#    truly at −1° would be lifted past a cone padded by it.
want H && case_ H "$REF" \
  '    val MAX_BEND_DEG: Double by lazy { converged(TAPER_TOP_DEG) }' \
  '    val MAX_BEND_DEG: Double by lazy { converged(0.0) }' \
  run_ref 'largest bend'

# I: a refracting frame that asks for no cull margin.
want I && case_ I "$FRAME" \
  '    val cullMarginDeg: Double = if (refracting) Refraction.MAX_BEND_DEG else 0.0' \
  '    val cullMarginDeg: Double = 0.0' \
  run_frame 'cull to look further'
