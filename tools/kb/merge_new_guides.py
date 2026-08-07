#!/usr/bin/env python3
"""Merge a Track-B drafting wave (NEW guides) into the Knowledge Base.

Usage: python3 tools/kb/merge_new_guides.py <wave-dir>
where <wave-dir> holds one `<guide-id>.json` per NEW guide — a full guide object
({id,title,category,summary,sections[,safetyNote]}) drafted against a pending topic in
tools/kb/topic_manifest.json (title == the topic text, so the pipeline's title-normalized
auto-link marks the topic covered).

Per-file judgement:
- SKIP with a warning: unparseable, not a guide object, id already bundled (a re-run or an agent
  retrying an existing topic), or thinner than MIN_WORDS (a stub that would drag the library down).
- FATAL (exit 1, nothing written): an unknown category (must be in the fixed taxonomy — mirrors
  GuideTaxonomy/GuidesJsonValidationTest/build_manifest.py), a duplicate id WITHIN the wave, blank
  required fields, empty/duplicate-heading sections, or a section image that doesn't exist on disk.

Accepted guides are appended to `guides_incoming.json`; run `tools/kb/kb_pipeline.py` right after —
it reshards them into their per-category files, rebuilds the index, validates, recounts pages and
auto-links the topic manifest.
"""
import glob
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
ASSETS = os.path.join(REPO, "app", "src", "main", "assets", "survival")
IMAGES = os.path.join(ASSETS, "images")
INCOMING = os.path.join(ASSETS, "guides_incoming.json")
MIN_WORDS = 1500

KNOWN_CATEGORIES = {
    "Astronomy", "Biology", "Chemistry", "Climate", "Computing", "Earth Science", "Engineering",
    "Essentials", "Foundations", "Geography", "Hazards", "Health", "Making", "Mathematics", "Medical",
    "Movement", "Navigation", "Nutrition", "Physics", "Preparedness", "Psychology", "Reference", "Rescue",
    "Skills", "Sustenance", "Weather", "Cooking — Food Safety",
    "Energy & Environment", "Sports & Fitness", "Electronics", "Home & Repair", "Agriculture & Gardening",
    "Vehicles & Transport", "Cooking — Techniques", "History", "Philosophy", "Religion & Mythology",
    "Literature & Writing", "Language & Linguistics", "Society & Culture", "Archaeology & Anthropology",
    "Law & Government", "Education & Learning", "Music", "Visual Arts & Design", "Games & Recreation",
    "Media & Communication", "Business & Finance", "Economics",
}


def guide_words(g: dict) -> int:
    total = 0
    for s in g.get("sections", []):
        text = s.get("body", "") or ""
        for k in ("ingredients", "steps"):
            v = s.get(k)
            if v:
                text += " " + " ".join(v)
        total += len(text.split())
    return total


def main() -> None:
    if len(sys.argv) != 2:
        sys.exit("usage: merge_new_guides.py <wave-dir>")
    wave_dir = sys.argv[1]

    existing_ids: set[str] = set()
    for p in sorted(glob.glob(os.path.join(ASSETS, "guides*.json"))):
        with open(p, encoding="utf-8") as fh:
            for g in json.load(fh)["guides"]:
                existing_ids.add(g["id"])

    images_on_disk: set[str] = set()
    for root, _, fs in os.walk(IMAGES):
        for f in fs:
            images_on_disk.add(os.path.relpath(os.path.join(root, f), IMAGES).replace(os.sep, "/"))

    accepted: list[dict] = []
    wave_ids: set[str] = set()
    warnings: list[str] = []
    fatals: list[str] = []

    for path in sorted(glob.glob(os.path.join(wave_dir, "*.json"))):
        name = os.path.basename(path)
        try:
            with open(path, encoding="utf-8") as fh:
                g = json.load(fh)
        except Exception as e:  # noqa: BLE001
            warnings.append(f"{name}: unreadable JSON ({e}) — skipped")
            continue
        if not isinstance(g, dict) or not g.get("id") or not isinstance(g.get("sections"), list):
            warnings.append(f"{name}: not a guide object — skipped")
            continue
        gid = g["id"]
        if gid in existing_ids:
            warnings.append(f"{name}: id {gid!r} already bundled — skipped")
            continue
        if gid in wave_ids:
            fatals.append(f"{name}: id {gid!r} duplicated WITHIN the wave")
            continue
        if not g.get("title") or not g.get("category") or not g.get("summary"):
            fatals.append(f"{name}: blank required field")
            continue
        if g["category"] not in KNOWN_CATEGORIES:
            fatals.append(f"{name}: unknown category {g['category']!r}")
            continue
        heads: set[str] = set()
        bad = None
        for s in g["sections"]:
            h = s.get("heading", "")
            if not h or h in heads:
                bad = f"blank/duplicate heading {h!r}"
                break
            heads.add(h)
            if not (s.get("body") or s.get("ingredients") or s.get("steps")):
                bad = f"empty section {h!r}"
                break
            img = s.get("image")
            if img and img not in images_on_disk:
                bad = f"dangling image {img!r} in {h!r}"
                break
        if bad:
            fatals.append(f"{name}: {bad}")
            continue
        if not g["sections"]:
            fatals.append(f"{name}: zero sections")
            continue
        words = guide_words(g)
        if words < MIN_WORDS:
            warnings.append(f"{name}: only {words} words (< {MIN_WORDS}) — skipped as a stub")
            continue
        wave_ids.add(gid)
        accepted.append(g)

    if fatals:
        print("FATAL violations — NOTHING was written:")
        for f in fatals:
            print(" -", f)
        sys.exit(1)

    for w in warnings:
        print("WARN:", w)
    if not accepted:
        sys.exit("nothing to merge")

    book = {"guides": []}
    if os.path.exists(INCOMING):
        with open(INCOMING, encoding="utf-8") as fh:
            book = json.load(fh)
    book["guides"].extend(accepted)
    with open(INCOMING, "w", encoding="utf-8") as fh:
        json.dump(book, fh, ensure_ascii=False, indent=1)
        fh.write("\n")
    total = sum(guide_words(g) for g in accepted)
    print(f"MERGED {len(accepted)} new guides ({total} words) -> guides_incoming.json. "
          f"Now run kb_pipeline.py to reshard/index/validate/link.")


if __name__ == "__main__":
    main()
