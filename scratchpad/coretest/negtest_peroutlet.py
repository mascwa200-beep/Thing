#!/usr/bin/env python3
"""Negative-test BreakingNews.perOutlet: break each rule, prove the right test notices.

⚠️ Restores in a `finally` AND the caller wraps this in a shell `trap ... EXIT`, because a harness
that can be killed by a timeout has left a perturbation in the tree here before.
⚠️ The baseline is asserted green first: a suite that was already failing makes every case below
report "awake" for a reason that has nothing to do with the perturbation.
"""
import filecmp
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path("/home/user/Thing")
SRC = ROOT / "core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/BreakingNews.kt"
TEST = "core/telemetry/src/test/java/dev/mascwa/pulse/core/telemetry/BreakingNewsTest.kt"

# (label, find, replace, tests that MUST fail)
CASES = [
    (
        "first-wins instead of newest-per-outlet (what `select` would have given us)",
        "if (held == null || timeMs(item) > timeMs(held)) newestPer[o] = item",
        "if (held == null) newestPer[o] = item",
        ["one story per outlet, and it is that outlet's newest"],
    ),
    (
        "trust ignored, so ordering is recency only",
        "compareByDescending<Map.Entry<String, T>> { prefer(it.key) }\n                    .thenByDescending { timeMs(it.value) },",
        "compareByDescending<Map.Entry<String, T>> { timeMs(it.value) },",
        ["preferred outlets take the slots first, even when a stranger is fresher"],
    ),
    (
        "trust treated as a FILTER, so a quiet morning renders nothing",
        "return newestPer.entries\n            .sortedWith(",
        "return newestPer.entries\n            .filter { prefer(it.key) }\n            .sortedWith(",
        ["an unpreferred outlet is still shown rather than leaving the block empty"],
    ),
    (
        "a nameless outlet is allowed to represent a newsroom",
        "if (o.isBlank()) continue",
        "if (false) continue",
        ["an outlet that cannot be named is dropped"],
    ),
    (
        "the max bound is dropped",
        ".take(max)",
        "",
        ["max bounds the result and zero asks for nothing"],
    ),
]


def run():
    p = subprocess.run(
        ["./scratchpad/coretest/run.sh", TEST], cwd=ROOT, capture_output=True, text=True
    )
    return p.stdout + p.stderr


def failing(out):
    return set(re.findall(r"^\d+\) ([^(]+)\(", out, re.M))


base = run()
if "OK (" not in base:
    sys.exit("BASELINE IS NOT GREEN — nothing below would mean anything:\n" + base[-3000:])
print("baseline:", re.search(r"OK \(\d+ tests\)", base).group(0))

original = SRC.read_text()
tmp = Path(tempfile.mkdtemp())
shutil.copy2(SRC, tmp / SRC.name)
asleep = []
try:
    for label, find, repl, expect in CASES:
        n = original.count(find)
        assert n == 1, f"{label}: perturbation matched {n} times, not once — it would test nothing"
        SRC.write_text(original.replace(find, repl))
        out = run()
        SRC.write_text(original)
        if "OK (" in out:
            asleep.append(label)
            print(f"  ASLEEP  {label}")
            continue
        if "error:" in out and "NOTHING COMPILED" in out:
            asleep.append(label)
            print(f"  INVALID {label} — it did not compile, which is not evidence of a live guard")
            continue
        fails = failing(out)
        missing = [t for t in expect if t not in fails]
        print(f"  {'awake  ' if not missing else 'PARTIAL'} {label}: {len(fails)} failed")
        if missing:
            print(f"           expected but did not fail: {missing}")
            asleep.append(label)
finally:
    SRC.write_text(original)
    assert filecmp.cmp(SRC, tmp / SRC.name, shallow=False), "RESTORE FAILED"
    after = run()
    assert "OK (" in after, "the restored tree does not pass:\n" + after[-2000:]
    print("restored, and green again:", re.search(r"OK \(\d+ tests\)", after).group(0))

print()
print("ALL GUARDS AWAKE" if not asleep else f"ASLEEP/INVALID: {asleep}")
sys.exit(1 if asleep else 0)
