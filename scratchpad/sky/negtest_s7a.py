#!/usr/bin/env python3
"""Negative-test PlanetDisc's load-bearing rules.

Uses scratchpad/potato/negtest.py's runner, but its own loop: one of these rules lives in a
DIFFERENT file (Eclipses' solar radius, which PlanetDisc claims in writing to have copied), and the
shared main() perturbs a single target. Same discipline throughout — baseline asserted green first,
every perturbation asserted to have matched the source, and every file restored in a `finally`.
"""
import sys
from pathlib import Path

sys.path.insert(0, "/home/user/Thing/scratchpad/potato")
import negtest

ROOT = Path("/home/user/Thing")
PD = "core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/PlanetDisc.kt"
ECL = "core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/Eclipses.kt"
MODULE = "core:telemetry"
FILTER = "*PlanetDiscTest*"

CASES = [
    (
        "an impossible ratio is refused rather than returned as NaN",
        PD,
        "        if (s >= 1.0) return 0.0",
        "        if (false) return 0.0",
        ["an impossible geometry answers zero"],
    ),
    (
        "the terminator keeps its sign",
        # abs() still draws a terminator and still gets half phase right. It gets the BULGE
        # backwards, so every gibbous phase is drawn as a crescent and every crescent as gibbous.
        PD,
        "    fun terminatorFactor(phaseAngleDeg: Double): Double = cos(phaseAngleDeg * DEG)",
        "    fun terminatorFactor(phaseAngleDeg: Double): Double = abs(cos(phaseAngleDeg * DEG))",
        ["the terminator is an ellipse whose bulge reverses"],
    ),
    (
        "the ring squash is an absolute value",
        # A negative minor axis flips the drawn ellipse inside out at every southern-face epoch,
        # which is half of Saturn's 29-year cycle.
        PD,
        "        val squash: Double get() = abs(sin(openingDeg * DEG))",
        "        val squash: Double get() = sin(openingDeg * DEG)",
        ["the rings ellipse has a real minor axis"],
    ),
    (
        "the ring opening is negated",
        PD,
        "        val opening = -asin(cosPoleToLos) / DEG",
        "        val opening = asin(cosPoleToLos) / DEG",
        ["ring opening matches DE421"],
    ),
    (
        "the position angle is east over north, not north over east",
        # Swapped it still returns an angle, still varies smoothly, and puts both Saturn's rings and
        # Jupiter's moons at ninety degrees to where they are.
        PD,
        "        return (atan2(east, north) / DEG + 360.0) % 360.0",
        "        return (atan2(north, east) / DEG + 360.0) % 360.0",
        ["the position angle is measured east of north"],
    ),
    (
        "the moons' frame term carries no secular rate",
        # THE defect this slice turned on: `- J` instead of `- B`. J runs at 0.9025179 degrees a
        # day, so it slows every moon by that much — and looks entirely plausible.
        PD,
        "        val u1 = (163.8067 + 203.4058643 * dl + psi - b / DEG) * DEG",
        "        val u1 = (163.8067 + 203.4058643 * dl + psi - bigJ / DEG) * DEG",
        ["the frame term carries no secular rate"],
    ),
    (
        "the moons are stated on the J2000 epoch their constants belong to",
        # The other half of the same defect: an 1899-epoch day count under J2000 constants. The
        # moons still swing back and forth; they are simply in the wrong places.
        PD,
        "        val d = Ephemeris.julianDateTT(epochMs) - 2_451_545.0",
        "        val d = Ephemeris.julianDateTT(epochMs) - 2_415_020.0",
        ["the moons are where Horizons puts them"],
    ),
    (
        "the across-track offset follows the cosine",
        # sin instead of cos puts every moon furthest off the line exactly when it should be ON it,
        # and still draws a tilted line of moons.
        PD,
        "        y = -radii * cos(u) * sin(dEarthDeg * DEG),",
        "        y = -radii * sin(u) * sin(dEarthDeg * DEG),",
        ["the across-track offset follows the cosine"],
    ),
    (
        "behind is the far half of the orbit",
        PD,
        "        behind = cos(u) < 0.0,",
        "        behind = cos(u) > 0.0,",
        ["Horizons says which moons are hidden"],
    ),
    (
        "the orbital radii swing with their perturbation",
        # Freezing one is a small error only a real ephemeris can see.
        PD,
        'moonlet("Callisto", u4 + 0.845 * DEG * sin(h), 26.3627 - 0.1939 * cos(h), dEarth),',
        'moonlet("Callisto", u4 + 0.845 * DEG * sin(h), 26.3627, dEarth),',
        ["the moons are where Horizons puts them"],
    ),
    (
        "limb darkening refuses a point outside the disc",
        PD,
        "        if (fractionOfRadius < 0.0 || fractionOfRadius > 1.0) return 0.0",
        "        if (false) return 0.0",
        ["limb darkening runs from one at the centre"],
    ),
    (
        "the shared solar radius cannot drift",
        # The guard for the claim PlanetDisc makes in writing about where its copies came from.
        ECL,
        "    internal const val SUN_RADIUS_KM = 696_000.0",
        "    internal const val SUN_RADIUS_KM = 695_700.0",
        ["the Sun and Moon radii match the eclipse and occultation cores"],
    ),
]


def main():
    originals = {p: (ROOT / p).read_text() for p in {c[1] for c in CASES}}

    base = negtest.run(MODULE, FILTER)
    if not base:
        sys.exit("FATAL: the filter matched no tests at all — nothing was verified.")
    failing = [k for k, ok in base.items() if not ok]
    if failing:
        sys.exit(f"FATAL: baseline is not green, so every result below is meaningless: {failing}")
    print(f"baseline: {len(base)} tests, all passing\n")

    ok = True
    try:
        for name, path, old, new, expect in CASES:
            src = ROOT / path
            text = originals[path]
            if old not in text:
                print(f"[{name}] SKIPPED — perturbation did not match the source. NOT TESTED.")
                ok = False
                continue
            src.write_text(text.replace(old, new, 1))
            got = negtest.run(MODULE, FILTER)
            src.write_text(text)
            if not got:
                print(f"[{name}] did not compile — inconclusive, not evidence.")
                ok = False
                continue
            broke = sorted(k for k, good in got.items() if not good)
            hit = [e for e in expect if any(e in b for b in broke)]
            if len(hit) == len(expect):
                print(f"[{name}] AWAKE — broke {len(broke)}: {broke[:3]}")
            else:
                print(f"[{name}] ASLEEP — expected {expect}; actually failing: {broke}")
                ok = False
    finally:
        for p, text in originals.items():
            (ROOT / p).write_text(text)
            assert (ROOT / p).read_text() == text, f"restore failed for {p}"
    print("\nALL GUARDS AWAKE" if ok else "\nSOME GUARDS ASLEEP")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
