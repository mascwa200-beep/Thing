#!/usr/bin/env python3
"""Re-generate the :desktop module's mirrors of the Android pure cores.

The desktop module deliberately declares NO dependency on :app or :core:* — that is what keeps it
independently buildable without AGP or the Android SDK. The price is that shared pure logic has to be
copied rather than imported, and a copy with nothing watching it can drift.

⚠️ Diffing the pre-existing mirrors is what motivated this, and the result was NOT the scandal a first
skim suggested: `NewsInsights`, `MediaBias` and `SocialBuzz` were identical, and `NewsMarketLink`
differed only in trailing whitespace. `NewsExplainers` differs in real copy — and *correctly*, because
it names social tabs and a cloud analysis engine the desktop does not have. The actual problem was that
none of that was knowable without diffing six files by hand.

So the copy is mechanical and the result is checked. Files that must be identical are listed here;
files that are deliberately adapted are not, and say so in their own headers. Every mirrored file gets a `// MIRROR OF <path>`
banner as its first line, and `MirrorDriftTest` re-derives the comparison the same way this script
writes it: strip the banner, strip `package` lines, strip project-internal imports (those legitimately
differ), and require everything else byte-identical.

Usage:  python3 tools/mirror_desktop_cores.py [--check]

  (no args)  rewrite every mirror from its source
  --check    report which mirrors are stale without writing (what CI's test does, for local use)
"""

from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

CORE = "core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry"
CORE_TEST = "core/telemetry/src/test/java/dev/mascwa/pulse/core/telemetry"
SURVIVAL = "app/src/main/java/dev/mascwa/pulse/data/survival"
DESKTOP = "desktop/src/main/kotlin/dev/mascwa/pulse/desktop"
DESKTOP_TEST = "desktop/src/test/kotlin/dev/mascwa/pulse/desktop"

# source path -> mirror path. Pure logic only: none of these import android.* (verified before adding).
MIRRORS: dict[str, str] = {
    # The study + retrieval stack the desktop now teaches from.
    f"{CORE}/GuideSearch.kt": f"{DESKTOP}/telemetry/GuideSearch.kt",
    f"{CORE}/LibraryConsult.kt": f"{DESKTOP}/telemetry/LibraryConsult.kt",
    f"{CORE}/StudyQuestions.kt": f"{DESKTOP}/telemetry/StudyQuestions.kt",
    f"{CORE}/Recall.kt": f"{DESKTOP}/telemetry/Recall.kt",
    f"{CORE}/Curriculum.kt": f"{DESKTOP}/telemetry/Curriculum.kt",
    f"{CORE}/DailyLesson.kt": f"{DESKTOP}/telemetry/DailyLesson.kt",
    # The Khan-model learning layer: a course seen at once, practice sets, and a hint ladder. Both
    # platforms teach from the same library and must judge and pace it identically.
    f"{CORE}/ContentPack.kt": f"{DESKTOP}/telemetry/ContentPack.kt",
    f"{CORE}/CourseMastery.kt": f"{DESKTOP}/telemetry/CourseMastery.kt",
    f"{CORE}/PracticeSet.kt": f"{DESKTOP}/telemetry/PracticeSet.kt",
    f"{CORE}/Hints.kt": f"{DESKTOP}/telemetry/Hints.kt",
    # Marking an answer objectively, and knowing how the studying is actually going. Mirrored because
    # both platforms teach from the same library and must judge it the same way.
    f"{CORE}/QuizBuilder.kt": f"{DESKTOP}/telemetry/QuizBuilder.kt",
    f"{CORE}/StudyProgress.kt": f"{DESKTOP}/telemetry/StudyProgress.kt",
    f"{CORE}/Refresher.kt": f"{DESKTOP}/telemetry/Refresher.kt",
    f"{CORE}/DeviceSearch.kt": f"{DESKTOP}/telemetry/DeviceSearch.kt",
    f"{CORE}/EmergencyTriage.kt": f"{DESKTOP}/telemetry/EmergencyTriage.kt",
    # How old what is on screen is, and why. ElapsedPhrase comes along because Freshness needs it and it
    # was split out of TemporalReasoner precisely so this could travel without the memory stream.
    f"{CORE}/Freshness.kt": f"{DESKTOP}/telemetry/Freshness.kt",
    f"{CORE}/ElapsedPhrase.kt": f"{DESKTOP}/telemetry/ElapsedPhrase.kt",
    # When a published build is safe to offer. Shared so the phone and the desktop cannot come to
    # disagree about it — the tri-state green gate has an edge that cost real debugging once.
    f"{CORE}/UpdatePolicy.kt": f"{DESKTOP}/telemetry/UpdatePolicy.kt",
    # The News vertical's signal stack, ported before this script existed. Brought under the guard now:
    # these are pure logic and must stay identical. (NewsExplainers.kt and Explainer.kt are deliberately
    # NOT here — they are adapted ports, not mirrors; see their headers.)
    # Live TV news. Both platforms play the same channels and must agree about which are
    # trustworthy, so the catalogue and its judgements are shared rather than duplicated.
    f"{CORE}/LiveChannels.kt": f"{DESKTOP}/telemetry/LiveChannels.kt",
    f"{CORE}/NewsInsights.kt": f"{DESKTOP}/telemetry/NewsInsights.kt",
    f"{CORE}/NewsMarketLink.kt": f"{DESKTOP}/telemetry/NewsMarketLink.kt",
    f"{CORE}/MediaBias.kt": f"{DESKTOP}/telemetry/MediaBias.kt",
    f"{CORE}/SocialBuzz.kt": f"{DESKTOP}/telemetry/SocialBuzz.kt",
    # The library's own models and taxonomy — app-side content, but pure data.
    f"{SURVIVAL}/GuideModels.kt": f"{DESKTOP}/library/GuideModels.kt",
    f"{SURVIVAL}/GuideTaxonomy.kt": f"{DESKTOP}/library/GuideTaxonomy.kt",
    # The tests come across too, so the same assertions gate BOTH platforms' CI. Mirroring logic
    # without mirroring its tests would leave the desktop copy unexercised — which is the state the
    # drift this script exists to prevent grew in.
    f"{CORE_TEST}/LiveChannelsTest.kt": f"{DESKTOP_TEST}/telemetry/LiveChannelsTest.kt",
    f"{CORE_TEST}/GuideSearchTest.kt": f"{DESKTOP_TEST}/telemetry/GuideSearchTest.kt",
    f"{CORE_TEST}/LibraryConsultTest.kt": f"{DESKTOP_TEST}/telemetry/LibraryConsultTest.kt",
    f"{CORE_TEST}/StudyQuestionsTest.kt": f"{DESKTOP_TEST}/telemetry/StudyQuestionsTest.kt",
    f"{CORE_TEST}/RecallTest.kt": f"{DESKTOP_TEST}/telemetry/RecallTest.kt",
    f"{CORE_TEST}/CurriculumTest.kt": f"{DESKTOP_TEST}/telemetry/CurriculumTest.kt",
    f"{CORE_TEST}/DailyLessonTest.kt": f"{DESKTOP_TEST}/telemetry/DailyLessonTest.kt",
    f"{CORE_TEST}/ContentPackTest.kt": f"{DESKTOP_TEST}/telemetry/ContentPackTest.kt",
    f"{CORE_TEST}/CourseMasteryTest.kt": f"{DESKTOP_TEST}/telemetry/CourseMasteryTest.kt",
    f"{CORE_TEST}/PracticeSetTest.kt": f"{DESKTOP_TEST}/telemetry/PracticeSetTest.kt",
    f"{CORE_TEST}/HintsTest.kt": f"{DESKTOP_TEST}/telemetry/HintsTest.kt",
    f"{CORE_TEST}/QuizBuilderTest.kt": f"{DESKTOP_TEST}/telemetry/QuizBuilderTest.kt",
    f"{CORE_TEST}/ProcedureQuestionTest.kt": f"{DESKTOP_TEST}/telemetry/ProcedureQuestionTest.kt",
    f"{CORE_TEST}/StudyProgressTest.kt": f"{DESKTOP_TEST}/telemetry/StudyProgressTest.kt",
    f"{CORE_TEST}/RefresherTest.kt": f"{DESKTOP_TEST}/telemetry/RefresherTest.kt",
    f"{CORE_TEST}/DeviceSearchTest.kt": f"{DESKTOP_TEST}/telemetry/DeviceSearchTest.kt",
    f"{CORE_TEST}/EmergencyTriageTest.kt": f"{DESKTOP_TEST}/telemetry/EmergencyTriageTest.kt",
    f"{CORE_TEST}/FreshnessTest.kt": f"{DESKTOP_TEST}/telemetry/FreshnessTest.kt",
    f"{CORE_TEST}/UpdatePolicyTest.kt": f"{DESKTOP_TEST}/telemetry/UpdatePolicyTest.kt",
}

# Package renames applied to the mirror. Order matters only in that each source package maps to exactly
# one desktop package.
PACKAGES = {
    "dev.mascwa.pulse.core.telemetry": "dev.mascwa.pulse.desktop.telemetry",
    "dev.mascwa.pulse.data.survival": "dev.mascwa.pulse.desktop.library",
}

BANNER = "// MIRROR OF {src} — regenerate with tools/mirror_desktop_cores.py; MirrorDriftTest holds it"


def render(src_rel: str, text: str) -> str:
    """The mirror of a source file: banner, then the source with its own packages renamed."""
    out = text
    for frm, to in PACKAGES.items():
        out = out.replace(f"package {frm}", f"package {to}")
        out = out.replace(f"import {frm}.", f"import {to}.")
    return BANNER.format(src=src_rel) + "\n" + out


def comparable(text: str) -> list[str]:
    """What must match between a source and its mirror.

    Everything except the banner, the package line and project-internal imports — those three are the
    only things a mirror is allowed to differ in, and each of them differs for a mechanical reason.
    """
    kept = []
    for line in text.splitlines():
        s = line.strip()
        if s.startswith("// MIRROR OF "):
            continue
        if s.startswith("package "):
            continue
        if s.startswith("import dev.mascwa.pulse."):
            continue
        kept.append(line)
    return kept


def main() -> int:
    check = "--check" in sys.argv[1:]
    stale = []
    for src_rel, dst_rel in MIRRORS.items():
        src, dst = ROOT / src_rel, ROOT / dst_rel
        if not src.exists():
            print(f"MISSING SOURCE: {src_rel}")
            return 2
        want = render(src_rel, src.read_text(encoding="utf-8"))
        if check:
            if not dst.exists():
                stale.append(f"{dst_rel} (absent)")
            elif comparable(dst.read_text(encoding="utf-8")) != comparable(want):
                stale.append(dst_rel)
        else:
            dst.parent.mkdir(parents=True, exist_ok=True)
            dst.write_text(want, encoding="utf-8")
            print(f"wrote {dst_rel}")

    if check:
        if stale:
            print("STALE MIRRORS:\n  " + "\n  ".join(stale))
            return 1
        print(f"all {len(MIRRORS)} mirrors current")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
