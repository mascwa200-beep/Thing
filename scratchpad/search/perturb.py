#!/usr/bin/env python3
"""Negative-test every load-bearing rule in SearchPlan: break it, confirm its own test fails.

⚠️ CRASH-SAFE BY CONSTRUCTION, and that is not caution — it is a bug this harness already caused.
An earlier version was killed by a two-minute timeout *while a perturbation was on disk*. The next
run then read that damaged file as its baseline, restored to it afterwards, and reported several
rules "awake" against source that already carried an injected defect. So:

  - the pristine source is written to a .orig sidecar BEFORE anything is touched, and a run that
    finds a leftover sidecar restores from it and refuses to continue until that is acknowledged;
  - restoration happens in a finally, so an exception cannot leave the tree dirty;
  - and the baseline is RUN and required to be green before the first perturbation, so a harness
    that starts from broken source stops instead of measuring nonsense.

A green test proves nothing until you have watched it fail — and a harness needs the same care as
the thing it checks.
"""
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
SRC = ROOT / "core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/SearchPlan.kt"
TEST = "core/telemetry/src/test/java/dev/mascwa/pulse/core/telemetry/SearchPlanTest.kt"
SIDECAR = SRC.with_suffix(".kt.orig")

# (label, exact source fragment, replacement, the test that MUST fail)
CASES = [
    ("word-boundary matching -> plain contains",
     'private fun containsPhrase(haystack: String, phrase: String): Boolean =\n'
     '    " $haystack ".contains(" $phrase ")',
     'private fun containsPhrase(haystack: String, phrase: String): Boolean =\n'
     '    haystack.contains(phrase)',
     "aMarkerInsideALongerWordDoesNotFire"),

    ("CURRENT checked before PRACTICAL -> reversed",
     'if (CURRENT_MARKERS.any { containsPhrase(q, it) }) return Shape.CURRENT\n'
     '        if (PRACTICAL_MARKERS.any { containsPhrase(q, it) }) return Shape.PRACTICAL',
     'if (PRACTICAL_MARKERS.any { containsPhrase(q, it) }) return Shape.PRACTICAL\n'
     '        if (CURRENT_MARKERS.any { containsPhrase(q, it) }) return Shape.CURRENT',
     "currentBeatsPracticalWhenAQuestionIsBoth"),

    ("apostrophe deleted -> retained",
     "s.lowercase().filter { it != '\\'' && it != '’' }\n"
     "        .map { if (it.isLetterOrDigit()) it else ' ' }",
     "s.lowercase()\n"
     "        .map { if (it.isLetterOrDigit() || it == '\\'' || it == '’') it else ' ' }",
     "apostrophesCollapseOntoTheWrittenForm"),

    ("the gap notice is shape-scoped -> always WEB",
     '    private fun requiredTier(shape: Shape): Tier? = when (shape) {\n'
     '        Shape.CURRENT -> Tier.WEB\n        else -> null\n    }',
     '    private fun requiredTier(shape: Shape): Tier? = Tier.WEB',
     "aPracticalQuestionWithNoWebTierReportsNoGap"),

    ("front-only stripping -> drop scaffold words anywhere",
     '        var cut = 0\n        while (cut < words.size && words[cut] in LEADING_SCAFFOLD) cut++',
     '        words = words.filterNot { it in LEADING_SCAFFOLD }\n        var cut = 0',
     "interiorWordsAndOrderAreKept"),

    ("never strip to nothing -> allow it",
     '        if (cut in 1 until words.size) words = words.drop(cut)',
     '        if (cut >= 1) words = words.drop(cut)',
     "aQueryThatIsAllScaffoldingSurvivesIntact"),

    ("per-tier cap -> uncapped",
     '                if (taken >= perTier || out.size >= limit) break',
     '                if (out.size >= limit) break',
     "aTierIsCappedSoTheOthersStillAppear"),

    ("url dedupe -> none",
     '                if (!seen.add(key)) continue',
     '                seen.add(key)',
     "theSameUrlFromTwoTiersAppearsOnce"),

    ("urlless answers keyed by title -> keyed by tier alone",
     'val key = a.url?.lowercase() ?: "${a.tier}:${a.title.lowercase()}"',
     'val key = a.url?.lowercase() ?: "${a.tier}"',
     "urllessAnswersAreNotTreatedAsDuplicates"),

    ("tier order decides precedence -> a fixed order",
     '        for (tier in order) {',
     '        for (tier in listOf(Tier.WEB, Tier.LIBRARY, Tier.ENCYCLOPAEDIA)) {',
     "theTierOrderDecidesPrecedence"),

    ("provenance distinct per tier -> two the same",
     '    fun provenance(tier: Tier): String = when (tier) {\n'
     '        Tier.LIBRARY -> "From the offline library on this device"',
     '    fun provenance(tier: Tier): String = when (tier) {\n'
     '        Tier.LIBRARY -> "From Wikipedia"',
     "everyTierIntroducesItselfDistinctly"),
]


def run_tests() -> str:
    r = subprocess.run([str(ROOT / "scratchpad/search/run.sh"), TEST],
                       capture_output=True, text=True, cwd=ROOT)
    return r.stdout + r.stderr


def failed_names(out: str) -> set:
    return {l.split("(")[0].split(") ")[-1].strip()
            for l in out.splitlines() if ") " in l and "(dev.mascwa" in l}


def main() -> int:
    if SIDECAR.exists():
        SRC.write_text(SIDECAR.read_text())
        SIDECAR.unlink()
        print("A previous run died with a perturbation on disk. The source has been restored from\n"
              "the sidecar. Re-run to measure — this run is stopping so the recovery is visible.")
        return 2

    orig = SRC.read_text()
    SIDECAR.write_text(orig)
    problems = []
    try:
        baseline = run_tests()
        if "OK (" not in baseline:
            print("BASELINE IS NOT GREEN — refusing to measure anything against it.")
            print(baseline[-2000:])
            return 2
        print("baseline green\n")

        for label, old, new, expect in CASES:
            if old not in orig:
                print(f"!! PERTURBATION DID NOT MATCH THE SOURCE: {label}")
                problems.append(label)
                continue
            SRC.write_text(orig.replace(old, new, 1))
            failed = failed_names(run_tests())
            SRC.write_text(orig)
            if expect in failed:
                extra = sorted(failed - {expect})
                print(f"OK   {label}\n     -> {expect}" + (f"  (also {extra})" if extra else ""))
            else:
                print(f"!! ASLEEP: {label}\n     -> expected {expect}, got {sorted(failed) or 'NOTHING'}")
                problems.append(label)
    finally:
        SRC.write_text(orig)
        SIDECAR.unlink(missing_ok=True)

    print()
    print(f"all {len(CASES)} rules awake" if not problems
          else f"{len(problems)} problem(s): {problems}")
    return 0 if not problems else 1


if __name__ == "__main__":
    sys.exit(main())
