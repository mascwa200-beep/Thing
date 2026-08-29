#!/usr/bin/env python3
"""Negative-test the S9 rules: break each one, require exactly the test that names it to fail.

Three things this asserts about ITSELF before trusting any verdict, because each has silently
invalidated a run in this project before:
  * the baseline suite is GREEN — a test already failing makes every perturbation look effective;
  * the perturbation actually MATCHED the source — a substitution that hit nothing reports a rule
    asleep when it was never exercised;
  * the file is restored byte-for-byte, in a `finally`, so a kill cannot leave a defect in the tree.
"""
import subprocess
import sys
import shutil
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[2]
EPH = ROOT / "core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/Ephemeris.kt"
TEST = ROOT / "core/telemetry/src/test/java/dev/mascwa/pulse/core/telemetry/EphemerisTest.kt"
RUN = ROOT / "scratchpad/coretest/run.sh"

# (label, file, old, new, tests that MUST fail)
CASES = [
    (
        "meanOfDateToJ2000 rotates the wrong way",
        EPH,
        "precessRotate(raDeg, decDeg, centuries(julianDateTT(epochMs)), toDate = false)",
        "precessRotate(raDeg, decDeg, centuries(julianDateTT(epochMs)), toDate = true)",
        ["theMeanEquinoxRotationsAreAnExactInversePair", "theTwoDirectionsLandTwiceTheDriftApart"],
    ),
    (
        "j2000ToMeanOfDate rotates the wrong way",
        EPH,
        "precessRotate(raDeg, decDeg, centuries(julianDateTT(epochMs)), toDate = true)",
        "precessRotate(raDeg, decDeg, centuries(julianDateTT(epochMs)), toDate = false)",
        ["theMeanEquinoxRotationsAreAnExactInversePair", "theTwoDirectionsLandTwiceTheDriftApart"],
    ),
    (
        "j2000ToMeanOfDate quietly becomes the apparent one",
        EPH,
        "fun j2000ToMeanOfDate(raDeg: Double, decDeg: Double, epochMs: Long): DoubleArray =\n"
        "        precessRotate(raDeg, decDeg, centuries(julianDateTT(epochMs)), toDate = true)",
        "fun j2000ToMeanOfDate(raDeg: Double, decDeg: Double, epochMs: Long): DoubleArray {\n"
        "        val a = precessFromJ2000(raDeg, decDeg, epochMs)\n"
        "        return doubleArrayOf(a.rightAscensionDeg, a.declinationDeg)\n"
        "    }",
        ["theMeanRotationIsNotTheApparentOne"],
    ),
    (
        "precessVectorToJ2000 forgets to rotate",
        EPH,
        "val j = meanOfDateToJ2000(ra, dec, epochMs)",
        "val j = doubleArrayOf(ra, dec)",
        ["theVectorFormAgreesWithTheDegreeFormAndStaysAUnitVector"],
    ),
    (
        "precessVectorToJ2000 drops the asin clamp",
        EPH,
        "val dec = asin(v[2].coerceIn(-1.0, 1.0)) / DEG",
        "val dec = asin(v[2]) / DEG",
        ["thePoleIsSafeBecauseTheRightAscensionDropsOut"],
    ),
    (
        "the round trip is measured through the lossy acos again",
        TEST,
        "                    var dRa = abs(back[0] - ra)\n"
        "                    if (dRa > 180.0) dRa = 360.0 - dRa\n"
        "                    worst = maxOf(worst, maxOf(dRa, abs(back[1] - dec)))",
        "                    worst = maxOf(worst, Ephemeris.angularSeparationDeg(\n"
        "                        ra.toDouble(), dec.toDouble(), back[0], back[1]))",
        ["theMeanEquinoxRotationsAreAnExactInversePair", "negatingTheEpochIsNotTheInverseAndIsFarWorse"],
    ),
]


def run():
    p = subprocess.run([str(RUN), str(TEST)], capture_output=True, text=True, cwd=ROOT)
    out = p.stdout + p.stderr
    if "JUnit version" not in out:
        return None, out  # never even ran
    failed = set(re.findall(r"^\d+\) (\w+)\(", out, re.M))
    return failed, out


def main():
    base, out = run()
    if base is None:
        sys.exit("the suite did not run at all:\n" + out[-2000:])
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
            failed, _ = run()
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
                note = f" (also {extra})" if extra else ""
                print(f"awake  {label}: {sorted(failed & set(expect))} failed{note}")
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
