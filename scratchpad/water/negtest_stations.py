#!/usr/bin/env python3
"""Negative-test every load-bearing rule in WaterStations.

Same discipline as the sky harnesses, and every clause of it was earned by a guard in this
repository that once came back green for the wrong reason:

  * the baseline is asserted GREEN first — a suite already failing reports every guard "awake" for
    a reason nothing to do with the perturbation;
  * each perturbation asserts it MATCHED EXACTLY ONCE before the file is written, because a
    substitution that silently did nothing looks identical to a rule that holds, and one that
    matched twice is testing something other than what it claims;
  * the perturbation must REMOVE the property rather than merely touch the line;
  * failing test names are read out of the JUnit output, so "it failed" is never taken as evidence
    that the RIGHT guard fired;
  * the file is restored in a `finally`, byte-compared, and the suite re-run to prove it.

⚠️ Run one harness per Bash invocation. A run whose total time can exceed the tool's timeout is
SIGTERMed, and `finally` does not run — which is how a perturbation once got left in the tree.
"""
import filecmp
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path("/home/user/Thing")
SRC = ROOT / "core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/WaterStations.kt"
TEST = "core/telemetry/src/test/java/dev/mascwa/pulse/core/telemetry/WaterStationsTest.kt"

# The whole `nearest` loop, so the "closest first, then check the reach" mistake can be expressed
# as the plausible implementation it actually is rather than as a mangled line.
NEAREST_BODY = """        var best: Near? = null
        for (s in stations) {
            val km = Geodesy.distanceMeters(lat, lon, s.lat, s.lon) / 1000.0
            if (!km.isFinite()) continue
            val reach = if (s.kind == Kind.TIDE) TIDE_REACH_KM else LEVEL_REACH_KM
            if (km > reach) continue
            if (best == null || km < best.km) best = Near(s, km)
        }
        return best"""

CLOSEST_THEN_REACH = """        val closest = stations.minByOrNull {
            Geodesy.distanceMeters(lat, lon, it.lat, it.lon)
        } ?: return null
        val km = Geodesy.distanceMeters(lat, lon, closest.lat, closest.lon) / 1000.0
        val reach = if (closest.kind == Kind.TIDE) TIDE_REACH_KM else LEVEL_REACH_KM
        return if (km <= reach) Near(closest, km) else null"""

# (label, find, replace, tests that MUST fail)
CASES = [
    (
        "one shared reach instead of a reach per kind",
        "val reach = if (s.kind == Kind.TIDE) TIDE_REACH_KM else LEVEL_REACH_KM",
        "val reach = LEVEL_REACH_KM",
        ["the same distance is near enough for a lake and too far for a tide",
         "a nearer station of the wrong kind does not hide a usable one"],
    ),
    (
        "an unknown product code is guessed at instead of refused",
        '"W" -> Kind.LEVEL\n            else -> return null',
        '"W" -> Kind.LEVEL\n            else -> Kind.TIDE',
        ["an unknown product code is refused rather than guessed at"],
    ),
    (
        "the closest station is picked first and its reach checked afterwards",
        NEAREST_BODY,
        CLOSEST_THEN_REACH,
        ["a nearer station of the wrong kind does not hide a usable one"],
    ),
    (
        "upcoming trusts the feed's order",
        "turns.filter { it.at > nowLocal }.sortedBy { it.at }.take(max)",
        "turns.filter { it.at > nowLocal }.take(max)",
        ["out of order input still comes back in order"],
    ),
    (
        "upcoming stops filtering out what has already happened",
        "turns.filter { it.at > nowLocal }.sortedBy { it.at }.take(max)",
        "turns.sortedBy { it.at }.take(max)",
        ["only what is still ahead",
         "the day rolls over on its own",
         "past the last prediction there is nothing to say",
         "the tide line names the next two turns by the clock"],
    ),
    (
        "trim1 goes back to banker's rounding",
        "val a = kotlin.math.floor(kotlin.math.abs(v) * 10.0 + 0.5).toLong()",
        "val a = kotlin.math.abs(kotlin.math.round(v * 10.0)).toLong()",
        ["a tie rounds away from zero, in both directions"],
    ),
    (
        "trim1 drops the sign",
        'return (if (v < 0.0 && a != 0L) "-" else "")',
        'return ("")',
        ["a negative height keeps its sign",
         "a tie rounds away from zero, in both directions"],
    ),
    (
        "trim1 prints a signed zero",
        "if (v < 0.0 && a != 0L)",
        "if (v < 0.0)",
        ["a negative height keeps its sign"],
    ),
    (
        "an unreadable gauge is rendered anyway",
        "if (!feet.isFinite()) return null",
        "if (false) return null",
        ["an unreadable gauge draws nothing"],
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
backup = Path(tempfile.mkdtemp()) / "WaterStations.kt"
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
