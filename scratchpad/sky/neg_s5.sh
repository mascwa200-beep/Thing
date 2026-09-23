#!/usr/bin/env bash
# Negative tests for S5 (sky brightness + extinction): each case perturbs ONE rule, asserts the
# perturbation applied exactly once, runs the suite that should catch it, and restores the files under
# a trap — byte-compared. The shape of neg_s4.sh.
#   ./neg_s5.sh            runs every case
#   ./neg_s5.sh N3         runs one
# ⚠️ Perturbs files IN PLACE: nothing may edit them or compile the core while this runs.
set -uo pipefail
cd "$(dirname "$0")/../.."
SB=core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/SkyBrightness.kt
SF=core/sky/src/main/java/dev/mascwa/pulse/sky/SkyFrame.kt
SBTEST=core/telemetry/src/test/java/dev/mascwa/pulse/core/telemetry/SkyBrightnessTest.kt
SFTEST=core/sky/src/test/java/dev/mascwa/pulse/sky/SkyFrameExtinctionTest.kt
BK=$(mktemp -d)
cp "$SB" "$BK/SkyBrightness.kt"; cp "$SF" "$BK/SkyFrame.kt"
restore() { cp "$BK/SkyBrightness.kt" "$SB"; cp "$BK/SkyFrame.kt" "$SF"; }
trap restore EXIT

run_sb()  { ./scratchpad/coretest/run.sh "$SBTEST" 2>&1 | grep -v JAVA_TOOL_OPTIONS; }
run_sky() { /tmp/skytest.sh dev.mascwa.pulse.sky.SkyFrameExtinctionTest "$SF" -- "$SFTEST" 2>&1 | grep -v JAVA_TOOL_OPTIONS; }

perturb() { # file old new
  python3 - "$1" "$2" "$3" <<'EOF'
import sys
path, old, new = sys.argv[1], sys.argv[2], sys.argv[3]
src = open(path).read()
n = src.count(old)
assert n == 1, f"perturbation matched {n} times, not once: {old[:70]!r}"
open(path, "w").write(src.replace(old, new))
print("perturbation applied")
EOF
}

verdict() { # expected-test-substring output
  local expect="$1"; local out="$2"
  if echo "$out" | grep -q "OK ("; then echo "   ASLEEP — suite still green"; return; fi
  if echo "$out" | grep -qE '(^e: |: error:)'; then echo "   INVALID — did not compile:"; echo "$out" | grep -E '(^e: |: error:)' | head -3; return; fi
  local fails; fails=$(echo "$out" | grep -E '^[0-9]+\) ' | sed 's/^[0-9]*) //')
  [ -n "$fails" ] || { echo "   NO VERDICT — neither green nor a listed failure:"; echo "$out" | tail -4; return; }
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
  cmp -s "$BK/SkyBrightness.kt" "$SB" && cmp -s "$BK/SkyFrame.kt" "$SF" && echo "   restored byte-identical" || echo "   RESTORE FAILED"
}

sel="${1:-ALL}"
want() { [ "$sel" = ALL ] || [ "$sel" = "$1" ]; }

echo "== baseline =="
for r in run_sb run_sky; do
  b=$($r); echo "$b" | grep -q "OK (" || { echo "BASELINE NOT GREEN ($r)"; echo "$b" | tail -5; exit 1; }
  echo "   green: $r $(echo "$b" | grep 'OK (')"
done

# N1: the dimming starts a thousandth of a magnitude before the end of nautical twilight — the
#     night identity (limit bit for bit) breaks at -12.
want N1 && case_ N1 "$SB" \
  '        NAUTICAL_SUN_ALT_DEG, 0.0,
        CIVIL_SUN_ALT_DEG, 2.5,' \
  '        NAUTICAL_SUN_ALT_DEG, 0.001,
        CIVIL_SUN_ALT_DEG, 2.5,' \
  run_sb "every brightness term is the identity"

# N2: the zenith clamp dropped — Rozenberg is a shade under 1 there, and the air brightens a star.
want N2 && case_ N2 "$SB" \
  '        return (EXTINCTION_MAG_PER_AIRMASS * (x - 1.0)).coerceAtLeast(0.0)' \
  '        return EXTINCTION_MAG_PER_AIRMASS * (x - 1.0)' \
  run_sb "extinction is zero at the zenith"

# N3: extinction applied below the drawn horizon — the map's dimmed-by-alpha region gets 5.07 mag.
want N3 && case_ N3 "$SB" \
  '        if (trueAltDeg.isNaN() || trueAltDeg < Refraction.TRUE_AT_APPARENT_HORIZON_DEG) return 0.0' \
  '        if (trueAltDeg.isNaN() || trueAltDeg < -90.0) return 0.0' \
  run_sb "extinction is zero at the zenith"

# N4: the airmass form allowed to run below the horizon instead of being clamped at 40.
want N4 && case_ N4 "$SB" \
  '        val cosZ = sin(Math.toRadians(apparentAltDeg.coerceIn(0.0, 90.0)))' \
  '        val cosZ = sin(Math.toRadians(apparentAltDeg.coerceIn(-90.0, 90.0)))' \
  run_sb "Rozenberg airmass"

# N5: airmass at the TRUE altitude rather than the apparent one — 5.07 on the true horizon, not 4.04.
want N5 && case_ N5 "$SB" \
  '        val x = airmass(Refraction.apparentOf(trueAltDeg))' \
  '        val x = airmass(trueAltDeg)' \
  run_sb "extinction is zero at the zenith"

# N6: the table scale off by one entry — every lookup lands a hundredth of a degree from its sample.
want N6 && case_ N6 "$SB" \
  '    private val TABLE_SCALE: Double = (TABLE_ENTRIES - 1) / (1.0 - SIN_DRAWN_HORIZON)' \
  '    private val TABLE_SCALE: Double = TABLE_ENTRIES / (1.0 - SIN_DRAWN_HORIZON)' \
  run_sb "per-star tables agree"

# N7: a knot out of order — the sky gets DARKER through civil twilight.
want N7 && case_ N7 "$SB" \
  '        CIVIL_SUN_ALT_DEG, 0.20,
        HORIZON_SUN_ALT_DEG, 0.60,' \
  '        CIVIL_SUN_ALT_DEG, 0.02,
        HORIZON_SUN_ALT_DEG, 0.60,' \
  run_sb "monotonically"

# N8: the frame extincts with the atmosphere OFF — the switch stops meaning anything.
want N8 && case_ N8 "$SF" \
  '        if (!refracting || sinAlt < sinDrawnHorizon) 0.0 else SkyBrightness.fastExtinctionMag(sinAlt)' \
  '        if (sinAlt < sinDrawnHorizon) 0.0 else SkyBrightness.fastExtinctionMag(sinAlt)' \
  run_sky "with the atmosphere off the air takes nothing"

# N9: the frame's extinction step moves off the batch boundary to the geometric horizon.
want N9 && case_ N9 "$SF" \
  '        if (!refracting || sinAlt < sinDrawnHorizon) 0.0 else SkyBrightness.fastExtinctionMag(sinAlt)' \
  '        if (!refracting || sinAlt < 0.0) 0.0 else SkyBrightness.fastExtinctionMag(sinAlt)' \
  run_sky "the extinction step sits exactly on the batch boundary"

# N10: the Milky Way never quite fades out — a tenth of it left at the end of nautical twilight.
want N10 && case_ N10 "$SB" \
  '        NAUTICAL_SUN_ALT_DEG, 0.0,
    )

    /** Where the glow peaks' \
  '        NAUTICAL_SUN_ALT_DEG, 0.1,
    )

    /** Where the glow peaks' \
  run_sb "the sky brightens, the Milky Way fades"
