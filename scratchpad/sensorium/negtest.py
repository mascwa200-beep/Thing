#!/usr/bin/env python3
"""Negative-test one S0 core rule: break it, prove the guard that names it fails.

    ./neg.sh <case>        one case per invocation
    ./neg.sh --list

A green test proves nothing until it has been watched to fail. The six recorded ways it can be green
for the wrong reason all apply here, so this asserts every one it can:

  · the perturbation MATCHED the source (an edit that silently applied to nothing reports a guard
    awake that was never exercised);
  · the baseline is GREEN before anything is touched (a suite already failing makes every case look
    awake — and it was a real perturbation-harness bug in this repo, not a hypothetical);
  · the expected tests actually FAILED, by name, and nothing else did — a build that broke for an
    unrelated reason is not evidence about this rule.

⚠️ Restoring belongs in the SHELL (`trap ... EXIT` in neg.sh), not here: a run killed by the tool's
two-minute timeout never reaches a `finally`, and this repository has already left a perturbation in
the tree that way.
"""
import subprocess
import sys
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[2]
CORE = ROOT / "core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/Sensorium.kt"
BASE = ROOT / "core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/SensoriumBaseline.kt"
T_CORE = "core/telemetry/src/test/java/dev/mascwa/pulse/core/telemetry/SensoriumTest.kt"
T_BASE = "core/telemetry/src/test/java/dev/mascwa/pulse/core/telemetry/SensoriumBaselineTest.kt"

# (name, file, find, replace, tests-that-must-fail)
CASES = [
    (
        "covered-suppresses-the-light-claim", CORE,
        "            covered -> LightState.UNKNOWN\n", "",
        ["aCoveredSensorReportsNoBrightnessRatherThanADarkRoom",
         "theSpokenLineSaysCoveredAndDropsTheBrightnessWord"],
    ),
    (
        "covered-guards-the-outdoor-lux-branch", CORE,
        "            !covered && f.lightLux != null && f.lightLux >= LUX_BRIGHT -> EnvSetting.OUTDOOR",
        "            f.lightLux != null && f.lightLux >= LUX_BRIGHT -> EnvSetting.OUTDOOR",
        ["aCoveredPhoneCannotBeReadAsOutdoorsFromItsLightSensor"],
    ),
    (
        "the-wifi-prior-is-a-last-resort", CORE,
        "        val setting = when {\n            motion == MotionState.DRIVING -> EnvSetting.VEHICLE",
        "        val setting = when {\n"
        "            f.wifiApCount != null && f.wifiApCount >= WIFI_DENSE -> EnvSetting.INDOOR\n"
        "            motion == MotionState.DRIVING -> EnvSetting.VEHICLE",
        ["realEvidenceAlwaysBeatsTheAccessPointCount"],
    ),
    (
        "a-level-may-raise-but-never-lower", CORE,
        "            else -> maxOf(byKeyword, byLevel)", "            else -> byLevel",
        ["aMeasuredLevelNeverQuietensAReadingTheClassifierNamed"],
    ),
    (
        "nothing-recognised-lets-the-level-decide", CORE,
        "            sound.isEmpty() -> byLevel\n", "",
        ["aSilentRoomReadsSilentRatherThanMerelyQuiet"],
    ),
    (
        "an-absent-level-changes-nothing", CORE,
        "            byLevel == null -> byKeyword", "            byLevel == null -> NoiseProfile.LOUD",
        ["anAbsentLevelLeavesEveryReadingExactlyWhereItWas"],
    ),
    (
        "a-level-only-sip-counts-as-heard", CORE,
        "heard = f.soundLabels.isNotEmpty() || f.soundDbfs != null,", "heard = f.soundLabels.isNotEmpty(),",
        ["aSipThatOnlyMeasuredALevelStillCountsAsHavingHeard"],
    ),
    (
        # ⚠️ This expectation originally also named `anUncountedCrowdIsNotLearnedAsZero` and
        # `aNormalLineOmitsEverySenseItNeverMeasured`, and the harness duly reported the guard
        # ASLEEP. The guard was fine; the EXPECTATION was wrong. Both of those tests build an
        # `EnvMetrics` directly and never call `of()`, so a change to `of()` cannot reach them —
        # the "fixture never reached the branch" mechanism, applied to my own expectation list
        # rather than to a test. Exactly one test exercises `of()`, and it is the one named here.
        "an-uncounted-crowd-reaches-the-metric-as-absence", BASE,
        "            crowd = frame.btDeviceCount?.toFloat(),", "            crowd = (frame.btDeviceCount ?: 0).toFloat(),",
        ["anAbsentDeviceCountReachesTheMetricAsAbsence"],
    ),
    (
        "the-normal-line-omits-an-unlearned-crowd", BASE,
        "        if (c.crowdSamples >= MIN_SAMPLES) {\n            parts += when {",
        "        if (true) {\n            parts += when {",
        ["aNormalLineOmitsEverySenseItNeverMeasured"],
    ),
    (
        "the-normal-line-omits-an-unlearned-light", BASE,
        "        if (c.lightSamples >= MIN_SAMPLES) {\n            // The metric is log10",
        "        if (true) {\n            // The metric is log10",
        ["aNormalLineOmitsEverySenseItNeverMeasured"],
    ),
    (
        "a-sense-is-judged-only-against-its-own-history", BASE,
        "        if (c.crowdSamples >= MIN_SAMPLES) {\n            m.crowd?.let { judge(\"crowd\"",
        "        if (true) {\n            m.crowd?.let { judge(\"crowd\"",
        ["aSenseIsNotJudgedUntilItHasBeenLearnedFromEnoughOfItsOwnReadings"],
    ),
    (
        "a-late-sense-seeds-on-its-own-count", BASE,
        "ewma(c.lightSamples, c.lightMean, m.light)", "ewma(c.samples, c.lightMean, m.light)",
        ["aLateArrivingSenseTakesItsFirstReadingWhole"],
    ),
    (
        # The other half of the crowd fix, and the one case 7's perturbation could not reach: the
        # learned mean itself must be left alone when nothing counted, not folded a zero.
        "an-uncounted-crowd-is-not-folded-into-the-learned-mean", BASE,
        "            crowdMean = if (m.crowd == null) c.crowdMean else ewma(c.crowdSamples, c.crowdMean, m.crowd),",
        "            crowdMean = ewma(c.crowdSamples, c.crowdMean, m.crowd ?: 0f),",
        ["anUncountedCrowdIsNotLearnedAsZero"],
    ),
]


def run() -> tuple[bool, set[str]]:
    """Run both suites. Returns (compiled, failing-test-names)."""
    p = subprocess.run(
        [str(ROOT / "scratchpad/coretest/run.sh"), T_CORE, T_BASE],
        cwd=ROOT, capture_output=True, text=True,
    )
    out = p.stdout + p.stderr
    if "NOTHING COMPILED" in out:
        return False, set()
    # JUnit's text runner prints "N) testName(Class)" for each failure.
    return True, set(re.findall(r"^\d+\) (\w+)\(", out, re.M))


def main() -> int:
    if len(sys.argv) > 1 and sys.argv[1] == "--list":
        for i, c in enumerate(CASES):
            print(f"{i:2d}  {c[0]}")
        return 0
    idx = int(sys.argv[1])
    name, path, find, repl, expect = CASES[idx]

    compiled, failing = run()
    if not compiled or failing:
        print(f"BASELINE IS NOT GREEN (compiled={compiled}, failing={sorted(failing)}) — nothing proved")
        return 2
    print(f"baseline green · case {idx}: {name}")

    src = path.read_text()
    n = src.count(find)
    if n != 1:
        print(f"PERTURBATION MATCHED {n} TIMES, not once — it would have proved nothing")
        return 2
    path.write_text(src.replace(find, repl))

    compiled, failing = run()
    if not compiled:
        print("the perturbed tree did not compile — that is not evidence about this rule")
        return 2
    missing = [t for t in expect if t not in failing]
    extra = sorted(failing - set(expect))
    if missing:
        print(f"GUARD ASLEEP — expected to fail but did not: {missing}")
        if extra:
            print(f"  (other failures: {extra})")
        return 1
    print(f"AWAKE — failed exactly {sorted(failing)}")
    if extra:
        print(f"  ⚠️ also failed: {extra} — check whether that is a second guard or an over-broad edit")
    return 0


if __name__ == "__main__":
    sys.exit(main())
