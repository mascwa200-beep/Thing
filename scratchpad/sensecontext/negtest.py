#!/usr/bin/env python3
"""Negative-test the load-bearing rules in SenseContext.kt.

One case per invocation (see neg.sh) so no run can outlive the tool timeout and skip its restore —
the recorded sixth way a green test proves nothing. The shell wrapper restores under `trap ... EXIT`
rather than relying on this process finishing at all.

Every case asserts its perturbation MATCHED the source before writing, and the wrapper asserts the
baseline is green before the first case runs. Both are recorded mechanisms by which a green test
proves nothing.
"""
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/SenseContext.kt"
TEST = "core/telemetry/src/test/java/dev/mascwa/pulse/core/telemetry/SenseContextTest.kt"

# (name, old, new, the test(s) that MUST fail)
CASES = [
    (
        "attention: an unread screen claims nothing",
        "        screenOn == null -> Attention.UNKNOWN\n",
        "        screenOn == null -> Attention.AWAY\n",
        ["nothing read the screen"],
    ),
    (
        "attention: IN_USE needs locked to be KNOWN false",
        "        screenOn && locked == false -> Attention.IN_USE\n",
        "        screenOn && locked != true -> Attention.IN_USE\n",
        ["a lit screen with the lock state unknown"],
    ),
    (
        "attention: Doze outranks a recent unlock",
        "        idle == true -> Attention.AWAY\n"
        "        msSinceUnlock != null && msSinceUnlock <= RECENTLY_PUT_DOWN_MS -> Attention.PRESENT\n",
        "        msSinceUnlock != null && msSinceUnlock <= RECENTLY_PUT_DOWN_MS -> Attention.PRESENT\n"
        "        idle == true -> Attention.AWAY\n",
        ["doze beats a recent unlock"],
    ),
    (
        "attention: the recency window is inclusive",
        "msSinceUnlock <= RECENTLY_PUT_DOWN_MS",
        "msSinceUnlock < RECENTLY_PUT_DOWN_MS",
        ["the recency window has an edge"],
    ),
    (
        "attention: an unseen unlock falls to AWAY, not PRESENT",
        "        else -> Attention.AWAY\n    }",
        "        else -> Attention.PRESENT\n    }",
        ["an unlock nobody saw"],
    ),
    (
        "wantsQuiet: nothing known is null, not false",
        "        return if (known.isEmpty()) null else known.any { it }",
        "        return known.any { it }",
        ["nothing known means nothing claimed"],
    ),
    (
        "wantsQuiet: an UNKNOWN filter is not evidence",
        "            dnd?.takeIf { it != DndFilter.UNKNOWN }?.let { it != DndFilter.OFF },",
        "            dnd?.let { it != DndFilter.OFF },",
        ["an unknown DND filter"],
    ),
    (
        "posture: a moving phone is refused",
        "            restDeviationG > RESTING_TOLERANCE_G -> UNKNOWN\n",
        "",
        ["a moving phone is not asked", "the resting band has an edge"],
    ),
    (
        "posture: a non-finite reading is refused",
        "            !zG.isFinite() || !restDeviationG.isFinite() -> UNKNOWN\n",
        "",
        ["a sensor that reported nothing usable"],
    ),
    (
        "posture: the flat bar is inclusive",
        "            zG >= FLAT_G -> FACE_UP\n            zG <= -FLAT_G -> FACE_DOWN\n",
        "            zG > FLAT_G -> FACE_UP\n            zG < -FLAT_G -> FACE_DOWN\n",
        ["a phone propped at a steep angle"],
    ),
    (
        "describe: the charger is named only when charging",
        "        if (charging == true) {\n",
        "        if (power != null) {\n",
        ["a plug type with nothing plugged in"],
    ),
    (
        "describe: a cool phone contributes no clause",
        "            DeviceClass.Pressure.WARM -> parts += \"warm\"\n",
        "            DeviceClass.Pressure.NONE -> parts += \"cool\"\n"
        "            DeviceClass.Pressure.WARM -> parts += \"warm\"\n",
        ["a cool phone is not described as cool"],
    ),
]


def run_suite() -> tuple[bool, str]:
    p = subprocess.run(
        [str(ROOT / "scratchpad/coretest/run.sh"), TEST],
        cwd=ROOT, capture_output=True, text=True,
    )
    return ("OK (" in p.stdout), p.stdout + p.stderr


def main() -> int:
    which = int(sys.argv[1])
    if which >= len(CASES):
        print(f"no case {which} — there are {len(CASES)}")
        return 2
    name, old, new, must_fail = CASES[which]
    original = SRC.read_text()

    n = original.count(old)
    if n != 1:
        print(f"[{which}] {name}\n  PERTURBATION DID NOT MATCH THE SOURCE ({n} occurrences) — case invalid")
        return 3
    SRC.write_text(original.replace(old, new))

    ok, out = run_suite()
    SRC.write_text(original)

    if ok:
        print(f"[{which}] {name}\n  ⚠️ GUARD ASLEEP — the suite passed with the rule removed")
        return 1
    failed = set(re.findall(r"\d+\) ([^(]+)\(", out))
    missing = [m for m in must_fail if not any(m in f for f in failed)]
    if missing:
        print(f"[{which}] {name}\n  ⚠️ failed, but NOT the named test(s): {missing}\n  failed: {sorted(failed)}")
        return 1
    print(f"[{which}] {name}\n  awake — failed: {sorted(failed)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
