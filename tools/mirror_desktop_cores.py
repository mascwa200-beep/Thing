#!/usr/bin/env python3
"""Re-generate the :desktop module's mirrors of the Android pure cores.

The desktop module deliberately declares NO dependency on :app or :core:* — that is what keeps it
independently buildable without AGP or the Android SDK. The price is that shared pure logic has to be
copied rather than imported, and a copy with nothing watching it drifts. It already has: before this
script existed, `NewsMarketLink` and `NewsExplainers` had diverged from their originals in real code,
not just comments, and nothing anywhere would have said so.

So the copy is mechanical and the result is checked. Every mirrored file gets a `// MIRROR OF <path>`
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
SURVIVAL = "app/src/main/java/dev/mascwa/pulse/data/survival"
DESKTOP = "desktop/src/main/kotlin/dev/mascwa/pulse/desktop"

# source path -> mirror path. Pure logic only: none of these import android.* (verified before adding).
MIRRORS: dict[str, str] = {
    # The study + retrieval stack the desktop now teaches from.
    f"{CORE}/GuideSearch.kt": f"{DESKTOP}/telemetry/GuideSearch.kt",
    f"{CORE}/LibraryConsult.kt": f"{DESKTOP}/telemetry/LibraryConsult.kt",
    f"{CORE}/StudyQuestions.kt": f"{DESKTOP}/telemetry/StudyQuestions.kt",
    f"{CORE}/Recall.kt": f"{DESKTOP}/telemetry/Recall.kt",
    f"{CORE}/Curriculum.kt": f"{DESKTOP}/telemetry/Curriculum.kt",
    f"{CORE}/DailyLesson.kt": f"{DESKTOP}/telemetry/DailyLesson.kt",
    f"{CORE}/DeviceSearch.kt": f"{DESKTOP}/telemetry/DeviceSearch.kt",
    f"{CORE}/EmergencyTriage.kt": f"{DESKTOP}/telemetry/EmergencyTriage.kt",
    # The library's own models and taxonomy — app-side content, but pure data.
    f"{SURVIVAL}/GuideModels.kt": f"{DESKTOP}/library/GuideModels.kt",
    f"{SURVIVAL}/GuideTaxonomy.kt": f"{DESKTOP}/library/GuideTaxonomy.kt",
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
