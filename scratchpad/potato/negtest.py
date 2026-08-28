#!/usr/bin/env python3
"""Negative-test a set of load-bearing rules by breaking each one and requiring the
named test(s) to fail.

Guards against the five recorded ways a green test proves nothing:
  1. the perturbation never matched the source     -> asserted before running
  2. it only touched the code without removing it  -> caller's problem; keep perturbations real
  3. the fixture never reached the branch          -> shown by "expected test did NOT fail"
  4. the assertion was too weak to see the damage  -> same
  5. the expected test was ALREADY failing         -> baseline asserted green FIRST
"""
import re, shutil, subprocess, sys, xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path("/home/user/Thing")
GRADLE = ["./gradlew", "--configure-on-demand", "--no-configuration-cache", "-q"]

def run(module, filt):
    subprocess.run(GRADLE + [f":{module}:test", "--tests", filt],
                   cwd=ROOT, capture_output=True, text=True)
    results = {}
    d = ROOT / module.replace(":", "/") / "build/test-results/test"
    for x in d.glob("TEST-*.xml"):
        root = ET.parse(x).getroot()
        for tc in root.iter("testcase"):
            bad = tc.find("failure") is not None or tc.find("error") is not None
            results[tc.get("name")] = not bad
    return results

def main(module, filt, target, cases):
    src = ROOT / target
    original = src.read_text()
    backup = original

    base = run(module, filt)
    if not base:
        sys.exit("FATAL: the filter matched no tests at all — nothing was verified.")
    failing = [k for k, ok in base.items() if not ok]
    if failing:
        sys.exit(f"FATAL: baseline is not green, so every result below would be meaningless.\n"
                 f"  already failing: {failing}")
    print(f"baseline: {len(base)} tests, all passing\n")

    ok = True
    try:
        for name, old, new, expect in cases:
            if old not in original:
                print(f"[{name}] SKIPPED — perturbation did not match the source. NOT TESTED.")
                ok = False
                continue
            src.write_text(original.replace(old, new, 1))
            got = run(module, filt)
            if not got:
                print(f"[{name}] did not compile — inconclusive, not evidence.")
                ok = False
            else:
                broke = sorted(k for k, good in got.items() if not good)
                hit = [e for e in expect if any(e in b for b in broke)]
                if len(hit) == len(expect):
                    print(f"[{name}] AWAKE — broke {len(broke)}: {broke[:3]}")
                else:
                    print(f"[{name}] ASLEEP — expected {expect} to fail; actually failing: {broke}")
                    ok = False
            src.write_text(original)
    finally:
        src.write_text(backup)
        assert src.read_text() == backup, "restore failed"
    print("\nALL GUARDS AWAKE" if ok else "\nSOME GUARDS ASLEEP")
    return 0 if ok else 1

if __name__ == "__main__":
    sys.exit(0)
