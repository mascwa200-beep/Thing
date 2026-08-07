#!/usr/bin/env python3
"""
Attach sourced images to bundled guides. The image half of a content wave.

An image wave produces two things: real image files already downloaded into
`app/src/main/assets/survival/images/`, and one patch file per guide describing where they go. This
merges the patches into the shards. It never downloads anything itself — by the time it runs, every
file must already be on disk, because a reference to a missing image is a hard CI failure and
`kb_pipeline.py` only catches it *after* the shards have been rewritten.

Patch file shape, one per guide, named <guide-id>.json:

    {"guideId": "...",
     "assignments": [{"heading": "<exact existing section heading>", "image": "kb/foo.jpg"}, ...]}

Guards, all fatal and all checked before anything is written (this tool is all-or-nothing, matching
the other mergers):
  * unknown guide id, or a heading that doesn't exist in that guide
  * an image path not present on disk, or pointing outside the images directory
  * a file that isn't actually an image (a saved 404 page is the common way this goes wrong)
  * overwriting an image a section already has — attaching is additive; replacing is a deliberate act
  * two assignments claiming the same section, or the same image used twice in one guide

Deliberately NOT checked: whether the picture is any good, or whether its licence permits bundling.
Licence is the sourcing agent's job and is recorded in images/NOTICE.txt; this tool assumes that was
done and only enforces structural correctness.

Usage:  python3 tools/kb/merge_images.py <wave-dir>
"""
import glob
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
ASSETS = os.path.join(REPO, "app", "src", "main", "assets", "survival")
IMAGES = os.path.join(ASSETS, "images")

# Leading bytes that identify the formats the on-device loader can decode (Coil + coil-svg).
MAGIC = {
    b"\xff\xd8\xff": "jpeg",
    b"\x89PNG\r\n\x1a\n": "png",
    b"GIF87a": "gif",
    b"GIF89a": "gif",
    b"RIFF": "webp",
}


def looks_like_image(path: str) -> str | None:
    """Return a format name, or None if this isn't a decodable image. Catches saved error pages."""
    with open(path, "rb") as fh:
        head = fh.read(512)
    if not head:
        return None
    for magic, kind in MAGIC.items():
        if head.startswith(magic):
            return kind
    stripped = head.lstrip()
    if stripped.startswith(b"<?xml") or stripped.startswith(b"<svg"):
        # An HTML error page also starts with '<', so require an actual <svg element.
        return "svg" if b"<svg" in head else None
    return None


def main() -> None:
    if len(sys.argv) != 2:
        sys.exit("usage: merge_images.py <wave-dir>")
    wave_dir = sys.argv[1]

    shard_of: dict[str, str] = {}
    guides: dict[str, dict] = {}
    books: dict[str, dict] = {}
    for path in sorted(glob.glob(os.path.join(ASSETS, "guides*.json"))):
        book = json.load(open(path, encoding="utf-8"))
        books[path] = book
        for g in book["guides"]:
            guides[g["id"]] = g
            shard_of[g["id"]] = path

    on_disk = {
        os.path.relpath(os.path.join(r, f), IMAGES).replace(os.sep, "/")
        for r, _, fs in os.walk(IMAGES)
        for f in fs
    }

    files = sorted(glob.glob(os.path.join(wave_dir, "*.json")))
    if not files:
        sys.exit(f"no patch files in {wave_dir}")

    fatals: list[str] = []
    warnings: list[str] = []
    planned: list[tuple[str, str, str]] = []   # (guide id, heading, image)
    touched: set[str] = set()

    for path in files:
        name = os.path.basename(path)
        try:
            patch = json.load(open(path, encoding="utf-8"))
        except Exception as exc:  # noqa: BLE001
            fatals.append(f"{name}: unparseable: {exc}")
            continue

        gid = patch.get("guideId")
        guide = guides.get(gid)
        if guide is None:
            warnings.append(f"{name}: guideId {gid!r} is not bundled — skipped")
            continue

        by_heading = {s.get("heading", ""): s for s in guide["sections"]}
        claimed_sections: set[str] = set()
        claimed_images: set[str] = set()

        for a in patch.get("assignments", []):
            heading, image = a.get("heading", ""), a.get("image", "")
            tag = f"{name}: {heading!r}"
            section = by_heading.get(heading)
            if section is None:
                fatals.append(f"{tag}: no such section in {gid}")
                continue
            if heading in claimed_sections:
                fatals.append(f"{tag}: two assignments for the same section")
                continue
            claimed_sections.add(heading)
            if image in claimed_images:
                fatals.append(f"{tag}: image {image!r} used twice in one guide")
                continue
            claimed_images.add(image)

            if not image or image.startswith("/") or ".." in image.split("/"):
                fatals.append(f"{tag}: unsafe image path {image!r}")
                continue
            if image not in on_disk:
                fatals.append(f"{tag}: image {image!r} is not on disk — download it first")
                continue
            kind = looks_like_image(os.path.join(IMAGES, image))
            if kind is None:
                fatals.append(f"{tag}: {image!r} is not a decodable image (a saved error page?)")
                continue
            if section.get("image"):
                fatals.append(f"{tag}: already has image {section['image']!r} — refusing to overwrite")
                continue

            planned.append((gid, heading, image))
            touched.add(shard_of[gid])

    # Warnings print even on a fatal exit — the sibling mergers suppress theirs behind the fatal
    # branch, which hides half the picture on exactly the runs where you most need it.
    for w in warnings:
        print(f"WARN: {w}")
    if fatals:
        print(f"FATAL violations — NOTHING was written ({len(fatals)}):")
        for f in fatals:
            print(f"  - {f}")
        sys.exit(1)
    if not planned:
        sys.exit("nothing to attach")

    for gid, heading, image in planned:
        for s in guides[gid]["sections"]:
            if s.get("heading") == heading:
                s["image"] = image
                break

    for path in sorted(touched):
        with open(path, "w", encoding="utf-8") as fh:
            json.dump(books[path], fh, ensure_ascii=False, indent=1)
            fh.write("\n")

    by_guide: dict[str, int] = {}
    for gid, _, _ in planned:
        by_guide[gid] = by_guide.get(gid, 0) + 1
    for gid in sorted(by_guide):
        print(f"  {gid}: +{by_guide[gid]} image(s)")
    print(
        f"ATTACHED {len(planned)} image(s) across {len(by_guide)} guide(s) in {len(touched)} shard "
        f"file(s). Now run kb_pipeline.py to re-index and validate."
    )


if __name__ == "__main__":
    main()
