#!/usr/bin/env python3
"""Negative-test the load-bearing rules in AmbientSituation.kt.

Same shape as negtest.py beside it, and for the same recorded reasons: one case per invocation so no
run can outlive the tool timeout and skip its restore, every perturbation asserted to have MATCHED
the source before anything is written, and the wrapper asserting the baseline is green first.
"""
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/AmbientSituation.kt"
TEST = "core/telemetry/src/test/java/dev/mascwa/pulse/core/telemetry/AmbientSituationTest.kt"

CASES = [
    (
        "the bar below which nothing is claimed",
        "    const val MIN_SCORE = 4",
        "    const val MIN_SCORE = 3",
        ["one weak signal is not a situation", "few voices alone are not company"],
    ),
    (
        "the incumbent bonus ranks and never scores",
        "            confidence = (best.score / CONFIDENT).coerceIn(0f, 1f),",
        "            confidence = (bestRank / CONFIDENT).coerceIn(0f, 1f),",
        ["holding a situation does not make it look better evidenced"],
    ),
    (
        "there is a bonus at all",
        "    const val INCUMBENT_BONUS = 1",
        "    const val INCUMBENT_BONUS = 0",
        ["a level reading goes to whoever held it"],
    ),
    (
        "the bar is checked against the RAW score, so incumbency cannot carry a dead situation",
        "        if (best.score < MIN_SCORE) return unknown(prev, nowMs)",
        "        if (best.score + (if (best.situation == incumbent) INCUMBENT_BONUS else 0) < MIN_SCORE) return unknown(prev, nowMs)",
        ["the bar is checked against the raw score, not the ranked one"],
    ),
    (
        "asleep is only ever considered at night",
        "        if (hourOfDay !in NIGHT_HOURS && hourOfDay !in SMALL_HOURS) return null\n",
        "",
        ["the very same evidence at noon is not asleep"],
    ),
    (
        "asleep needs the phone to be untouched",
        "        if (phone.attention() != Attention.AWAY) return null\n",
        "",
        ["a glance at a lit locked screen in the night is not asleep either"],
    ),
    # ⚠️ `driving`'s `if (score == 0) return null` was a case here and has been RETIRED rather than
    # fixed, because it is not independently observable and no honest fixture can make it so: the
    # Bluetooth term is worth 1 against a MIN_SCORE of 4, so a candidate built from corroboration
    # alone is refused by the bar whether or not the line exists. The line stays in the source as
    # belt-and-braces and its KDoc now says exactly that. The output property it protects is still
    # tested — `Bluetooth alone never puts anybody in a car` — it is simply MIN_SCORE that enforces
    # it today. Leaving a case here that can never fail would be a guard nobody can trust.
    (
        "being put away needs something against the front",
        "        if (!env.covered) return null\n",
        "",
        ["an uncovered phone is never reported as put away"],
    ),
    (
        "using the phone is refused while it is covered",
        "        if (env.covered) return null\n",
        "",
        ["a covered phone with the screen on is somebody holding it to their ear"],
    ),
    (
        "a meeting needs the diary to say so",
        "        if (phone.calendarBusy != true) return null",
        "        if (phone.calendarBusy == false) return null",
        ["voices and a silenced phone are not a meeting without the calendar"],
    ),
    (
        "home needs a positive answer about the network",
        "        if (phone.awayFromHome != false) return null",
        "        if (phone.awayFromHome == true) return null",
        ["home requires a positive answer about the network"],
    ),
    (
        "out and about needs a positive answer too",
        "        if (phone.awayFromHome != true) return null",
        "        if (phone.awayFromHome == false) return null",
        ["out and about also requires a positive answer"],
    ),
    (
        "not knowing restarts the clock",
        "        sinceMs = if (prev?.situation == Situation.UNKNOWN && prev.sinceMs > 0L) prev.sinceMs else nowMs,",
        "        sinceMs = prev?.sinceMs ?: nowMs,",
        ["not knowing restarts the clock rather than inheriting it"],
    ),
    (
        "an unchanged situation keeps its start time",
        "            sinceMs = if (prev?.situation == best.situation && prev.sinceMs > 0L) prev.sinceMs else nowMs,",
        "            sinceMs = nowMs,",
        ["an unchanged situation keeps the time it started"],
    ),
    (
        "contenders are only named when they are close",
        "            .filter { bestRank - (it.score + if (it.situation == incumbent) INCUMBENT_BONUS else 0) <= CONTENDER_MARGIN }",
        "            .filter { true }",
        ["walking with it in a pocket reports the pocket"],
    ),
    (
        "out and about is reachable at a base of 3",
        "        if (phone.awayFromHome != true) return null\n        var score = 3",
        "        if (phone.awayFromHome != true) return null\n        var score = 2",
        ["out and about is reachable, and that took a correction"],
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
