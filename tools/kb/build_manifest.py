#!/usr/bin/env python3
"""Build/extend tools/kb/topic_manifest.json — the 10,000-topic coverage ledger.

Usage: python3 tools/kb/build_manifest.py <ontology-dir>
where <ontology-dir> holds one or more JSON files, each a [{"topic": "...", "category": "..."}] array
(the output of the domain-enumeration workflow).

The manifest is a stable, append-only ledger: {"topics": [{"id","topic","category","guideId"}]}.
- Existing entries (their ids and guideId links) are NEVER changed or re-ordered by a re-run.
- New topics are deduped against everything already in the ledger (case/punctuation-insensitive) and
  appended with fresh sequential ids.
- Every topic's category must be one of the fixed taxonomy categories (mirrors GuideTaxonomy /
  GuidesJsonValidationTest.knownCategories) — an unknown category fails loudly, never silently.
- Topics whose title matches an existing bundled guide are linked immediately (guideId set), so the
  ledger starts with today's coverage; `kb_pipeline.py` keeps auto-linking after every content wave.

The drafting waves work through entries with guideId == null; "covered" = every entry linked.
"""
import glob
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
ASSETS = os.path.join(REPO, "app", "src", "main", "assets", "survival")
MANIFEST = os.path.join(HERE, "topic_manifest.json")

# Mirrors GuideTaxonomy.CATEGORY_SUPERGROUP / the CI allowlist — keep in lockstep.
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
    # Federation Database — the in-universe reference.
    "Species & Civilisations", "Starfleet & Federation", "Starships & Classes", "Warp & Starship Technology", "Worlds & Stations", "Federation Timeline", "Notable Figures",
}


def norm(s: str) -> str:
    return re.sub(r"[^a-z0-9]+", "", s.lower())


def main() -> None:
    if len(sys.argv) != 2:
        sys.exit("usage: build_manifest.py <ontology-dir>")
    src_dir = sys.argv[1]

    manifest = {"topics": []}
    if os.path.exists(MANIFEST):
        with open(MANIFEST, encoding="utf-8") as fh:
            manifest = json.load(fh)
    topics = manifest["topics"]
    seen = {norm(t["topic"]) for t in topics}
    next_seq = len(topics) + 1

    # Existing bundled guides, for immediate linking.
    guide_by_title = {}
    for p in sorted(glob.glob(os.path.join(ASSETS, "guides*.json"))):
        with open(p, encoding="utf-8") as fh:
            for g in json.load(fh)["guides"]:
                guide_by_title[norm(g["title"])] = g["id"]

    files = sorted(glob.glob(os.path.join(src_dir, "*.json")))
    if not files:
        sys.exit(f"no ontology JSON files in {src_dir}")
    bad_categories: list[str] = []
    added = 0
    dupes = 0
    per_file = {}
    for p in files:
        with open(p, encoding="utf-8") as fh:
            entries = json.load(fh)
        count = 0
        for e in entries:
            topic = re.sub(r"\s+", " ", str(e.get("topic", "")).strip())
            category = str(e.get("category", "")).strip()
            if not topic:
                continue
            if category not in KNOWN_CATEGORIES:
                bad_categories.append(f"{os.path.basename(p)}: {topic!r} -> unknown category {category!r}")
                continue
            key = norm(topic)
            if not key or key in seen:
                dupes += 1
                continue
            seen.add(key)
            topics.append({
                "id": f"t{next_seq:05d}",
                "topic": topic,
                "category": category,
                "guideId": guide_by_title.get(key),
            })
            next_seq += 1
            added += 1
            count += 1
        per_file[os.path.basename(p)] = count

    for name, count in sorted(per_file.items()):
        print(f"  {name}: +{count}")
    if bad_categories:
        print(f"UNKNOWN CATEGORIES ({len(bad_categories)}):")
        for b in bad_categories[:30]:
            print(" -", b)
        sys.exit(1)

    with open(MANIFEST, "w", encoding="utf-8") as fh:
        json.dump(manifest, fh, ensure_ascii=False, indent=1)
        fh.write("\n")
    done = sum(1 for t in topics if t.get("guideId"))
    print(f"MANIFEST: {len(topics)} topics (+{added} new, {dupes} duplicates skipped) · {done} covered · "
          f"{len(topics) - done} pending -> {os.path.relpath(MANIFEST, REPO)}")


if __name__ == "__main__":
    main()
