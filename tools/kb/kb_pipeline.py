#!/usr/bin/env python3
"""Knowledge Base content pipeline: reshard + index + validate + page-count.

Run from anywhere: `python3 tools/kb/kb_pipeline.py`. Idempotent — safe after every content wave.

1. RESHARD: merges every assets/survival/guides*.json and rewrites them as one file per category
   (guides_<category-slug>.json, original guide order preserved). The on-device loader globs
   "guides*.json", so sharding is loader-compatible by construction; per-category files also let
   parallel drafting waves merge without touching the same file.
2. INDEX: writes assets/survival/guide_index.json — {id, title, category, summary, headings[], file}
   per guide. Deliberately NOT named guides_*.json so neither the loader glob nor the validation
   test's shard glob picks it up as a guide book. The app's SurvivalContentRepository.index() parses
   this small file for browse/search chrome; CI (GuidesJsonValidationTest) asserts it stays in
   lockstep with the shards, so editing a shard without re-running this script fails the build.
3. VALIDATE: the Python twin of GuidesJsonValidationTest — id uniqueness, non-blank required fields,
   unique headings, non-empty sections, no dangling section images.
4. PAGE COUNT: prints the official "full pages" metric — sections whose body+ingredients+steps reach
   >=400 words (about one printed page). The standing target is 10,000+; the CI test asserts the
   count never regresses below its committed baseline.
"""
import json, os, re, sys, glob

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
ASSETS = os.path.join(REPO, "app", "src", "main", "assets", "survival")
IMAGES = os.path.join(ASSETS, "images")
INDEX_PATH = os.path.join(ASSETS, "guide_index.json")
FULL_PAGE_WORDS = 400


def slug(cat: str) -> str:
    s = cat.lower().replace("—", " ").replace("&", " and ")
    return re.sub(r"[^a-z0-9]+", "_", s).strip("_")


def section_words(sec: dict) -> int:
    text = sec.get("body", "") or ""
    for k in ("ingredients", "steps"):
        v = sec.get(k)
        if v:
            text += " " + " ".join(v)
    return len(text.split())


def main() -> None:
    shard_files = sorted(glob.glob(os.path.join(ASSETS, "guides*.json")))
    all_guides = []
    for f in shard_files:
        with open(f, encoding="utf-8") as fh:
            all_guides.extend(json.load(fh)["guides"])
    print(f"read {len(shard_files)} guide files, {len(all_guides)} guides")

    by_cat: dict[str, list] = {}
    for g in all_guides:
        by_cat.setdefault(g["category"], []).append(g)

    slugs: dict[str, str] = {}
    for cat in by_cat:
        s = slug(cat)
        if s in slugs:
            sys.exit(f"SLUG COLLISION: {cat!r} and {slugs[s]!r} both -> {s}")
        slugs[s] = cat

    written = set()
    for cat, guides in by_cat.items():
        p = os.path.join(ASSETS, f"guides_{slug(cat)}.json")
        with open(p, "w", encoding="utf-8") as fh:
            json.dump({"guides": guides}, fh, ensure_ascii=False, indent=1)
            fh.write("\n")
        written.add(p)
    removed = 0
    for f in shard_files:
        if f not in written:
            os.remove(f)
            removed += 1
    print(f"wrote {len(written)} category shards, removed {removed} stale files")

    # ---- index (kept in lockstep; CI enforces) ----
    index = []
    for cat, guides in sorted(by_cat.items()):
        fname = f"guides_{slug(cat)}.json"
        for g in guides:
            index.append({
                "id": g["id"],
                "title": g["title"],
                "category": g["category"],
                "summary": g["summary"],
                "headings": [s.get("heading", "") for s in g.get("sections", [])],
                "file": fname,
            })
    with open(INDEX_PATH, "w", encoding="utf-8") as fh:
        json.dump({"entries": index}, fh, ensure_ascii=False, indent=1)
        fh.write("\n")
    print(f"index: {len(index)} entries -> {os.path.relpath(INDEX_PATH, REPO)}")

    # ---- validation twin ----
    problems: list[str] = []
    seen_ids: set[str] = set()
    total_sections = 0
    full_pages = 0
    images_on_disk = set()
    for root, _, fs in os.walk(IMAGES):
        for f in fs:
            images_on_disk.add(os.path.relpath(os.path.join(root, f), IMAGES).replace(os.sep, "/"))
    merged = []
    for p in sorted(glob.glob(os.path.join(ASSETS, "guides*.json"))):
        with open(p, encoding="utf-8") as fh:
            merged.extend(json.load(fh)["guides"])
    for g in merged:
        gid = g.get("id", "")
        if not gid or not g.get("title") or not g.get("category") or not g.get("summary"):
            problems.append(f"{gid or '?'}: blank required field")
        if gid in seen_ids:
            problems.append(f"duplicate id {gid}")
        seen_ids.add(gid)
        secs = g.get("sections", [])
        if not secs:
            problems.append(f"{gid}: no sections")
        heads = set()
        for sec in secs:
            h = sec.get("heading", "")
            if not h:
                problems.append(f"{gid}: blank heading")
            if h in heads:
                problems.append(f"{gid}: duplicate heading {h!r}")
            heads.add(h)
            if not (sec.get("body") or sec.get("ingredients") or sec.get("steps")):
                problems.append(f"{gid}/{h}: empty section")
            img = sec.get("image")
            if img and img not in images_on_disk:
                problems.append(f"{gid}/{h}: dangling image {img}")
            total_sections += 1
            if section_words(sec) >= FULL_PAGE_WORDS:
                full_pages += 1
    if len(merged) != len(all_guides):
        problems.append(f"guide count changed during reshard: {len(all_guides)} -> {len(merged)}")

    print(f"TOTAL: {len(merged)} guides · {total_sections} sections · FULL PAGES (>={FULL_PAGE_WORDS}w): {full_pages} / 10,000 target")
    if problems:
        print("PROBLEMS:")
        for p in problems[:50]:
            print(" -", p)
        sys.exit(1)
    print("VALIDATION: CLEAN")


if __name__ == "__main__":
    main()
