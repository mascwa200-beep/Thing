#!/usr/bin/env python3
"""The local twin of the bundled-diagram build gates, plus the attribution check they do not make.

Three assertions have been run by hand after every image bank this session, and one of them was
run WRONG once: a scan that reported 300 unattributed images had skipped every file named
`NOTICE.txt` and never opened `images/kb/NOTICE.txt` at all, where 300 of the 343 entries live.
The real answer was zero. A check that is retyped each time is a check that is eventually mistyped,
and this one produced a false alarm that came within a sentence of being reported to the owner.

What it mirrors, and from where:

  * **orphans** — `BundledImagesTest.nothingShipsThatNoGuidePointsAt`. Files bundled into the APK
    and the desktop jar that no guide references: downloaded, stored, shown to nobody. The residue
    of an image wave stopped part-way; 59 of them shipped once with every check green.
  * **dangling** — `GuidesJsonValidationTest`. A guide pointing at a file that is not there. Worse
    than an orphan, because the reader draws nothing on a page somebody opened.
  * **raster shape** — `BundledImagesTest.everyRasterIsARealImageNoWiderThanTheReadersDrawIt`. A
    saved error page under a `.webp` name is the ordinary way image sourcing fails silently.
  * **vector shape** — `everyVectorIsRealSvgAndNotPathologicallyLarge`.
  * **attribution** — nothing in the build asserts this, and it is a licensing obligation rather
    than a rendering one: every bundled file must appear in one of the two NOTICE files.

⚠️ **The size limits are read out of the Kotlin gate, never restated here.** Two numbers meaning
one thing drift, and the drift shows up as a wave writing hundreds of diagrams that all fail CI —
which is why `source_images.py` already cross-checks the same two constants at startup.

Run before every bank. Exit code is the answer; the output names what is wrong.
"""
from __future__ import annotations

import json
import os
import re
import sys

SVG_ELEMENT_RE = re.compile(r"<(?:path|circle|rect|line|polyline|polygon|ellipse|text|image|use)\b", re.I)




HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))

# ⚠️ `--root` points the corpus checks at a copy, and exists so this script can be negative-tested.
# A gate is only known to work once it has been watched failing, and the only way to watch these
# five fail is to break a corpus deliberately — which must never mean breaking the real one. Making
# a hardlinked copy (`cp -al`) costs no disk and gives somewhere safe to plant each defect. The
# size limits still come from the real gate: they are the thing under test, never part of the copy.
_root = ROOT
for _i, _a in enumerate(sys.argv):
    if _a == "--root" and _i + 1 < len(sys.argv):
        _root = os.path.abspath(sys.argv[_i + 1])
ASSETS = os.path.join(_root, "app", "src", "main", "assets", "survival")
IMAGES = os.path.join(ASSETS, "images")
GATE_TEST = os.path.join(
    ROOT, "app", "src", "test", "java", "dev", "mascwa", "pulse",
    "data", "survival", "BundledImagesTest.kt",
)


def vector_floor() -> tuple[int, int]:
    """`MIN_SVG_ELEMENTS` and `MIN_SVG_BYTES`, read out of the sourcer that enforces them.

    ⚠️ Read, never restated — the same rule this file already follows for the Kotlin size limits,
    and for the same reason: two numbers meaning one thing drift, and here the drift would be
    silent in the worse direction. A gate with a lower floor than the sourcer accepts every
    fragment the sourcer would have refused, which is precisely the hole being closed.
    """
    src = open(os.path.join(HERE, "source_images.py"), encoding="utf-8").read()
    out = []
    for name in ("MIN_SVG_ELEMENTS", "MIN_SVG_BYTES"):
        m = re.search(rf"^{name}\s*=\s*([\d_]+)", src, re.M)
        if not m:
            raise SystemExit(f"cannot read {name} out of source_images.py")
        out.append(int(m.group(1).replace("_", "")))
    return out[0], out[1]


def limits() -> tuple[int, int]:
    """`MAX_DISPLAY_PX` and `MAX_SVG_BYTES`, taken from the gate itself so they cannot drift."""
    src = open(GATE_TEST, encoding="utf-8").read()
    out = []
    for name in ("MAX_DISPLAY_PX", "MAX_SVG_BYTES"):
        m = re.search(rf"\b{name}\s*=\s*([\d_]+)", src)
        if not m:
            raise SystemExit(f"cannot read {name} out of {os.path.basename(GATE_TEST)}")
        out.append(int(m.group(1).replace("_", "")))
    return out[0], out[1]


def referenced() -> dict[str, list[str]]:
    """image path -> the guides pointing at it, exactly as the readers resolve it."""
    out: dict[str, list[str]] = {}
    for f in sorted(os.listdir(ASSETS)):
        if not (f.startswith("guides") and f.endswith(".json")):
            continue
        doc = json.load(open(os.path.join(ASSETS, f), encoding="utf-8"))
        items = doc if isinstance(doc, list) else doc.get("guides", [])
        for g in items:
            for s in g.get("sections", []):
                if s.get("image"):
                    out.setdefault(s["image"], []).append(g["id"])
    return out


def on_disk() -> set[str]:
    """Every image file under images/, relative to it. ⚠️ NOTICE.txt is documentation, not a diagram."""
    out = set()
    for root, _dirs, files in os.walk(IMAGES):
        for name in files:
            if name.endswith(".txt"):
                continue
            out.add(os.path.relpath(os.path.join(root, name), IMAGES).replace(os.sep, "/"))
    return out


def webp_width(b: bytes) -> int | None:
    """Canvas width from a WebP container header, or None if this is not one.

    ⚠️ All three chunk layouts, because only VP8L is common in this corpus and an unexercised
    branch is an unchecked one. Validated against Pillow over the whole corpus when it was written.
    """
    if len(b) < 30 or b[:4] != b"RIFF" or b[8:12] != b"WEBP":
        return None
    fourcc = b[12:16]
    if fourcc == b"VP8 ":
        return int.from_bytes(b[26:28], "little") & 0x3FFF
    if fourcc == b"VP8L":
        bits = int.from_bytes(b[21:25], "little")
        return (bits & 0x3FFF) + 1
    if fourcc == b"VP8X":
        return int.from_bytes(b[24:27], "little") + 1
    return None


def notices() -> str:
    """⚠️ BOTH notice files. Reading only images/NOTICE.txt is the mistake this script exists for."""
    text = ""
    for p in (os.path.join(IMAGES, "NOTICE.txt"), os.path.join(IMAGES, "kb", "NOTICE.txt")):
        if os.path.exists(p):
            text += open(p, encoding="utf-8").read() + "\n"
    return text


def main() -> int:
    max_px, max_svg = limits()
    min_els, min_bytes = vector_floor()
    ref, disk = referenced(), on_disk()
    notice = notices()
    fail = 0

    orphans = sorted(disk - set(ref))
    if orphans:
        fail = 1
        print(f"FAIL {len(orphans)} orphans — bundled, referenced by nothing:")
        for o in orphans[:10]:
            print("   ", o)

    dangling = sorted(set(ref) - disk)
    if dangling:
        fail = 1
        print(f"FAIL {len(dangling)} dangling — a guide points at a file that is not there:")
        for d in dangling[:10]:
            print(f"    {d}  <- {', '.join(ref[d])}")

    shape: list[str] = []
    for rel in sorted(ref):
        p = os.path.join(IMAGES, rel)
        if not os.path.exists(p):
            continue                              # already reported as dangling
        b = open(p, "rb").read()
        if rel.lower().endswith(".svg"):
            head = b[:2048].decode("utf-8", "replace").lower()
            if "<svg" not in head:
                shape.append(f"{rel}: no <svg> root in the first 2 kB")
            elif len(b) > max_svg:
                shape.append(f"{rel}: {len(b) // 1024} kB > {max_svg // 1024} kB")
            else:
                # ⚠️ The vector check had a ceiling and no floor, so graphical FRAGMENTS counted as
                # diagrams: a 9x9 canvas holding one diagonal line — a shogi board-tile piece —
                # shipped as the sole illustration of a whole guide, and two more like it. Rasters
                # were always floored by width; vectors were not. Poor by BOTH measures is the test,
                # because element count alone throws out a real 7 kB diagram drawn as four complex
                # paths, and byte count alone throws out a lean but complete one. See
                # source_images.pixels_ok for the measurement over all 93 bundled vectors.
                drawn = len(SVG_ELEMENT_RE.findall(b.decode("utf-8", "replace")))
                if drawn < min_els and len(b) < min_bytes:
                    shape.append(f"{rel}: too sparse to be a diagram — {drawn} element(s), {len(b)} B")
        else:
            w = webp_width(b)
            if w is None:
                shape.append(f"{rel}: not a WebP image ({len(b)} bytes, starts {b[:8].hex()})")
            elif w <= 0 or w > max_px:
                shape.append(f"{rel}: {w}px wide")
    if shape:
        fail = 1
        print(f"FAIL {len(shape)} files are not the shape the readers can draw:")
        for s in shape[:10]:
            print("   ", s)

    # Attribution: the bundled name appears somewhere in a NOTICE. Deliberately a mention test
    # rather than a parse — the four historical notice formats are all still in the file, and a
    # parser that understands three of them would report the fourth as missing provenance.
    unattributed = sorted(r for r in ref if os.path.basename(r) not in notice and r not in notice)
    if unattributed:
        fail = 1
        print(f"FAIL {len(unattributed)} bundled images appear in neither NOTICE file:")
        for u in unattributed[:10]:
            print("   ", u)

    print(
        f"{'FAILED' if fail else 'ok'} — {len(ref)} referenced, {len(disk)} on disk, "
        f"limits {max_px}px / {max_svg // 1024} kB (read from the gate)"
    )
    return fail


if __name__ == "__main__":
    raise SystemExit(main())
