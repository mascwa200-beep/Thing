#!/usr/bin/env python3
"""Negative-test every load-bearing rule in ReferenceCircles.

Discipline, and every clause of it has been earned by a guard that came back green for the wrong
reason at least once in this repository:

  * the baseline is asserted GREEN before anything is perturbed — a suite already failing reports
    every guard "awake" for a reason nothing to do with the perturbation;
  * each perturbation asserts it MATCHED the source before the file is written, because a
    substitution that silently did nothing looks exactly like a rule that holds;
  * the perturbation must REMOVE the property, not merely touch the line;
  * the failing test names are read out of the JUnit output, so "the build failed" is never taken
    as evidence the right guard fired;
  * the file is restored in a `finally` and the restore is checked with a byte comparison.
"""
import filecmp
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path("/home/user/Thing")
SRC = ROOT / "core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/ReferenceCircles.kt"
TEST = "core/telemetry/src/test/java/dev/mascwa/pulse/core/telemetry/ReferenceCirclesTest.kt"

# (label, find, replace, tests that MUST fail)
CASES = [
    (
        "longitudeOf steps by a whole span short",
        "arc * ARC_SPAN_DEG + index * STEP_DEG",
        "arc * (ARC_SPAN_DEG - STEP_DEG) + index * STEP_DEG",
        ["each run ends exactly where the next begins",
         "the last run closes the circle and nothing runs past it"],
    ),
    (
        "PER_ARC drops the shared endpoint",
        "(ARC_SPAN_DEG / STEP_DEG).toInt() + 1",
        "(ARC_SPAN_DEG / STEP_DEG).toInt()",
        ["the runs tile the circle exactly once",
         "each run ends exactly where the next begins",
         "the last run closes the circle and nothing runs past it",
         "the vertex count is exactly what a caller preallocates"],
    ),
    (
        "the obliquity is applied to x instead of y",
        "out[0] = cos(l)\n        out[1] = sin(l) * cos(e)",
        "out[0] = cos(l) * cos(e)\n        out[1] = sin(l)",
        ["the ecliptic meets the equator at the equinoxes, at every obliquity",
         "equinox agrees with both circles",
         "the ecliptic is a great circle whose pole is at the obliquity"],
    ),
    (
        "the ecliptic's height forgets the longitude",
        "out[2] = sin(l) * sin(e)",
        "out[2] = sin(e)",
        # ⚠️ NOT `a zero obliquity puts the ecliptic exactly on the equator`, and my first list said
        # it was. With `sin(e)` in place of `sin(l) * sin(e)` and an obliquity of zero the answer is
        # still 0.0, so that perturbation is a literal no-op for that fixture. Expecting a failure
        # there would have reported a perfectly awake guard as asleep.
        ["the ecliptic meets the equator at the equinoxes, at every obliquity",
         "equinox agrees with both circles",
         "the ecliptic reaches exactly the obliquity and no further",
         "the ecliptic is a great circle whose pole is at the obliquity"],
    ),
    (
        "the equator drifts off declination zero",
        # ⚠️ Anchored on the preceding line: a bare `out[2] = 0.0` also appears in `equinox`, and the
        # harness refused to write a perturbation that matched twice — which is the guard doing its
        # job, since perturbing both would have tested something else entirely.
        "out[1] = sin(ra)\n        out[2] = 0.0",
        "out[1] = sin(ra)\n        out[2] = 1e-9",
        ["the equator lies exactly on declination zero",
         "the equator is the same thing SkyProjection already computes",
         "a zero obliquity puts the ecliptic exactly on the equator"],
    ),
    (
        "the obliquity argument is ignored for a constant",
        "val e = obliquityDeg * DEG",
        "val e = 23.4392911 * DEG",
        ["the ecliptic reaches exactly the obliquity and no further",
         "a zero obliquity puts the ecliptic exactly on the equator",
         "the obliquity is honoured rather than assumed"],
    ),
    (
        "ARC_SPAN_DEG no longer divides the circle",
        "const val ARC_SPAN_DEG = 360.0 / ARCS",
        "const val ARC_SPAN_DEG = 359.0 / ARCS",
        ["the runs tile the circle exactly once",
         "the last run closes the circle and nothing runs past it"],
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
    sys.exit("BASELINE IS NOT GREEN — nothing below would mean anything:\n" + base[-2000:])
print("baseline:", re.search(r"OK \(\d+ tests\)", base).group(0))

original = SRC.read_text()
backup = Path(tempfile.mkdtemp()) / "ReferenceCircles.kt"
shutil.copy2(SRC, backup)
asleep = []
try:
    for label, find, repl, expect in CASES:
        n = original.count(find)
        assert n == 1, f"{label}: the perturbation matched {n} times, not once — it would test nothing"
        SRC.write_text(original.replace(find, repl))
        out = run()
        if "OK (" in out:
            asleep.append(label)
            print(f"  ASLEEP  {label} — the suite still passes with the rule gone")
            continue
        fails = failing(out)
        missing = [t for t in expect if t not in fails]
        extra = sorted(fails - set(expect))
        status = "awake  " if not missing else "PARTIAL"
        print(f"  {status} {label}: {len(fails)} failed")
        if missing:
            print(f"           expected but did not fail: {missing}")
            asleep.append(label)
        if extra:
            print(f"           also failed (fine, but worth seeing): {extra}")
finally:
    SRC.write_text(original)
    assert filecmp.cmp(SRC, backup, shallow=False), "RESTORE FAILED — the source is not as it was"
    after = run()
    assert "OK (" in after, "the restored source does not pass:\n" + after[-2000:]
    print("restored, and green again:", re.search(r"OK \(\d+ tests\)", after).group(0))

print()
print("ALL GUARDS AWAKE" if not asleep else f"ASLEEP: {asleep}")
sys.exit(1 if asleep else 0)
