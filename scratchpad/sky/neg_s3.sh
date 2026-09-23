#!/usr/bin/env bash
# Negative tests for S3 (VSOP87 planets): each case perturbs ONE rule, asserts the perturbation
# applied, runs the suite that should catch it, and restores the files under a trap — byte-compared.
#   ./neg_s3.sh            runs every case
#   ./neg_s3.sh C          runs one
# ⚠️ The baseline must be green before this means anything; it is asserted first.
set -uo pipefail
cd "$(dirname "$0")/../.."
PC=core/telemetry/src/main/java/dev/mascwa/pulse/data/orbital/PlanetCalc.kt
EPH=core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/Ephemeris.kt
VS=core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/Vsop87.kt
PCTEST=core/telemetry/src/test/java/dev/mascwa/pulse/data/orbital/PlanetCalcTest.kt
EPHTEST=core/telemetry/src/test/java/dev/mascwa/pulse/core/telemetry/EphemerisTest.kt
VSTEST=core/telemetry/src/test/java/dev/mascwa/pulse/core/telemetry/Vsop87Test.kt
BK=$(mktemp -d)
cp "$PC" "$BK/PlanetCalc.kt"; cp "$EPH" "$BK/Ephemeris.kt"; cp "$VS" "$BK/Vsop87.kt"
restore() { cp "$BK/PlanetCalc.kt" "$PC"; cp "$BK/Ephemeris.kt" "$EPH"; cp "$BK/Vsop87.kt" "$VS"; }
trap restore EXIT

run_pc()  { ./scratchpad/coretest/run.sh "$PCTEST" 2>&1 | grep -v JAVA_TOOL_OPTIONS; }
run_eph() { ./scratchpad/coretest/run.sh "$EPHTEST" 2>&1 | grep -v JAVA_TOOL_OPTIONS; }
run_vs()  { ./scratchpad/coretest/run.sh "$VSTEST" 2>&1 | grep -v JAVA_TOOL_OPTIONS; }

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
  cmp -s "$BK/PlanetCalc.kt" "$PC" && cmp -s "$BK/Ephemeris.kt" "$EPH" && cmp -s "$BK/Vsop87.kt" "$VS" && echo "   restored byte-identical" || echo "   RESTORE FAILED"
}

sel="${1:-ALL}"
want() { [ "$sel" = ALL ] || [ "$sel" = "$1" ]; }

echo "== baseline =="
b1=$(run_pc); echo "$b1" | grep -q "OK (" || { echo "BASELINE NOT GREEN (PlanetCalcTest)"; echo "$b1" | tail -5; exit 1; }
b2=$(run_eph); echo "$b2" | grep -q "OK (" || { echo "BASELINE NOT GREEN (EphemerisTest)"; echo "$b2" | tail -5; exit 1; }
b3=$(run_vs); echo "$b3" | grep -q "OK (" || { echo "BASELINE NOT GREEN (Vsop87Test)"; echo "$b3" | tail -5; exit 1; }
echo "   green: $(echo "$b1" | grep 'OK (') / $(echo "$b2" | grep 'OK (') / $(echo "$b3" | grep 'OK (')"

# A: a planet without annual aberration — up to 20" — must fail the 4.5" bar.
want A && case_ A "$PC" \
  '        Ephemeris.aberrate(dir, jdTT)
        val eqJ2000' \
  '        val eqJ2000' \
  run_pc everyPlanetIsWithinFourAndAHalfArcseconds

# B: no light-time correction (geometric position) — Mercury moves ~15" in its light-time.
want B && case_ B "$PC" \
  'private const val LIGHT_TIME_ITERATIONS = 2' \
  'private const val LIGHT_TIME_ITERATIONS = 0' \
  run_pc everyPlanetIsWithinFourAndAHalfArcseconds

# C: the horizon coordinates skip the parallax — the planet-parallax guard must see it vanish.
want C && case_ C "$PC" \
  '        val seen = Ephemeris.topocentric(geocentric, lat, lon, epochMs)' \
  '        val seen = geocentric' \
  run_pc theHorizonCoordinatesCarryTheParallax

# D: the inverse nutation rotation leaves with the wrong obliquity — the true pair stops being exact.
want D && case_ D "$EPH" \
  '        val fromEps = if (forward) epsMean else epsTrue' \
  '        val fromEps = epsMean' \
  run_eph theTrueEquinoxRotationsAreAnExactInversePair

# E: precession without nutation on the way to true-of-date — the Sun is 17" out.
want E && case_ E "$EPH" \
  '        return nutateRotate(mean[0], mean[1], t, forward = true)' \
  '        return mean' \
  run_eph geocentricSunMatchesJplToBetterThanTwoArcseconds

# F: the series stops raising T to its power — every table reads as its T^0 sum.
want F && case_ F "$VS" \
  '            tk *= t' \
  '            tk *= 1.0' \
  run_vs 'the evaluator reads the truncated table exactly'

# G: the Sun without aberration — 20" — fails its own 2" bar.
want G && case_ G "$EPH" \
  '        dir[2] = -dir[2] / dist
        aberrate(dir, jdTT)' \
  '        dir[2] = -dir[2] / dist' \
  run_eph geocentricSunMatchesJplToBetterThanTwoArcseconds

# H: Saturn's ring-tilt term with the wrong sign — most of a magnitude at a wide ring opening.
want H && case_ H "$PC" \
  '                    -8.914 - 1.825 * s + 0.026 * ph' \
  '                    -8.914 + 1.825 * s + 0.026 * ph' \
  run_pc magnitudesMatchMallamaAndHilton

# I: the Sun's Earth vector fed UT instead of TT — 69 s of orbital motion is ~3" on the Sun.
# ⚠️ The first writing of this case perturbed earthHeliocentricJ2000Au instead, which the Sun does
# NOT go through (sunApparentJ2000 calls the series itself), and reported the guard ASLEEP — the
# recorded "fixture never reached the branch" mechanism, in the perturbation rather than the test.
want I && case_ I "$EPH" \
  '    fun sunEquatorial(epochMs: Long): Equatorial {
        val jdTT = julianDateTT(epochMs)' \
  '    fun sunEquatorial(epochMs: Long): Equatorial {
        val jdTT = julianDate(epochMs)' \
  run_eph geocentricSunMatchesJplToBetterThanTwoArcseconds
