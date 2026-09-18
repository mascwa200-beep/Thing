#!/usr/bin/env python3
"""Negative-test the load-bearing rules of the barcode-scan core.

A test that passes proves nothing until it has been watched to fail. This applies one
perturbation at a time to the shipped source, runs the suite, and reports which tests
noticed. Four ways a green run has proved nothing in this project before, all guarded here:

  1. the perturbation never matched the source     -> every edit asserts its own substitution count
  2. it only touched the code without removing it  -> reported per-test, so a no-op reads as ASLEEP
  3. the fixture never reached the branch          -> same; an untouched test list is the tell
  4. the assertion was too weak to see the damage  -> same
  5. the baseline was already failing              -> asserted green before anything is perturbed

Restore runs from a shell trap in the caller, and also here in a finally, because a tool
timeout has left a perturbation in the tree once already.
"""
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/BarcodeScan.kt"
RESULTS = ROOT / "core/telemetry/build/test-results/test"

GRADLE = [
    str(ROOT / "gradlew"), ":core:telemetry:test",
    "--tests", "*BarcodeScanTest*",
    "--configure-on-demand", "--no-configuration-cache", "--rerun-tasks", "-q",
]

# (name, needle, replacement, tests expected to fail)
PERTURBATIONS = [
    (
        "number-system gate removed: an EAN-8 gets expanded into a product that does not exist",
        "        if (ns != '0' && ns != '1') return null\n",
        "",
        ["onlyTheTwoNumberSystemsUpcEActuallyUsesAreExpanded"],
    ),
    (
        "length gate removed: anything eight-ish is treated as a UPC-E",
        "        if (e.length != 8) return null\n",
        "        if (e.length < 8) return null\n",
        ["somethingThatIsNotAUpcEExpandsToNothing"],
    ),
    (
        "the '3' branch folded into the default: five zeros become four and every digit shifts",
        "            '3' -> \"${d[0]}${d[1]}${d[2]}00000${d[3]}${d[4]}\"\n",
        "",
        ["aUpcEExpandsToTheUpcAItStandsFor",
         "anExpandedCodePassesTheChecksumThatTheCompressedFormCannotBeJudgedBy"],
    ),
    (
        "the '4' branch folded into the default",
        "            '4' -> \"${d[0]}${d[1]}${d[2]}${d[3]}00000${d[4]}\"\n",
        "",
        # Two, not one: the checksum test carries the same fixture and notices independently.
        # My first run of this predicted one and reported a live guard as asleep.
        ["aUpcEExpandsToTheUpcAItStandsFor",
         "anExpandedCodePassesTheChecksumThatTheCompressedFormCannotBeJudgedBy"],
    ),
    (
        "check digit dropped instead of carried through",
        "        return \"$ns$body$check\"",
        "        return \"$ns${body}0\"",
        ["aUpcEExpandsToTheUpcAItStandsFor",
         "anExpandedCodePassesTheChecksumThatTheCompressedFormCannotBeJudgedBy",
         "theExpandedFormAndTheProductsOwnKeyAreTheSameNumber"],
    ),
]


def run_suite():
    """-> (ran_at_all, {test name: passed})"""
    if RESULTS.exists():
        shutil.rmtree(RESULTS)
    proc = subprocess.run(GRADLE, cwd=ROOT, capture_output=True, text=True, timeout=900)
    if not RESULTS.exists():
        out = (proc.stdout + proc.stderr)
        if "error:" in out or "Compilation error" in out:
            return "did not compile", {}
        return "no results and not a compile error either", {}
    outcome = {}
    for xml in RESULTS.glob("*.xml"):
        for case in ET.parse(xml).getroot().iter("testcase"):
            failed = any(c.tag in ("failure", "error") for c in case)
            outcome[case.get("name")] = not failed
    if not outcome:
        return "filter matched nothing", {}
    return None, outcome


def main():
    original = SRC.read_text()
    try:
        print("baseline...", flush=True)
        err, base = run_suite()
        if err:
            sys.exit(f"BASELINE {err}")
        if not all(base.values()):
            sys.exit("BASELINE NOT GREEN: " + ", ".join(n for n, ok in base.items() if not ok))
        print(f"baseline: {len(base)} tests, all passing\n")

        asleep = 0
        for name, needle, repl, expected in PERTURBATIONS:
            n = original.count(needle)
            assert n == 1, f"perturbation {name!r} matched {n} times, not once — it would not apply"
            SRC.write_text(original.replace(needle, repl, 1))
            err, got = run_suite()
            if err:
                print(f"  !! {name}\n     {err} — this is NOT evidence a guard is awake")
                asleep += 1
                continue
            failed = sorted(n for n, ok in got.items() if not ok)
            ok = set(failed) == set(expected)
            print(f"  {'OK  ' if ok else 'ASLEEP'} {name}")
            print(f"        failed: {failed or '(nothing — the rule is untested)'}")
            if not ok:
                print(f"        expected: {sorted(expected)}")
                asleep += 1
        print()
        print("all guards awake" if asleep == 0 else f"{asleep} guard(s) not awake")
        return 1 if asleep else 0
    finally:
        SRC.write_text(original)


if __name__ == "__main__":
    sys.exit(main())
