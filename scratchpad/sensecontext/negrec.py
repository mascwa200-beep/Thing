#!/usr/bin/env python3
"""Negative-test the load-bearing rules in AmbientReconcile.kt.

⚠️ The runner below is the third near-copy of the one in negrules.py and negsit.py, and that is
deliberate rather than overlooked: these are verification tools, not shipped code, and the one
failure mode that would matter — a runner that reports "awake" without having tested anything — is
caught by the wrapper asserting the baseline is green before the first case. Converging them means
editing two working harnesses mid-arc to save forty lines, which is the wrong trade while they are
the instrument being relied on. If a fourth is ever wanted, converge then.

Same discipline as its siblings: one case per invocation so no run can outlive the tool timeout and
skip its restore, and every perturbation asserted to have MATCHED the source before anything is
written.
"""
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/AmbientReconcile.kt"
TEST = "core/telemetry/src/test/java/dev/mascwa/pulse/core/telemetry/AmbientReconcileTest.kt"

CASES = [
    (
        "a hold nobody wants any more is let go",
        "                h.action !in wantedActions -> released += Released(h, ReleaseReason.NO_LONGER_WANTED)",
        "                h.action !in wantedActions -> keep += h",
        ["something no longer wanted is released, and says so"],
    ),
    (
        "the ceiling is reached at the ceiling",
        "                nowMs >= h.untilMs -> {",
        "                nowMs >= h.untilMs + 365L * 24 * 60 * 60_000L -> {",
        [
            "a hold that outlives its ceiling is released even though the rule still wants it",
            "a hold released by the backstop does not come straight back",
            "the bar lifts once the rule stops asking",
            "a backstop touches only the hold that reached it",
        ],
    ),
    # ⚠️ THE one that makes the backstop real. Taking `wanted` as the new truth advances fromMs on
    # every heartbeat, so untilMs runs away and the ceiling never arrives — a four-hour hold becomes
    # "four hours after the rule last stopped", which is never.
    (
        "a held intent is never replaced by the fresh one that wants it",
        "                else -> keep += h",
        "                else -> keep += wanted.first { w -> w.action == h.action }",
        ["a held intent keeps the instant it was asserted"],
    ),
    (
        "the bar only survives while its rule is still asking",
        "        expired += state.expiredWhileWanted.filter { it in wantedActions }",
        "        expired += state.expiredWhileWanted",
        ["the bar lifts once the rule stops asking"],
    ),
    (
        "the bar actually bars",
        "        val asserted = wanted.filter { it.action !in heldActions && it.action !in expired }",
        "        val asserted = wanted.filter { it.action !in heldActions }",
        [
            "a hold released by the backstop does not come straight back",
            "letting go by hand bars it from coming straight back",
        ],
    ),
    (
        "nothing already held is asserted a second time",
        "        val asserted = wanted.filter { it.action !in heldActions && it.action !in expired }",
        "        val asserted = wanted.filter { it.action !in expired }",
        ["something already held is not asserted again"],
    ),
    (
        "standing down bars nothing",
        "        state = HoldState(),\n        released = state.held.map { Released(it, why) },",
        "        state = HoldState(expiredWhileWanted = state.held.mapTo(mutableSetOf()) { it.action }),\n        released = state.held.map { Released(it, why) },",
        ["standing down lets go of everything and bars nothing"],
    ),
    (
        "letting go by hand does bar",
        "                expiredWhileWanted = state.expiredWhileWanted + action,",
        "                expiredWhileWanted = state.expiredWhileWanted,",
        ["letting go by hand bars it from coming straight back"],
    ),
    (
        "letting go of something not held is not a way to bar it",
        "        if (gone.isEmpty()) return Reconciliation(state)\n",
        "",
        ["letting go of something that is not held changes nothing"],
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
