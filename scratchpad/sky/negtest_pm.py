#!/usr/bin/env python3
"""Negative-test the S9b proper-motion rules.

Same three self-assertions as negtest.py, each of which has silently invalidated a run before:
  * the baseline suite is GREEN — a test already failing makes every perturbation look effective;
  * the perturbation actually MATCHED the source, exactly once;
  * the file is restored byte-for-byte in a `finally`, so a kill cannot leave a defect in the tree.
"""
import subprocess
import sys
import shutil
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[2]
PM = ROOT / "core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/ProperMotion.kt"
EPH = ROOT / "core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/Ephemeris.kt"
TEST = ROOT / "core/telemetry/src/test/java/dev/mascwa/pulse/core/telemetry/ProperMotionTest.kt"
RUN = ROOT / "scratchpad/coretest/run.sh"

# (label, file, old, new, tests that MUST fail)
CASES = [
    (
        "the projected motion is not un-projected at all",
        PM,
        "val ra = raDeg + pmRaMasPerYear * years / (MAS_PER_DEGREE * shrink)",
        "val ra = raDeg + pmRaMasPerYear * years / MAS_PER_DEGREE",
        [
            "therightascensionstepisdividedbythecosineofdeclination",
            "thecosinegrowsthestepwithoutlimittowardthepole",
            "astaratthepolegetsaboundedshiftratherthananarbitraryone",
            "thefloordoesnotengageforastarmerelyclosetothepole",
            # NOT the occultation-budget test, and my first run listed it wrongly. Regulus sits at
            # declination 11.97, where the cosine is 0.978 -- so removing the division changes its
            # displacement by two per cent, against a test whose threshold has far more headroom
            # than that. That test guards the SCALE of the motion, not the projection.
        ],
    ),
    (
        "the cosine is multiplied rather than divided",
        PM,
        "val ra = raDeg + pmRaMasPerYear * years / (MAS_PER_DEGREE * shrink)",
        "val ra = raDeg + pmRaMasPerYear * years * shrink / MAS_PER_DEGREE",
        [
            "therightascensionstepisdividedbythecosineofdeclination",
            "thecosinegrowsthestepwithoutlimittowardthepole",
            "astaratthepolegetsaboundedshiftratherthananarbitraryone",
            "thefloordoesnotengageforastarmerelyclosetothepole",
        ],
    ),
    (
        "the cosine is applied to declination too",
        PM,
        "val dec = decDeg + pmDecMasPerYear * years / MAS_PER_DEGREE",
        "val dec = decDeg + pmDecMasPerYear * years / (MAS_PER_DEGREE * shrink)",
        ["thedeclinationstepisnotdividedbyanything"],
    ),
    (
        "the pole floor is dropped",
        PM,
        "val shrink = cos(Math.toRadians(decDeg)).let { if (abs(it) < MIN_COS) MIN_COS else it }",
        "val shrink = cos(Math.toRadians(decDeg))",
        ["astaratthepolegetsaboundedshiftratherthananarbitraryone"],
    ),
    (
        "the floor engages everywhere, not just at the pole",
        PM,
        "private const val MIN_COS = 1e-6",
        "private const val MIN_COS = 1.0",
        [
            "therightascensionstepisdividedbythecosineofdeclination",
            "thecosinegrowsthestepwithoutlimittowardthepole",
            "astaratthepolegetsaboundedshiftratherthananarbitraryone",
            "thefloordoesnotengageforastarmerelyclosetothepole",
            # NOT the occultation-budget test, and my first run listed it wrongly. Regulus sits at
            # declination 11.97, where the cosine is 0.978 -- so removing the division changes its
            # displacement by two per cent, against a test whose threshold has far more headroom
            # than that. That test guards the SCALE of the motion, not the projection.
        ],
    ),
    (
        "right ascension is not wrapped",
        PM,
        "out[0] = ((ra % 360.0) + 360.0) % 360.0",
        "out[0] = ra",
        ["rightascensionwrapsratherthanrunningpastthecircle"],
    ),
    (
        "declination is not clamped at the pole",
        PM,
        "out[1] = dec.coerceIn(-90.0, 90.0)",
        "out[1] = dec",
        ["declinationclampsatthepoleratherthanrunningpastit"],
    ),
    (
        "the epoch offset is dropped from yearsSince",
        PM,
        "Ephemeris.julianYear(epochMs) - catalogueEpochYear",
        "Ephemeris.julianYear(epochMs) - 2000.0",
        ["thecatalogueepochissubtractedsoadeepercataloguecarriesless"],
    ),
    (
        "a julian year becomes a calendar year",
        EPH,
        "fun julianYear(epochMs: Long): Double = 2000.0 + (julianDate(epochMs) - J2000) / 365.25",
        "fun julianYear(epochMs: Long): Double = 2000.0 + (julianDate(epochMs) - J2000) / 365.0",
        ["yearsarejulianyearsandnotcalendarones"],
    ),
]


def run():
    p = subprocess.run([str(RUN), str(TEST)], capture_output=True, text=True, cwd=ROOT)
    out = p.stdout + p.stderr
    if "JUnit version" not in out:
        return None, out  # never even ran
    # ⚠️ Test names here are backtick-quoted English sentences, so the JUnit report prints them with
    # spaces. Strip the spaces on both sides rather than trying to match them.
    failed = {re.sub(r"\s+", "", m) for m in re.findall(r"^\d+\) (.+?)\(dev\.", out, re.M)}
    return failed, out


def main():
    base, out = run()
    if base is None:
        sys.exit("the suite did not run at all:\n" + out[-3000:])
    if base:
        sys.exit(f"BASELINE IS NOT GREEN — {sorted(base)} already fail; every verdict below "
                 "would be meaningless")
    count = re.search(r"OK \((\d+) tests\)", out).group(1)
    print(f"baseline: green ({count} tests)\n")

    ok = True
    for label, path, old, new, expect in CASES:
        original = path.read_text()
        if original.count(old) != 1:
            print(f"SKIP  {label}: the perturbation matched {original.count(old)} times, not 1")
            ok = False
            continue
        backup = path.with_suffix(path.suffix + ".negbak")
        shutil.copy2(path, backup)
        try:
            path.write_text(original.replace(old, new, 1))
            failed, detail = run()
            if failed is None:
                print(f"?     {label}: did not compile")
                ok = False
                continue
            missing = [t for t in expect if t not in failed]
            extra = sorted(failed - set(expect))
            if missing:
                print(f"ASLEEP {label}: expected {missing} to fail, saw {sorted(failed)}")
                ok = False
            else:
                note = f" (also {len(extra)} more)" if extra else ""
                print(f"awake  {label}: {len(expect)} named tests failed{note}")
        finally:
            shutil.copy2(backup, path)
            backup.unlink()
            assert path.read_text() == original, f"RESTORE FAILED for {path}"

    after, _ = run()
    if after:
        sys.exit(f"the tree did not come back green: {sorted(after)}")
    print("\nrestored, suite green again")
    sys.exit(0 if ok else 1)


main()
