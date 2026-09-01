#!/usr/bin/env python3
"""Negative-test every load-bearing rule in BillingCycle.

Discipline as recorded: the baseline is asserted green first; every perturbation must match exactly
once before the file is written; the perturbation must REMOVE the property rather than touch the
line; failing test names are read out of the JUnit output; the file is restored in a `finally` and
byte-compared, and the suite re-run to prove it.

⚠️ Run one harness per Bash invocation — a run that outlives the tool's timeout is SIGTERMed and
`finally` does not run, which is how a perturbation once got left in the tree.
"""
import filecmp
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path("/home/user/Thing")
SRC = ROOT / "core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/BillingCycle.kt"
TEST = "core/telemetry/src/test/java/dev/mascwa/pulse/core/telemetry/BillingCycleTest.kt"

CASES = [
    (
        "the cycle day is not clamped to the month's length",
        "        day.coerceIn(1, 31).coerceAtMost(daysInMonth(year, month))",
        "        day.coerceIn(1, 31)",
        ["a cycle day the month does not have falls back to its last",
         "the thirtieth of April is when a thirty-first cycle rolls over",
         "February takes the same treatment, leap year or not",
         "how far in never runs past how long"],
    ),
    (
        "startOf compares against the raw setting instead of this month's effective day",
        "        val thisMonth = effectiveDay(year, month, cycleDay)",
        "        val thisMonth = cycleDay",
        ["the thirtieth of April is when a thirty-first cycle rolls over",
         "February takes the same treatment, leap year or not",
         "how far in never runs past how long"],
    ),
    (
        "the day it resets counts as day zero",
        "            dayOfMonth - start.day + 1",
        "            dayOfMonth - start.day",
        # ⚠️ NOT `counting across a month boundary`, and my first list said it was. `daysInto` has
        # two branches and this perturbs only the SAME-month one; every fixture in that test starts
        # in the previous month, so it goes through the other branch and cannot notice. Expecting a
        # failure there reported a perfectly awake guard as asleep. The cross-month branch has its
        # own perturbation below.
        ["the day it resets is day one",
         "how far in never runs past how long"],
    ),
    (
        "counting across a month boundary forgets a day",
        "            daysInMonth(start.year, start.month) - start.day + 1 + dayOfMonth",
        "            daysInMonth(start.year, start.month) - start.day + dayOfMonth",
        ["counting across a month boundary counts the days, not the dates"],
    ),
    (
        "the cycle length is taken from its own month rather than the gap to the next start",
        "        return daysInMonth(start.year, start.month) - start.day + next",
        "        return daysInMonth(start.year, start.month)",
        ["a cycle is as long as the gap to the next one, not as long as its month",
         "how far in never runs past how long"],
    ),
    (
        "the leap rule keeps only its every-fourth-year half",
        "    fun isLeap(year: Int): Boolean = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0",
        "    fun isLeap(year: Int): Boolean = year % 4 == 0",
        ["the leap rule is the whole rule, not the every-fourth-year half of it"],
    ),
    (
        "a month number that is not a month gets 31 days rather than none",
        "        else -> 0\n    }",
        "        else -> 31\n    }",
        ["every month is as long as it is"],
    ),
    (
        "a stored setting outside the range is trusted",
        "        day.coerceIn(1, 31).coerceAtMost",
        "        day.coerceAtMost",
        ["a stored setting outside one to thirty-one is coerced rather than trusted"],
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
backup = Path(tempfile.mkdtemp()) / "BillingCycle.kt"
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
        if "error:" in out and "There w" not in out:
            asleep.append(label)
            print(f"  INVALID {label} — the perturbation does not compile, so nothing was tested")
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
