#!/usr/bin/env python3
"""Negative-test the load-bearing rules in AmbientRules.kt.

Same shape as negsit.py beside it, and for the same recorded reasons: one case per invocation so no
run can outlive the tool timeout and skip its restore, every perturbation asserted to have MATCHED
the source before anything is written, and the wrapper asserting the baseline is green first.
"""
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/AmbientRules.kt"
TEST = "core/telemetry/src/test/java/dev/mascwa/pulse/core/telemetry/AmbientRulesTest.kt"

CASES = [
    (
        "nothing acts on a situation that has only just appeared",
        "        return s.nowMs - r.sinceMs >= SETTLE_MS",
        "        return true",
        ["a situation nobody has settled into is not acted on"],
    ),
    (
        "a thinly-evidenced situation is not acted on",
        "        if (r.confidence < MIN_CONFIDENCE) return false\n",
        "",
        ["a situation with one signal behind it is not acted on"],
    ),
    (
        "a situation that has never started is not acted on",
        "        if (r.sinceMs <= 0L) return false\n",
        "",
        ["a situation with no start time is not acted on"],
    ),
    (
        "not knowing is never acted on",
        "        if (r.situation == Situation.UNKNOWN) return false\n",
        "",
        ["not knowing is never acted on"],
    ),
    (
        "safety is OUTSIDE the settle gate",
        "        safety(s) + if (!settled(s)) emptyList() else when (s.situation.situation) {",
        "        if (!settled(s)) emptyList() else safety(s) + when (s.situation.situation) {",
        ["an alarm is acted on immediately, with nothing else known"],
    ),
    (
        "only an ALERT reaches the safety rule",
        "            it.severity == EventSeverity.ALERT && it.key == SensoriumEvents.KEY_SMOKE_ALARM",
        "            true",
        ["a merely notable sound is not an alarm"],
    ),
    (
        "only a FIRE alarm gets the loud, bright answer",
        "            it.severity == EventSeverity.ALERT && it.key == SensoriumEvents.KEY_SMOKE_ALARM",
        "            it.severity == EventSeverity.ALERT",
        ["breaking glass is never answered by making the phone loud and bright"],
    ),
    (
        "the torch is gated on darkness",
        "        if (s.env.light == LightState.DARK) {",
        "        if (true) {",
        ["the torch comes on for an alarm in the dark and not otherwise"],
    ),
    (
        "an alarm is announced as RED and not as routine",
        "            intent(s, AmbientAction.RAISE_ALARM_VOLUME, why, ActionUrgency.RED),",
        "            intent(s, AmbientAction.RAISE_ALARM_VOLUME, why),",
        ["an alarm is acted on immediately, with nothing else known"],
    ),
    (
        "a tier above the switched-on one is refused",
        "            if (i.action.tier.ordinal <= maxTier.ordinal) allowed += i",
        "            if (true) allowed += i",
        [
            "a phone-tier hold is refused, with the reason, while that tier is off",
            "turning the phone tier on does not turn the owner tier on",
        ],
    ),
    (
        "the expiry is the action's own ceiling",
        "    val untilMs: Long get() = fromMs + action.maxHoldMs",
        "    val untilMs: Long get() = fromMs + 365L * 24 * 60 * 60_000L",
        ["the expiry is derived from the action and cannot be asked for"],
    ),
    (
        "driving does not reach for the audio",
        "        intent(s, AmbientAction.PAUSE_VIDEO, \"you are driving\"),",
        "        intent(s, AmbientAction.PAUSE_VIDEO, \"you are driving\"),\n        intent(s, AmbientAction.SILENCE_RINGER, \"you are driving\"),",
        ["driving quietens the phone and leaves the audio alone"],
    ),
    (
        "a meeting does not take over Do Not Disturb",
        "        add(intent(s, AmbientAction.STAY_SILENT, \"your calendar says you are in something\"))",
        "        add(intent(s, AmbientAction.STAY_SILENT, \"your calendar says you are in something\"))\n        add(intent(s, AmbientAction.REQUEST_DND, \"your calendar says you are in something\"))",
        ["a meeting silences the ringer and does not take over Do Not Disturb"],
    ),
    # ⚠️ The transition-only rule, made structural. A hold is asserted and then RELEASED, so
    # silencing a ringer that was already silent means the release turns it back ON.
    (
        "the ringer is only silenced when it is measurably on",
        "        if (s.phone.ringer == RingerState.NORMAL) {",
        "        if (true) {",
        ["a ringer that is already silent is left alone"],
    ),
    (
        "company earns silence and nothing more",
        "        intent(s, AmbientAction.STAY_SILENT, \"there are people around you\"),",
        "        intent(s, AmbientAction.STAY_SILENT, \"there are people around you\"),\n        intent(s, AmbientAction.HOLD_NON_URGENT, \"there are people around you\"),",
        ["being among people earns silence and nothing more"],
    ),
    (
        "a pocket keeps its ears",
        "        intent(s, AmbientAction.LOWER_SENSE_RATE, \"the phone is put away\"),",
        "        intent(s, AmbientAction.STOP_CAMERA_SIPS, \"the phone is put away\"),",
        ["being put away stops the camera rather than slowing it"],
    ),
    (
        "asleep slows the sensing rather than stopping it",
        "        intent(s, AmbientAction.LOWER_SENSE_RATE, \"you appear to be asleep\"),\n",
        "",
        ["asleep spends less without ever switching the ears off"],
    ),
    # ⚠️ Like the destructive-name case below, this one plants a plausible-looking ENUM member rather
    # than perturbing a rule, because what it guards is the shape of the capability list. The point
    # of removing STAND_SENSING_DOWN was that no rule can reach for something that does not exist —
    # so the only way to test that is to put it back.
    (
        "nothing in the list can switch the ears off",
        "    STAY_SILENT(ActionTier.APP, \"stopped speaking aloud\", \"speak up again\", 4 * HOUR),",
        "    STAY_SILENT(ActionTier.APP, \"stopped speaking aloud\", \"speak up again\", 4 * HOUR),\n    STAND_SENSING_DOWN(ActionTier.APP, \"stood the sensors down\", \"wake the sensors\", 12 * HOUR),",
        ["nothing in the list can switch the ears off"],
    ),
    # ⚠️ The destructive-name guard is the one rule here whose perturbation has to go in the ENUM
    # rather than in a rule, because what it protects is the shape of the capability list and not any
    # decision made from it. Adding a plausible-looking member is exactly the change it exists to
    # catch, so that is what gets planted.
    (
        "the closed list cannot express anything destructive",
        "    SHORTEN_SCREEN_TIMEOUT(\n        ActionTier.OWNER, \"shortened the screen timeout\", \"put the timeout back\", 8 * HOUR,\n    ),",
        "    SHORTEN_SCREEN_TIMEOUT(\n        ActionTier.OWNER, \"shortened the screen timeout\", \"put the timeout back\", 8 * HOUR,\n    ),\n    FACTORY_RESET(ActionTier.OWNER, \"reset the device\", \"nothing — this cannot be undone\", 1 * HOUR),",
        ["the closed list cannot express anything destructive"],
    ),
    (
        "every hold expires",
        "    TORCH_ON(ActionTier.PHONE, \"turned the torch on\", \"turn the torch off\", 10 * MINUTE),",
        "    TORCH_ON(ActionTier.PHONE, \"turned the torch on\", \"turn the torch off\", 0L),",
        ["every hold expires, and none of them expires so late it stops being a backstop"],
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
