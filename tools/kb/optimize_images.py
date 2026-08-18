#!/usr/bin/env python3
"""Re-encode the bundled guide diagrams to the size the reader actually draws them at.

⚠️ **Why this exists, and the mistake it corrects.** An earlier efficiency pass measured the image
corpus by listing `assets/survival/images/` and concluded "5.5 MB across 44 files — already
phone-appropriate, nothing to do here". That listing missed `images/kb/`, which holds 300 of the 343
files. The real corpus is **68 MB**, by a wide margin the largest thing in the APK's assets (the
entire 651-guide text corpus is 25.6 MB). The `du -sh` figure of 69 MB, dismissed at the time as
block-allocation overhead, had been right all along.

**What the readers actually display**, which is what sets the ceiling:

    Android  GuidesScreen.SurvivalDiagram   widthIn(max = 260.dp)  ->  780 px at density 3
    Desktop  Diagram                        widthIn(max = 620.dp)  -> 1240 px at density 2

The corpus contains 123 files wider than 1200 px and tops out at 1920. Every pixel above the cap is
decoded and then thrown away at draw time, on every open, which costs decode work and heap as well
as download size.

**The settings, and why these and not others.** Measured over the whole corpus, comparing each
original against its re-encode *after both are scaled to the 780 px the phone shows* — because that
is the only comparison that answers "does this change how it looks":

    cap 1280 q85   65.7 MB -> 27.0 MB  (-59%)   RMSE  median 2.0   p90  5.5   worst  6.3
    cap 1080 q82   65.7 MB -> 21.8 MB  (-67%)   RMSE  median 2.5   p90 12.3   worst 14.1

On 0-255 channels an RMSE around 2 is imperceptible and around 6 is very subtle; 14 shows on flat
areas. This library is line art, scanned engravings and clinical diagrams, where a soft edge is a
loss of information rather than of polish — so it takes the conservative pair, and 1280 also stays
comfortably above the 1240 px the desktop can ask for.

**SVGs are left alone**: 15 files, 2.4 MB, vector, already ideal at any size, and re-encoding them
to raster would be a real quality loss rather than a saving.

**The GIF is converted, which fixes a bug.** `desktop/feature/library/Diagram.kt` documents that
Compose Desktop has no loader for it, so that one diagram has always failed soft to its caption on
Windows. As WebP it draws.

Re-running this is safe: files already in WebP and within the cap are left untouched.

    python3 tools/kb/optimize_images.py [--check] [--cap 1280] [--quality 85]

`--check` reports what would change and writes nothing.
"""
from __future__ import annotations

import argparse
import io
import json
import os
import sys
from pathlib import Path

try:
    from PIL import Image
except ImportError:  # pragma: no cover - operator convenience
    sys.exit("Pillow is required:  pip install Pillow")

REPO = Path(__file__).resolve().parents[2]
ASSETS = REPO / "app/src/main/assets/survival"
IMAGES = ASSETS / "images"

# Vector stays vector. NOTICE is prose.
KEEP = {".svg"}
SKIP_NAMES = {"NOTICE.txt"}


def shard_files() -> list[Path]:
    return sorted(ASSETS.glob("guides*.json"))


def rewrite_reference(name: str) -> str:
    """`kb/foo.png` -> `kb/foo.webp`, leaving svg and already-webp alone."""
    stem, ext = os.path.splitext(name)
    return name if ext.lower() in KEEP or ext.lower() == ".webp" else stem + ".webp"


def convert(path: Path, cap: int, quality: int) -> bytes:
    with Image.open(path) as im:
        im.load()
        w, h = im.size
        if w > cap:
            im = im.resize((cap, max(1, round(h * cap / w))), Image.LANCZOS)
        # ⚠️ Flatten onto white rather than keeping alpha. These are drawn on a light card
        # (both readers set a cream background behind the diagram), so a transparent PNG already
        # renders against white; preserving the alpha channel would cost bytes for an effect
        # nothing can see.
        if im.mode in ("RGBA", "LA", "P"):
            im = im.convert("RGBA")
            flat = Image.new("RGB", im.size, (255, 255, 255))
            flat.paste(im, mask=im.split()[-1])
            im = flat
        else:
            im = im.convert("RGB")
        buf = io.BytesIO()
        im.save(buf, "WEBP", quality=quality, method=6)
        return buf.getvalue()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--cap", type=int, default=1280)
    ap.add_argument("--quality", type=int, default=85)
    args = ap.parse_args()

    rasters = [
        p for p in sorted(IMAGES.rglob("*"))
        if p.is_file() and p.name not in SKIP_NAMES and p.suffix.lower() not in KEEP
        and p.suffix.lower() != ".webp"
    ]
    if not rasters:
        print("nothing to convert — every raster is already WebP")

    # ⚠️ Collision guard. `foo.png` and `foo.jpg` would both become `foo.webp` and one would
    # silently overwrite the other, leaving a guide pointing at the wrong picture.
    targets: dict[Path, Path] = {}
    for p in rasters:
        t = p.with_suffix(".webp")
        if t in targets.values() or t.exists():
            sys.exit(f"COLLISION: {p} would overwrite {t}")
        targets[p] = t

    before = sum(p.stat().st_size for p in rasters)
    after = 0
    for src, dst in targets.items():
        data = convert(src, args.cap, args.quality)
        after += len(data)
        if not args.check:
            dst.write_bytes(data)
            src.unlink()
    print(
        f"{len(rasters)} rasters: {before / 1048576:.1f} MB -> {after / 1048576:.1f} MB "
        f"(saves {(before - after) / 1048576:.1f} MB, {100 * (before - after) / before:.0f}%)"
        + ("  [--check, nothing written]" if args.check else "")
    )

    # Point every section at the new file name.
    touched = 0
    for shard in shard_files():
        doc = json.loads(shard.read_text(encoding="utf-8"))
        guides = doc if isinstance(doc, list) else doc.get("guides", [])
        changed = False
        for g in guides:
            for s in g.get("sections") or []:
                img = s.get("image")
                if img:
                    new = rewrite_reference(img)
                    if new != img:
                        s["image"] = new
                        changed = True
                        touched += 1
        if changed and not args.check:
            shard.write_text(
                json.dumps(doc, ensure_ascii=False, indent=1) + "\n", encoding="utf-8"
            )
    print(f"{touched} section references repointed")

    # Nothing may be left dangling.
    named: set[str] = set()
    for shard in shard_files():
        doc = json.loads(shard.read_text(encoding="utf-8"))
        guides = doc if isinstance(doc, list) else doc.get("guides", [])
        for g in guides:
            for s in g.get("sections") or []:
                if s.get("image"):
                    named.add(s["image"])
    missing = sorted(n for n in named if not (IMAGES / n).is_file())
    if missing and not args.check:
        sys.exit(f"DANGLING after conversion: {missing[:5]} ({len(missing)} total)")
    print(f"{len(named)} distinct images referenced, all present" if not missing else
          f"{len(missing)} would dangle (expected under --check)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
