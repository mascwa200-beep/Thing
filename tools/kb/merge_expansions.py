#!/usr/bin/env python3
"""Merge a Track-A expansion wave into the guide shards.

Usage: python3 tools/kb/merge_expansions.py <wave-dir>
where <wave-dir> holds one `<guide-id>.json` per expanded guide — the FULL guide object with every
sub-400-word section rewritten deeper (same id, same section headings in the same order, images kept).

Safety model (each file judged independently):
- SKIP with a warning: a file that doesn't parse as a guide object, targets an unknown id, targets an
  id already merged this run, or shows no word growth (this last rule is what quietly rejects agents'
  stray scratch copies of ORIGINAL guides, e.g. `original.json`).
- FATAL (exit 1, nothing further merges): a real structural violation — changed title/category, changed
  or reordered section headings, or a dropped section image. Those would break deep links, the topic
  manifest's title linking, or bundled diagrams, and deserve a human look, not a silent skip.

Run `tools/kb/kb_pipeline.py` afterwards (reshard/index/validate/page-count) — this script edits shard
files in place but does not rebuild the index.
"""
import glob
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
ASSETS = os.path.join(REPO, "app", "src", "main", "assets", "survival")


def section_words(sec: dict) -> int:
    text = sec.get("body", "") or ""
    for k in ("ingredients", "steps"):
        v = sec.get(k)
        if v:
            text += " " + " ".join(v)
    return len(text.split())


def guide_words(g: dict) -> int:
    return sum(section_words(s) for s in g.get("sections", []))


def main() -> None:
    if len(sys.argv) != 2:
        sys.exit("usage: merge_expansions.py <wave-dir>")
    wave_dir = sys.argv[1]

    # id -> (shard path, position) over the current shards.
    shards: dict[str, dict] = {}  # path -> parsed book
    where: dict[str, tuple[str, int]] = {}
    for p in sorted(glob.glob(os.path.join(ASSETS, "guides*.json"))):
        with open(p, encoding="utf-8") as fh:
            book = json.load(fh)
        shards[p] = book
        for i, g in enumerate(book["guides"]):
            where[g["id"]] = (p, i)

    merged: dict[str, tuple[int, int]] = {}  # id -> (old words, new words)
    warnings: list[str] = []
    fatals: list[str] = []
    touched: set[str] = set()

    for path in sorted(glob.glob(os.path.join(wave_dir, "*.json"))):
        name = os.path.basename(path)
        try:
            with open(path, encoding="utf-8") as fh:
                new = json.load(fh)
        except Exception as e:  # noqa: BLE001 - report and move on
            warnings.append(f"{name}: unreadable JSON ({e}) — skipped")
            continue
        if not isinstance(new, dict) or not new.get("id") or not isinstance(new.get("sections"), list):
            warnings.append(f"{name}: not a guide object — skipped")
            continue
        gid = new["id"]
        if gid not in where:
            warnings.append(f"{name}: id {gid!r} not in any shard — skipped")
            continue
        if gid in merged:
            warnings.append(f"{name}: id {gid!r} already merged this run — skipped")
            continue

        shard_path, idx = where[gid]
        old = shards[shard_path]["guides"][idx]

        if new.get("title") != old["title"] or new.get("category") != old["category"]:
            fatals.append(f"{name}: title/category changed ({old['title']!r}/{old['category']!r} -> "
                          f"{new.get('title')!r}/{new.get('category')!r})")
            continue
        old_heads = [s.get("heading", "") for s in old.get("sections", [])]
        new_heads = [s.get("heading", "") for s in new.get("sections", [])]
        if old_heads != new_heads:
            fatals.append(f"{name}: section headings changed/reordered")
            continue
        lost = [
            h for h, o, n in zip(old_heads, old["sections"], new["sections"])
            if o.get("image") and n.get("image") != o.get("image")
        ]
        if lost:
            fatals.append(f"{name}: dropped/changed section image(s) at {lost}")
            continue

        ow, nw = guide_words(old), guide_words(new)
        if nw <= ow:
            warnings.append(f"{name}: no word growth ({ow} -> {nw}) — skipped (stray original copy?)")
            continue

        shards[shard_path]["guides"][idx] = new
        touched.add(shard_path)
        merged[gid] = (ow, nw)

    if fatals:
        print("FATAL structural violations — NOTHING was written:")
        for f in fatals:
            print(" -", f)
        sys.exit(1)

    for p in sorted(touched):
        with open(p, "w", encoding="utf-8") as fh:
            json.dump(shards[p], fh, ensure_ascii=False, indent=1)
            fh.write("\n")

    for w in warnings:
        print("WARN:", w)
    total_old = sum(o for o, _ in merged.values())
    total_new = sum(n for _, n in merged.values())
    for gid, (o, n) in sorted(merged.items()):
        print(f"  {gid}: {o} -> {n} words (+{n - o})")
    print(f"MERGED {len(merged)} guides across {len(touched)} shard files · "
          f"{total_old} -> {total_new} words (+{total_new - total_old}). Now run kb_pipeline.py.")


if __name__ == "__main__":
    main()
