#!/usr/bin/env python3
"""Negative-test every load-bearing rule in SkyPointing.

Same discipline as `negtest_refcircles.py`, and every clause of it is here because a guard came back
green for the wrong reason at least once in this repository: assert the baseline is GREEN first;
assert each perturbation MATCHED before writing; require it to REMOVE the property rather than merely
touch the line; read the failing test NAMES rather than trusting "the build failed"; restore in a
`finally` and check the restore byte for byte.
"""
import filecmp
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path("/home/user/Thing")
SRC = ROOT / "core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/SkyPointing.kt"
TEST = "core/telemetry/src/test/java/dev/mascwa/pulse/core/telemetry/SkyPointingTest.kt"

CASES = [
    (
        "the sensor's pitch is taken at face value",
        "Attitude(azimuthDeg = azimuthDeg, altitudeDeg = -pitchDeg, rollDeg = -rollDeg)",
        "Attitude(azimuthDeg = azimuthDeg, altitudeDeg = pitchDeg, rollDeg = -rollDeg)",
        ["the sensor's own signs are turned round exactly once"],
    ),
    (
        "the sensor's roll is taken at face value",
        "Attitude(azimuthDeg = azimuthDeg, altitudeDeg = -pitchDeg, rollDeg = -rollDeg)",
        "Attitude(azimuthDeg = azimuthDeg, altitudeDeg = -pitchDeg, rollDeg = rollDeg)",
        ["the sensor's own signs are turned round exactly once"],
    ),
    (
        "THE ROLL SIGN: equivalentView stops negating",
        "rollDeg = -a.rollDeg,",
        "rollDeg = a.rollDeg,",
        ["the vector path draws exactly what the angle path draws"],
    ),
    (
        "roll turns the screen-up the other way",
        "out[0] = ux * cr + rx * sr\n        out[1] = uy * cr + ry * sr\n        out[2] = uz * cr + rz * sr",
        "out[0] = ux * cr - rx * sr\n        out[1] = uy * cr - ry * sr\n        out[2] = uz * cr - rz * sr",
        ["tipping the top of the handset right turns the screen up toward the screen right",
         "the vector path draws exactly what the angle path draws"],
    ),
    (
        "the screen-up is no longer a quarter turn up",
        "val uz = cos(alt)",
        "val uz = sin(alt)",
        ["the two directions are always a unit pair at right angles",
         "with no roll the screen up is the look direction a quarter turn higher"],
    ),
    (
        "the angle path stops clamping at the pole",
        "altitudeDeg = a.altitudeDeg.coerceIn(\n            -SkyProjection.MAX_ALTITUDE_DEG,\n            SkyProjection.MAX_ALTITUDE_DEG,\n        ),",
        "altitudeDeg = a.altitudeDeg,",
        ["the angle path clamps at the pole and the vector path does not"],
    ),
    (
        "a degenerate look direction is normalised anyway",
        "if (n < DEGENERATE) return\n        fx /= n",
        "if (false) return\n        fx /= n",
        ["a half turn between frames keeps the newest reading rather than nothing"],
    ),
    (
        "a degenerate screen-up is normalised anyway",
        "if (n < DEGENERATE) return\n\n        outForward[0] = fx",
        "if (false) return\n\n        outForward[0] = fx",
        ["a half turn between frames keeps the newest reading rather than nothing"],
    ),
    (
        "the blend is not squared up again",
        "ux -= d * fx; uy -= d * fy; uz -= d * fz",
        "ux -= 0.0; uy -= 0.0; uz -= 0.0",
        ["a blended attitude is still a valid attitude"],
    ),
    (
        "the hand-set correction stops wrapping",
        "a.copy(azimuthDeg = wrap360(a.azimuthDeg + trimDeg))",
        "a.copy(azimuthDeg = a.azimuthDeg + trimDeg)",
        ["the hand set correction wraps rather than running off the end"],
    ),
    (
        "a bearing is invented at the pole",
        "if (abs(v[0]) < 1e-12 && abs(v[1]) < 1e-12) return 0.0\n",
        "",
        ["straight up has no bearing and says so rather than inventing one"],
    ),
]


def run():
    p = subprocess.run(
        ["./scratchpad/coretest/run.sh", TEST],
        cwd=ROOT, capture_output=True, text=True,
    )
    return p.stdout + p.stderr


def failing(out):
    return set(re.findall(r"^\d+\) ([^(]+)\(", out, re.M))


base = run()
if "OK (" not in base:
    sys.exit("BASELINE IS NOT GREEN — nothing below would mean anything:\n" + base[-3000:])
print("baseline:", re.search(r"OK \(\d+ tests\)", base).group(0))

original = SRC.read_text()
backup = Path(tempfile.mkdtemp()) / "SkyPointing.kt"
shutil.copy2(SRC, backup)
asleep = []
try:
    for label, find, repl, expect in CASES:
        n = original.count(find)
        if n != 1:
            asleep.append(f"{label} (perturbation matched {n} times)")
            print(f"  BROKEN  {label}: matched {n} times, not once — it would test nothing")
            continue
        SRC.write_text(original.replace(find, repl))
        out = run()
        if "OK (" in out:
            asleep.append(label)
            print(f"  ASLEEP  {label} — the suite still passes with the rule gone")
            continue
        if "NOTHING COMPILED" in out or "error:" in out:
            asleep.append(f"{label} (did not compile)")
            print(f"  BROKEN  {label} — the perturbation does not compile, so nothing was tested")
            continue
        fails = failing(out)
        missing = [t for t in expect if t not in fails]
        extra = sorted(fails - set(expect))
        print(f"  {'awake  ' if not missing else 'PARTIAL'} {label}: {len(fails)} failed")
        if missing:
            print(f"           expected but did not fail: {missing}")
            asleep.append(label)
        if extra:
            print(f"           also failed (fine, but worth seeing): {extra}")
finally:
    SRC.write_text(original)
    assert filecmp.cmp(SRC, backup, shallow=False), "RESTORE FAILED — the source is not as it was"
    after = run()
    assert "OK (" in after, "the restored source does not pass:\n" + after[-3000:]
    print("restored, and green again:", re.search(r"OK \(\d+ tests\)", after).group(0))

print()
print("ALL GUARDS AWAKE" if not asleep else f"ASLEEP/BROKEN: {asleep}")
sys.exit(1 if asleep else 0)
