#!/usr/bin/env python3
"""Pick the next Track-B drafting wave from the pending topic-manifest entries.

Usage: python3 tools/kb/select_wave.py [count]   (default 120)
Prints a JSON {"outDir": "...OVERRIDE ME...", "topics": [{id, topic, category}, ...]} to stdout, and
writes it to tools/kb/.next_wave.json. Selection is round-robin across categories (breadth first), skips
topics already covered (guideId set) and any id colliding with a bundled guide, and derives a stable
slug id from the topic title. Deterministic: same manifest -> same wave, so a resume picks up cleanly.

Feed the "topics" (with a real outDir) to the kb-wave-b Workflow; each agent drafts one full guide whose
title == the topic verbatim, so kb_pipeline.py's title auto-link marks the manifest entry covered.
"""
import glob
import json
import os
import re
import sys
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
ASSETS = os.path.join(REPO, "app", "src", "main", "assets", "survival")
MANIFEST = os.path.join(HERE, "topic_manifest.json")


def slug(s: str) -> str:
    return re.sub(r"-+", "-", re.sub(r"[^a-z0-9]+", "-", s.lower())).strip("-")[:60].rstrip("-")


def main() -> None:
    count = int(sys.argv[1]) if len(sys.argv) > 1 else 120
    manifest = json.load(open(MANIFEST, encoding="utf-8"))
    pending = [t for t in manifest["topics"] if not t.get("guideId")]

    used = set()
    for p in glob.glob(os.path.join(ASSETS, "guides*.json")):
        for g in json.load(open(p, encoding="utf-8"))["guides"]:
            used.add(g["id"])

    by_cat = defaultdict(list)
    for t in pending:
        by_cat[t["category"]].append(t)
    cats = sorted(by_cat)
    idx = {c: 0 for c in cats}

    sel = []
    while len(sel) < count:
        progressed = False
        for c in cats:
            if len(sel) >= count:
                break
            lst = by_cat[c]
            while idx[c] < len(lst):
                t = lst[idx[c]]
                idx[c] += 1
                gid = slug(t["topic"])
                if gid and gid not in used:
                    used.add(gid)
                    sel.append({"id": gid, "topic": t["topic"], "category": t["category"]})
                    progressed = True
                    break
        if not progressed:
            break

    out = {"outDir": "SET_ME_TO_A_WAVE_DIR", "topics": sel}
    with open(os.path.join(HERE, ".next_wave.json"), "w", encoding="utf-8") as fh:
        json.dump(out, fh, ensure_ascii=False)
    ncats = len({s["category"] for s in sel})
    print(f"# {len(sel)} topics across {ncats} categories ({len(pending)} pending in manifest)",
          file=sys.stderr)
    print(json.dumps(out, ensure_ascii=False))


if __name__ == "__main__":
    main()
