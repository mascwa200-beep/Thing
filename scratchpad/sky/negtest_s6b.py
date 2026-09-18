#!/usr/bin/env python3
"""Negative-test S6b's load-bearing rules: the prepared inverse and the raster header.

Reuses scratchpad/potato/negtest.py, which asserts a green baseline first and asserts that each
perturbation actually matched the source before it believes anything.
"""
import sys
sys.path.insert(0, "/home/user/Thing/scratchpad/potato")
import negtest

PROJ = "core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/SkyProjection.kt"
MW = "core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/MilkyWay.kt"

PROJ_CASES = [
    (
        "the inverse undoes the roll with the OPPOSITE sign",
        # Forward applies +roll last; the inverse must apply -roll first. Using +roll here reads
        # perfectly and is wrong everywhere except the exact centre of the screen.
        "rx = sx * basis.cosRoll + sy * basis.sinRoll\n            ry = -sx * basis.sinRoll + sy * basis.cosRoll",
        "rx = sx * basis.cosRoll - sy * basis.sinRoll\n            ry = sx * basis.sinRoll + sy * basis.cosRoll",
        ["unprojectUnit inverts projectUnit", "the prepared inverse answers"],
    ),
    (
        "the inverse flips y back",
        # `projectUnit` flips y once because a canvas grows downward. Forgetting it here mirrors the
        # whole sky top-to-bottom, which still draws a plausible picture.
        "val bigY = -ry * scale",
        "val bigY = ry * scale",
        ["unprojectUnit inverts projectUnit", "the prepared inverse answers"],
    ),
]

MW_CASES = [
    (
        "a wrong magic is refused",
        "if (u32(bytes, OFF_MAGIC) != MAGIC) return null",
        "if (false) return null",
        ["a header read the wrong way round"],
    ),
    (
        "a raster built at another resolution is refused",
        "if (u16(bytes, OFF_COLUMNS) != COLUMNS) return null",
        "if (false) return null",
        ["a header read the wrong way round"],
    ),
    (
        "a peak that is not a positive finite number is refused",
        "if (!peak.isFinite() || peak <= 0.0) return null",
        "if (false) return null",
        ["a header read the wrong way round"],
    ),
    (
        "the header is read little-endian",
        # The one mistake actually made while writing the builder.
        "((b[at + 3].toInt() and 0xFF) shl 24)",
        "((b[at + 3].toInt() and 0xFF) shl 0)",
        ["a header read the wrong way round"],
    ),
    (
        "the longitude wrap's fast path agrees with the modulo it replaced",
        "return if (deg < 0.0) deg + 360.0 else deg - 360.0",
        "return deg",
        ["the vector transform agrees with the angular one"],
    ),
    (
        "the cell wrap's fast path agrees with the modulo it replaced",
        "column == -1 -> COLUMNS - 1",
        "column == -1 -> 0",
        ["longitude wraps, so the seam"],
    ),
    (
        "the third galactic axis carries the pole's declination",
        "-sin(dec) * cos(ra), -sin(dec) * sin(ra), cos(dec),",
        "-sin(dec) * cos(ra), -sin(dec) * sin(ra), -cos(dec),",
        ["the vector transform agrees with the angular one"],
    ),
]

if __name__ == "__main__":
    a = negtest.main("core:telemetry", "*SkyProjectionTest", PROJ, PROJ_CASES)
    b = negtest.main("core:telemetry", "*MilkyWayTest", MW, MW_CASES)
    sys.exit(a or b)
