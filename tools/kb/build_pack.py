#!/usr/bin/env python3
"""Build an expansion pack from drafted guides, and the catalog row that offers it.

An expansion pack is how the library grows without the installer growing. A literal thousandfold of
the bundled corpus is gigabytes; a pack is fetched once and is then indistinguishable from bundled
content, with no network anywhere in the reading path.

The pack format is deliberately dull: **a zip of guide shards**, the same `{"guides":[...]}` files
the bundle ships, and nothing else. No index rides along — an index beside the content is a second
copy that can disagree with it, and the disagreement only ever shows up as a guide that lists but
will not open. The app derives the index from the shards at install time.

Usage:

    tools/kb/build_pack.py <guides-dir> --id cooking --title "Cooking" --version 2 \
        [--summary "..."] [--out dist/] [--per-shard 25]

`<guides-dir>` holds either whole shard files (`{"guides":[...]}`) or single-guide JSON files, in
any mixture — a drafting wave produces both shapes depending on which script wrote it.

Writes `pack-<id>-v<version>.zip` plus a `catalog-row.json` fragment to paste into `packs.json`.

⚠️ Refuses to build a pack that collides with a bundled guide id. The app drops such guides at merge
time (the bundle always wins), so shipping them would advertise a guide count the reader never gets.
Better to fail here, where somebody can rename them, than to quietly under-deliver.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import sys
import zipfile

REPO = pathlib.Path(__file__).resolve().parents[2]
BUNDLED = REPO / "app/src/main/assets/survival"

REQUIRED_GUIDE_FIELDS = ("id", "title", "category", "summary", "sections")


def load_guides(src: pathlib.Path) -> list[dict]:
    """Every guide under *src*, whether stored one per file or in shards."""
    guides: list[dict] = []
    for path in sorted(src.rglob("*.json")):
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as e:
            sys.exit(f"{path}: not valid JSON — {e}")
        if isinstance(data, dict) and "guides" in data:
            guides.extend(data["guides"])
        elif isinstance(data, dict) and "id" in data:
            guides.append(data)
        elif isinstance(data, list):
            guides.extend(data)
        else:
            sys.exit(f"{path}: not a guide or a shard")
    return guides


def bundled_ids() -> set[str]:
    index = BUNDLED / "guide_index.json"
    if not index.is_file():
        return set()
    return {e["id"] for e in json.loads(index.read_text(encoding="utf-8"))["entries"]}


def validate(guides: list[dict]) -> None:
    if not guides:
        sys.exit("No guides found.")

    seen: set[str] = set()
    for g in guides:
        missing = [f for f in REQUIRED_GUIDE_FIELDS if not g.get(f)]
        if missing:
            sys.exit(f"{g.get('id', '<no id>')}: missing {', '.join(missing)}")
        if g["id"] in seen:
            sys.exit(f"{g['id']}: appears twice in this pack")
        seen.add(g["id"])
        for s in g["sections"]:
            if not s.get("heading") or not s.get("body"):
                sys.exit(f"{g['id']}: a section has no heading or no body")

    # ⚠️ The app's merge rule is bundled-wins, so a colliding guide would simply never appear.
    clash = seen & bundled_ids()
    if clash:
        sys.exit(
            "These ids already exist in the bundled library and would be dropped at install time:\n  "
            + "\n  ".join(sorted(clash))
        )


def build(guides: list[dict], pack_id: str, version: int, per_shard: int, out: pathlib.Path) -> pathlib.Path:
    out.mkdir(parents=True, exist_ok=True)
    archive = out / f"pack-{pack_id}-v{version}.zip"

    # Deterministic: sorted guides, fixed timestamps, fixed order. Rebuilding the same content twice
    # must produce the same bytes, or the digest in the catalog is meaningless as an identity.
    guides = sorted(guides, key=lambda g: g["id"])
    shards = [guides[i : i + per_shard] for i in range(0, len(guides), per_shard)]

    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as z:
        for n, chunk in enumerate(shards, start=1):
            name = f"guides_{pack_id}_{n}.json"
            payload = json.dumps({"guides": chunk}, ensure_ascii=False, indent=1)
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            z.writestr(info, payload.encode("utf-8"))
    return archive


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("src", type=pathlib.Path, help="directory of drafted guides or shards")
    ap.add_argument("--id", required=True, help="pack id — lowercase, no spaces")
    ap.add_argument("--title", required=True)
    ap.add_argument("--summary", default="")
    ap.add_argument("--version", type=int, required=True, help="bump on every content change")
    ap.add_argument("--per-shard", type=int, default=25, help="guides per shard file (default 25)")
    ap.add_argument("--out", type=pathlib.Path, default=REPO / "dist" / "packs")
    args = ap.parse_args()

    if args.version < 1:
        sys.exit("--version must be 1 or more; 0 means 'not usable' to the app.")
    if args.id != args.id.strip().lower() or " " in args.id:
        sys.exit("--id must be lowercase with no spaces.")

    guides = load_guides(args.src)
    validate(guides)
    archive = build(guides, args.id, args.version, args.per_shard, args.out)

    raw = archive.read_bytes()
    row = {
        "id": args.id,
        "title": args.title,
        "summary": args.summary,
        "version": args.version,
        "sizeBytes": len(raw),
        "guideCount": len(guides),
        "asset": archive.name,
        "sha256": hashlib.sha256(raw).hexdigest(),
    }
    (args.out / "catalog-row.json").write_text(json.dumps(row, indent=2) + "\n", encoding="utf-8")

    sections = sum(len(g["sections"]) for g in guides)
    print(f"{archive}")
    print(f"  {len(guides)} guides · {sections} sections · {len(raw) / 1024 / 1024:.2f} MB")
    print(f"  sha256 {row['sha256']}")
    print(f"\nCatalog row written to {args.out / 'catalog-row.json'} — add it to the `packs` array of")
    print("packs.json, and upload both that and the archive to the `packs` release.")


if __name__ == "__main__":
    main()
