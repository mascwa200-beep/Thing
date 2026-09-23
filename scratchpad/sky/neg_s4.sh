#!/usr/bin/env bash
# Negative tests for S4 (apparent star places): each case perturbs ONE rule, asserts the perturbation
# applied exactly once, runs the suite that should catch it, and restores the files under a trap —
# byte-compared. The shape of neg_s3.sh.
#   ./neg_s4.sh            runs every case
#   ./neg_s4.sh C          runs one
# ⚠️ The baseline must be green before this means anything; it is asserted first, per runner.
set -uo pipefail
cd "$(dirname "$0")/../.."
EPH=core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/Ephemeris.kt
NUT=core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/Nutation.kt
TERM=core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/Terminator.kt
SF=core/sky/src/main/java/dev/mascwa/pulse/sky/SkyFrame.kt
APTEST=core/telemetry/src/test/java/dev/mascwa/pulse/core/telemetry/ApparentPlaceTest.kt
NUTTEST=core/telemetry/src/test/java/dev/mascwa/pulse/core/telemetry/NutationTest.kt
PCTEST=core/telemetry/src/test/java/dev/mascwa/pulse/data/orbital/PlanetCalcTest.kt
OCCTEST=core/telemetry/src/test/java/dev/mascwa/pulse/core/telemetry/OccultationsTest.kt
TERMTEST=core/telemetry/src/test/java/dev/mascwa/pulse/core/telemetry/TerminatorTest.kt
EPHTEST=core/telemetry/src/test/java/dev/mascwa/pulse/core/telemetry/EphemerisTest.kt
SFTEST=core/sky/src/test/java/dev/mascwa/pulse/sky/SkyFrameRefractionTest.kt
BK=$(mktemp -d)
cp "$EPH" "$BK/Ephemeris.kt"; cp "$NUT" "$BK/Nutation.kt"; cp "$TERM" "$BK/Terminator.kt"; cp "$SF" "$BK/SkyFrame.kt"
restore() { cp "$BK/Ephemeris.kt" "$EPH"; cp "$BK/Nutation.kt" "$NUT"; cp "$BK/Terminator.kt" "$TERM"; cp "$BK/SkyFrame.kt" "$SF"; }
trap restore EXIT

run_ap()   { ./scratchpad/coretest/run.sh "$APTEST"   2>&1 | grep -v JAVA_TOOL_OPTIONS; }
run_nut()  { ./scratchpad/coretest/run.sh "$NUTTEST"  2>&1 | grep -v JAVA_TOOL_OPTIONS; }
run_pc()   { ./scratchpad/coretest/run.sh "$PCTEST"   2>&1 | grep -v JAVA_TOOL_OPTIONS; }
run_occ()  { ./scratchpad/coretest/run.sh "$OCCTEST"  2>&1 | grep -v JAVA_TOOL_OPTIONS; }
run_term() { ./scratchpad/coretest/run.sh "$TERMTEST" 2>&1 | grep -v JAVA_TOOL_OPTIONS; }
run_eph()  { ./scratchpad/coretest/run.sh "$EPHTEST"  2>&1 | grep -v JAVA_TOOL_OPTIONS; }
run_sky()  { /tmp/skytest.sh dev.mascwa.pulse.sky.SkyFrameRefractionTest "$SF" -- "$SFTEST" 2>&1 | grep -v JAVA_TOOL_OPTIONS; }

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
  cmp -s "$BK/Ephemeris.kt" "$EPH" && cmp -s "$BK/Nutation.kt" "$NUT" && cmp -s "$BK/Terminator.kt" "$TERM" && cmp -s "$BK/SkyFrame.kt" "$SF" && echo "   restored byte-identical" || echo "   RESTORE FAILED"
}

sel="${1:-ALL}"
want() { [ "$sel" = ALL ] || [ "$sel" = "$1" ]; }

echo "== baseline =="
for r in run_ap run_nut run_pc run_occ run_term run_eph run_sky; do
  b=$($r); echo "$b" | grep -q "OK (" || { echo "BASELINE NOT GREEN ($r)"; echo "$b" | tail -5; exit 1; }
  echo "   green: $r $(echo "$b" | grep 'OK (')"
done

# A1: the out-of-phase nutation term on the wrong function (cos where the table says sin).
want A1 && case_ A1 "$NUT" \
  '            dPsi += (lon[l] + lon[l + 1] * t) * s + lon[l + 2] * c' \
  '            dPsi += (lon[l] + lon[l + 1] * t) * s + lon[l + 2] * s' \
  run_nut "the evaluator reproduces skyfield"

# A2: a fundamental argument loses its rate — the Moon's node stops moving.
want A2 && case_ A2 "$NUT" \
  '        val a4 = Math.toRadians((f[4][1] * t + f[4][0]) % ARCSEC_PER_TURN / 3600.0)' \
  '        val a4 = Math.toRadians((f[4][0]) % ARCSEC_PER_TURN / 3600.0)' \
  run_nut "the evaluator reproduces skyfield"

# A3: the planetary offset dropped — 0.135 mas against a microarcsecond bar.
want A3 && case_ A3 "$NUT" \
  '        out[0] = (dPsi + NutationTables.PLANETARY_LONGITUDE) * TENTH_MICROARCSEC' \
  '        out[0] = (dPsi) * TENTH_MICROARCSEC' \
  run_nut "the evaluator reproduces skyfield"

# B: apparent sidereal time collapses to mean — the equation of the equinoxes fixture, and the
#    planets' 3" alt/az bar (the equation is 5-7" at the fixtures).
want B && case_ B "$EPH" \
  '        return norm360(gmstDeg(jd) + nut[0] * cos(epsMean) / 3600.0)' \
  '        return norm360(gmstDeg(jd))' \
  run_ap "apparent sidereal time carries"
want B2 && case_ B2 "$EPH" \
  '        return norm360(gmstDeg(jd) + nut[0] * cos(epsMean) / 3600.0)' \
  '        return norm360(gmstDeg(jd))' \
  run_pc altitudeAndAzimuthAreTopocentricAndWithinThreeArcseconds

# C: β dropped from the ONE aberration implementation — 20" on every star.
want C && case_ C "$EPH" \
  '        val x = v[0] + beta[0]
        val y = v[1] + beta[1]
        val z = v[2] + beta[2]' \
  '        val x = v[0]
        val y = v[1]
        val z = v[2]' \
  run_ap "apparent right ascension and declination"

# D: the apparent place carried to the MEAN equinox instead of the true one — nutation, ~17".
want D && case_ D "$EPH" \
  '        return j2000ToTrueOfDate(ra, dec, epochMs)' \
  '        return j2000ToMeanOfDate(ra, dec, epochMs)' \
  run_ap "apparent right ascension and declination"

# E: the occultation star loses its aberration — contact times go back to ~40 s out.
want E && case_ E "$EPH" \
  '        val ofDate = apparentStarOfDate(raJ2000Deg, decJ2000Deg, epochMs)
        return Equatorial(ofDate[0], ofDate[1], distanceKm = 0.0)' \
  '        val ofDate = j2000ToTrueOfDate(raJ2000Deg, decJ2000Deg, epochMs)
        return Equatorial(ofDate[0], ofDate[1], distanceKm = 0.0)' \
  run_occ disappearanceAndReappearance

# F: the chart projects the geometric direction — the frame stops aberrating.
want F && case_ F "$SF" \
  '        Ephemeris.aberrateEquatorial(a, beta)
        if (!refracting)' \
  '        if (!refracting)' \
  run_sky "with the atmosphere off"

# G: catalogueOf back on the MEAN pair — the true-of-date inverse stops matching horizonOf.
want G && case_ G "$SF" \
  '            val j = Ephemeris.trueOfDateToJ2000(' \
  '            val j = Ephemeris.meanOfDateToJ2000(' \
  run_sky "a star truly on the horizon"

# H: the subsolar point back on MEAN sidereal time beside an apparent right ascension.
want H && case_ H "$TERM" \
  '        val gast = Ephemeris.gastDeg(Ephemeris.julianDate(epochMs))' \
  '        val gast = Ephemeris.gmstDeg(Ephemeris.julianDate(epochMs))' \
  run_term theTerminatorAgreesWithTheIndependentlyValidatedEphemeris

# I: the true-of-date pair loses its nutation on the way BACK — the exact inverse breaks, and with it
#    the tap's round trip.
want I && case_ I "$EPH" \
  '        val mean = nutateRotate(raDeg, decDeg, t, forward = false)
        return precessRotate(mean[0], mean[1], t, toDate = false)' \
  '        return precessRotate(raDeg, decDeg, t, toDate = false)' \
  run_eph theTrueEquinoxRotationsAreAnExactInversePair

# S: the Sun back to geocentric — 8.8" of parallax against a 3.6" bar.
want S && case_ S "$EPH" \
  '        toHorizontal(topocentric(sunEquatorial(epochMs), latDeg, lonDeg, epochMs), latDeg, lonDeg, epochMs)' \
  '        toHorizontal(sunEquatorial(epochMs), latDeg, lonDeg, epochMs)' \
  run_eph sunPositionMatchesJplAcrossTheGlobeAndTheYear
