#!/usr/bin/env python3
"""Negative-test every load-bearing rule in the pole fix.

Discipline, and each clause is here because skipping it has produced a false verdict before:
  * the baseline is asserted GREEN before anything is perturbed — a suite already failing makes
    every perturbation look "awake" for a reason that has nothing to do with it;
  * every substitution asserts it actually MATCHED the source, so a perturbation that silently did
    nothing cannot be read as a guard that holds;
  * the file is restored in a `finally` and BYTE-COMPARED, because a harness that can be killed
    leaves the perturbation in the tree;
  * the report names WHICH tests failed, because "the build failed" is not evidence the right guard
    fired.
"""
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/SkyPointing.kt"
PROJ = ROOT / "core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/SkyProjection.kt"
TEST = ROOT / "core/telemetry/src/test/java/dev/mascwa/pulse/core/telemetry/SkyPointingTest.kt"
RUNNER = ROOT / "scratchpad/coretest/run.sh"

# (name, [(needle, replacement), ...], [tests that must fail])
# ⚠️ Every case perturbs SkyPointing.kt unless it names another file with a leading `file=` entry —
# the frame-gap rules the slice-5 tests guard live in SkyProjection.kt, and a harness that could only
# reach one file would have reported those three guards "awake" without touching the rule at all.
CASES = [
    (
        "the roll negation comes back",
        [("altitudeDeg = -pitchDeg, rollDeg = rollDeg)",
          "altitudeDeg = -pitchDeg, rollDeg = -rollDeg)")],
        ["the sensor's roll is read back the way round the handset is really held",
         "the pitch is turned round and the roll is not"],
    ),
    (
        "smooth reads prev straight out of the arrays again",
        [("fx = pfx * (1 - w) + fx * w", "fx = prevForward[0] * (1 - w) + fx * w"),
         ("fy = pfy * (1 - w) + fy * w", "fy = prevForward[1] * (1 - w) + fy * w"),
         ("fz = pfz * (1 - w) + fz * w", "fz = prevForward[2] * (1 - w) + fz * w"),
         ("ux = pux * (1 - wu) + ux * wu", "ux = prevUp[0] * (1 - wu) + ux * wu"),
         ("uy = puy * (1 - wu) + uy * wu", "uy = prevUp[1] * (1 - wu) + uy * wu"),
         ("uz = puz * (1 - wu) + uz * wu", "uz = prevUp[2] * (1 - wu) + uz * wu")],
        ["a blend really blends when the caller aliases its arrays"],
    ),
    (
        "the pole never stiffens the screen-up",
        [("if (fromPole >= POLE_RAMP_DEG) return 1.0", "if (true) return 1.0")],
        ["the up weight is untouched away from the pole and stiffer at it"],
    ),
    (
        "the ramp is linear rather than smoothstep",
        [("1.0 + (POLE_MAX_STRETCH - 1.0) * t * t * (3.0 - 2.0 * t)",
          "1.0 + (POLE_MAX_STRETCH - 1.0) * t")],
        ["the stiffening ramps smoothly rather than stepping"],
    ),
    (
        "the early return checks only the aim's weight",
        [("if (w >= 1.0 && wu >= 1.0) return", "if (w >= 1.0) return")],
        ["a stiff screen up is still blended when the aim is taken whole"],
    ),
    (
        "the screen-up is not re-squared after two different weights",
        [("ux -= d * fx; uy -= d * fy; uz -= d * fz", "ux -= 0.0; uy -= 0.0; uz -= 0.0")],
        ["a stiffer screen up still leaves a valid attitude"],
    ),
    (
        "the clamp bites well below the pole",
        [("file=" + str(PROJ)),
         ("const val MAX_ALTITUDE_DEG = 89.5", "const val MAX_ALTITUDE_DEG = 88.0")],
        ["what that clamp costs anything drawn through the angle view, and it is worse the closer you look"],
    ),
    (
        "the clamp moves the azimuth as well as the altitude",
        [("azimuthDeg = a.azimuthDeg,\n        altitudeDeg = a.altitudeDeg.coerceIn(",
          "azimuthDeg = a.azimuthDeg + 0.1,\n        altitudeDeg = a.altitudeDeg.coerceIn(")],
        ["what that clamp costs anything drawn through the angle view, and it is worse the closer you look"],
    ),
    (
        "screen units stop being normalised to the field",
        [("file=" + str(PROJ)),
         ("invScale = 1.0 / edgeRadius(fovDeg)", "invScale = 1.0")],
        ["what that clamp costs anything drawn through the angle view, and it is worse the closer you look"],
    ),
]

FAILED = re.compile(r"^\d+\) (.+?)\(dev\.mascwa\.pulse", re.M)


def run():
    p = subprocess.run([str(RUNNER), str(TEST)], capture_output=True, text=True, cwd=ROOT)
    out = p.stdout + p.stderr
    if "NOTHING COMPILED" in out or "missing a required jar" in out:
        return None, out
    return set(FAILED.findall(out)), out


def split(subs):
    """A case's target file and its substitutions; `file=<path>` may lead the list."""
    if subs and isinstance(subs[0], str) and subs[0].startswith("file="):
        return Path(subs[0][5:]), subs[1:]
    return SRC, subs


def main():
    baseline, out = run()
    if baseline is None:
        sys.exit("the harness could not build at all:\n" + out)
    if baseline:
        sys.exit("BASELINE IS NOT GREEN — every verdict below would be meaningless:\n"
                 + "\n".join(sorted(baseline)))
    print("baseline: green\n")

    verdicts = []
    tmp = Path(tempfile.mkdtemp())
    # ⚠️ Back up EVERY file any case touches before the first perturbation, not the one being
    # perturbed — a harness that can be killed must restore all of them, and it must know what they
    # were before anything ran rather than what they were when its turn came round.
    targets = {split(subs)[0] for _, subs, _ in CASES}
    backups = {}
    for t in targets:
        b = tmp / t.name
        shutil.copy2(t, b)
        backups[t] = b
    try:
        for name, subs, expected in CASES:
            target, edits = split(subs)
            original = backups[target].read_text()
            text = original
            for needle, replacement in edits:
                if needle not in text:
                    sys.exit(f"PERTURBATION DID NOT MATCH THE SOURCE for {name!r}: {needle!r}")
                text = text.replace(needle, replacement, 1)
            target.write_text(text)
            failures, _ = run()
            target.write_text(original)
            if failures is None:
                verdicts.append((name, "DID NOT COMPILE — perturbation invalid", False))
                continue
            missing = [t for t in expected if t not in failures]
            extra = sorted(failures - set(expected))
            ok = not missing
            detail = "awake" if ok else "ASLEEP — " + ", ".join(missing)
            if extra:
                detail += f" (also failed: {'; '.join(extra)})"
            verdicts.append((name, detail, ok))
    finally:
        for t, b in backups.items():
            t.write_bytes(b.read_bytes())
            if t.read_bytes() != b.read_bytes():
                sys.exit(f"RESTORE FAILED — {t} is not what it was")

    print("restored byte-identical\n")
    for name, detail, ok in verdicts:
        print(f"{'PASS' if ok else 'FAIL'}  {name}: {detail}")
    if not all(v[2] for v in verdicts):
        sys.exit(1)


if __name__ == "__main__":
    main()
