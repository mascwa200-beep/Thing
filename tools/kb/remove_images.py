#!/usr/bin/env python3
"""Take a diagram back out of the library — the shard, the file and the NOTICE, together.

`source_images.py` writes three things when it accepts a picture: the image file, an `image` field
on a section of the guide's shard, and a four-line provenance block in `images/NOTICE.txt`. A
picture judged wrong by hand has to lose all three, and leaving any one behind is its own defect:

  * the file alone is an **orphan**, which `BundledImagesTest.nothingShipsThatNoGuidePointsAt`
    fails the build over — that gate exists because an aborted wave once stranded 59 of them;
  * the field alone is a **dangling reference**, which is worse, because the reader draws nothing
    on a page somebody opened;
  * the NOTICE block alone claims the library bundles a work it does not, which is a licensing
    statement about a file that is not there.

So removal is one operation over all three, not three edits done carefully.

⚠️ **A guide id is not enough to name a picture.** A guide can carry more than one (`-2`, `-3`), so
this identifies the entry by the **Commons file title** recorded in the NOTICE, which is what the
hand-review actually judged. Naming the wrong one would delete a good diagram and keep the bad.

Usage:  remove_images.py removals.json   [--dry-run]
where the file is [{"guide": "<id>", "file": "File:...", "why": "..."}].
"""
from __future__ import annotations

import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets", "survival")
IMAGES = os.path.join(ASSETS, "images")
NOTICE = os.path.join(IMAGES, "NOTICE.txt")


def shard_paths() -> list[str]:
    return sorted(
        os.path.join(ASSETS, f)
        for f in os.listdir(ASSETS)
        if f.startswith("guides_") and f.endswith(".json")
    )


def notice_blocks(text: str) -> list[tuple[int, int, str, str]]:
    """Every provenance block as (start, end, relative-path, commons-title).

    The blocks are `kb/<name>` then three indented lines. Parsed by position rather than by regex
    over the whole file so a removal can splice out an exact span and leave every byte around it
    untouched — this file is a licensing record and rewriting it wholesale would be a worse risk
    than the edit itself.
    """
    lines = text.split("\n")
    out = []
    for i, line in enumerate(lines):
        if not line.startswith("kb/"):
            continue
        title = lines[i + 1].strip() if i + 1 < len(lines) else ""
        out.append((i, min(i + 4, len(lines)), line.strip(), title))
    return out


def main() -> int:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    dry = "--dry-run" in sys.argv
    if not args:
        print(__doc__)
        return 2
    removals = json.load(open(args[0], encoding="utf-8"))

    text = open(NOTICE, encoding="utf-8").read()
    blocks = notice_blocks(text)

    # Resolve each removal to exactly one bundled path, by guide id AND Commons title.
    # ⚠️ Every failure is collected before returning, not raised at the first. A removal list is
    # written by hand against a report and is stale as often as not — one entry already reverted in
    # an earlier pass made the first run stop after naming one problem, which says nothing about
    # the other seventeen. One run should tell you everything that is wrong with the list.
    targets: dict[str, str] = {}
    problems: list[str] = []
    for r in removals:
        guide, want = r["guide"], r["file"]
        hits = [
            b for b in blocks
            if re.fullmatch(rf"kb/{re.escape(guide)}(-\d+)?\.[a-z]+", b[2]) and b[3] == want
        ]
        if len(hits) != 1:
            problems.append(f"  {guide}: {len(hits)} notice blocks match {want!r}")
            continue
        targets[hits[0][2]] = guide
    if problems:
        print(f"FAIL {len(problems)} entries did not resolve to exactly one picture:")
        print("\n".join(problems))
        return 1
    print(f"resolved {len(targets)} pictures to remove")

    # 1. the shard field
    cleared = 0
    for path in shard_paths():
        doc = json.load(open(path, encoding="utf-8"))
        items = doc if isinstance(doc, list) else doc.get("guides", [])
        touched = False
        for g in items:
            for sec in g.get("sections", []):
                if sec.get("image") in targets:
                    sec.pop("image", None)
                    cleared += 1
                    touched = True
        if touched and not dry:
            tmp = path + ".tmp"
            with open(tmp, "w", encoding="utf-8") as fh:
                json.dump(doc, fh, ensure_ascii=False, indent=1)
                fh.write("\n")
                fh.flush()
                os.fsync(fh.fileno())
            os.replace(tmp, path)
            print(f"  patched {os.path.basename(path)}")
    print(f"cleared {cleared} section references")
    if cleared != len(targets):
        print("FAIL every removed picture must have had exactly one referencing section")
        return 1

    # 2. the file
    for rel in targets:
        p = os.path.join(IMAGES, rel)  # rel is already "kb/<name>"
        if os.path.exists(p):
            if not dry:
                os.remove(p)
            print(f"  removed {rel}")
        else:
            print(f"  (already absent) {rel}")

    # 3. the NOTICE block
    lines = text.split("\n")
    drop: set[int] = set()
    for start, end, rel, _title in blocks:
        if rel in targets:
            drop |= set(range(start, end))
    kept = [l for i, l in enumerate(lines) if i not in drop]
    if not dry:
        with open(NOTICE, "w", encoding="utf-8") as fh:
            fh.write("\n".join(kept))
    print(f"removed {len(drop)} NOTICE lines ({len(drop) // 4} blocks)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
