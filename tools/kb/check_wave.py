#!/usr/bin/env python3
"""
Pre-merge lint for a content wave. Run it BEFORE merge_expansions.py / merge_new_guides.py.

Both mergers are all-or-nothing on fatals, so one bad file in a 30-agent wave stalls the whole wave.
Worse, `merge_expansions.py` rewrites the shipped shard files *before* anything validates the result,
so a problem it does not itself check leaves the tree dirty and only fails later. This catches all of
it up front, writes nothing, and tells you exactly which file to fix or drop.

It reproduces every merger FATAL, plus three classes the mergers do NOT check:

  * `summary` / `safetyNote` silently DROPPED by an expansion. merge_expansions.py replaces the whole
    guide object and never compares these two fields, so an agent that rebuilds a guide dict instead
    of mutating the original corrupts the shipped content and merges clean. This is the single most
    dangerous failure mode in the expansion track.
  * an expansion ADDING an image where the original had none — the merger's check only fires when the
    old section had one, so a fabricated path sails through and dangles.
  * the Kotlin-only assertions (blank safetyNote, recipe without safetyNote, empty/blank
    ingredients/steps) — see ci_parity_lint.py for why those bite so late.

Usage:
    python3 tools/kb/check_wave.py <wave-dir> expand
    python3 tools/kb/check_wave.py <wave-dir> new
"""
import collections
import glob
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
ASSETS = os.path.join(REPO, "app", "src", "main", "assets", "survival")
IMAGES = os.path.join(ASSETS, "images")
MANIFEST = os.path.join(HERE, "topic_manifest.json")
TEST_KT = os.path.join(
    REPO, "app", "src", "test", "java", "dev", "mascwa", "pulse", "data", "survival",
    "GuidesJsonValidationTest.kt",
)

MIN_WORDS = 1500          # merge_new_guides.py's stub floor
FULL_PAGE_WORDS = 400


def words(section: dict) -> int:
    text = section.get("body", "") or ""
    for key in ("ingredients", "steps"):
        value = section.get(key)
        if value:
            text += " " + " ".join(value)
    return len(text.split())


def norm(s: str) -> str:
    return re.sub(r"[^a-z0-9]+", "", s.lower())


def known_categories() -> set[str]:
    src = open(TEST_KT, encoding="utf-8").read()
    start = src.find("private val knownCategories = setOf")
    i, depth = src.find("(", start), 0
    for j in range(i, len(src)):
        if src[j] == "(":
            depth += 1
        elif src[j] == ")":
            depth -= 1
            if depth == 0:
                return set(re.findall(r'"([^"]+)"', src[i:j]))
    sys.exit("could not parse knownCategories")


def bundled() -> dict[str, dict]:
    out = {}
    for path in sorted(glob.glob(os.path.join(ASSETS, "guides*.json"))):
        for g in json.load(open(path, encoding="utf-8"))["guides"]:
            out[g["id"]] = g
    return out


def kotlin_only(g: dict, tag: str, err) -> None:
    """Assertions that pass every Python gate and fail only in the Kotlin build."""
    note = g.get("safetyNote")
    if note is not None and not str(note).strip():
        err(f"{tag}blank safetyNote — omit the field instead of \"\" (CI error)")
    category = g.get("category", "")
    if (
        category.startswith("Cooking — ")
        and category != "Cooking — Food Safety"
        and not str(note or "").strip()
    ):
        err(f"{tag}recipe category {category!r} with no safetyNote (CI error; the merger misses this)")
    for i, s in enumerate(g.get("sections", [])):
        for key in ("ingredients", "steps"):
            value = s.get(key)
            if value is None:
                continue
            if len(value) == 0:
                err(f"{tag}sec[{i}] empty (non-null) {key} list (CI error)")
            for j, line in enumerate(value):
                if not str(line).strip():
                    err(f"{tag}sec[{i}] {key}[{j}] is blank (CI error)")


def check_expand(files: list[str], current: dict[str, dict], problems: list[str]) -> None:
    for path in files:
        name = os.path.basename(path)

        def err(msg: str, _n=name) -> None:
            problems.append(f"{_n}: {msg}")

        try:
            g = json.load(open(path, encoding="utf-8"))
        except Exception as exc:  # noqa: BLE001
            err(f"UNPARSEABLE: {exc}")
            continue
        old = current.get(g.get("id"))
        if old is None:
            err(f"id {g.get('id')!r} is not in any shard — merger would skip it")
            continue
        if name != g["id"] + ".json":
            err("filename does not match <id>.json")

        if g.get("title") != old["title"] or g.get("category") != old["category"]:
            err("FATAL title/category changed")
        new_heads = [s.get("heading") for s in g.get("sections", [])]
        old_heads = [s.get("heading") for s in old["sections"]]
        if new_heads != old_heads:
            err("FATAL heading list changed or reordered")
            continue

        # The two fields merge_expansions.py never compares.
        if g.get("summary") != old["summary"]:
            err("summary changed or DROPPED — the merger does NOT catch this")
        if g.get("safetyNote") != old.get("safetyNote"):
            err("safetyNote changed or DROPPED — the merger does NOT catch this")

        for i, (a, b) in enumerate(zip(old["sections"], g["sections"])):
            for key in ("image", "ingredients", "steps"):
                if a.get(key) != b.get(key):
                    err(f"sec[{i}] {key} changed")
            if b.get("image") and not a.get("image"):
                err(f"sec[{i}] image ADDED where there was none — would dangle")
            if not (b.get("body") or "").strip():
                err(f"sec[{i}] blank body")

        kotlin_only(g, "", err)

        short = [(i, words(s)) for i, s in enumerate(g["sections"]) if words(s) < FULL_PAGE_WORDS]
        if short:
            err(f"sections still under {FULL_PAGE_WORDS}w: {short}")
        old_w = sum(words(s) for s in old["sections"])
        new_w = sum(words(s) for s in g["sections"])
        if new_w <= old_w:
            err(f"no word growth {old_w} -> {new_w} — the merger would silently SKIP this file")


def check_new(files: list[str], current: dict[str, dict], problems: list[str]) -> None:
    known = known_categories()
    manifest_topics = set()
    if os.path.isfile(MANIFEST):
        manifest_topics = {
            norm(t["topic"]) for t in json.load(open(MANIFEST, encoding="utf-8"))["topics"]
        }
    images_on_disk = {
        os.path.relpath(os.path.join(r, f), IMAGES).replace(os.sep, "/")
        for r, _, fs in os.walk(IMAGES)
        for f in fs
    }
    ids = collections.Counter()

    for path in files:
        name = os.path.basename(path)

        def err(msg: str, _n=name) -> None:
            problems.append(f"{_n}: {msg}")

        try:
            g = json.load(open(path, encoding="utf-8"))
        except Exception as exc:  # noqa: BLE001
            err(f"UNPARSEABLE: {exc}")
            continue
        if not isinstance(g, dict) or "guides" in g:
            err("not a bare guide object (must NOT be wrapped in {\"guides\":[...]})")
            continue

        gid = g.get("id", "")
        ids[gid] += 1
        if name != gid + ".json":
            err("filename does not match <id>.json")
        if gid in current:
            err(f"id {gid!r} is already bundled — merger will skip it")
        if norm(g.get("title", "")) not in manifest_topics:
            err("title matches NO manifest topic — the guide lands but its topic never links")
        for field in ("title", "category", "summary"):
            if not str(g.get(field, "")).strip():
                err(f"FATAL blank {field}")
        if g.get("category") and g["category"] not in known:
            err(f"FATAL unknown category {g['category']!r}")

        sections = g.get("sections") or []
        if not sections:
            err("FATAL zero sections")
        heads = [s.get("heading", "") for s in sections]
        if len(heads) != len(set(heads)) or not all(h.strip() for h in heads):
            err("FATAL blank or duplicate section heading")
        for i, s in enumerate(sections):
            if not (s.get("body") or s.get("ingredients") or s.get("steps")):
                err(f"FATAL sec[{i}] has no body/ingredients/steps")
            image = s.get("image")
            if image:
                err(f"sec[{i}] carries an image" + ("" if image in images_on_disk else " -> FATAL dangling"))

        kotlin_only(g, "", err)

        total = sum(words(s) for s in sections)
        if total < MIN_WORDS:
            err(f"{total}w < {MIN_WORDS} — merger will skip it as a stub")
        short = [(i, words(s)) for i, s in enumerate(sections) if words(s) < FULL_PAGE_WORDS]
        if short:
            err(f"sections under {FULL_PAGE_WORDS}w (they will not count as full pages): {short}")

    for gid, n in ids.items():
        if n > 1:
            problems.append(f"FATAL id {gid!r} duplicated within the wave")


def main() -> None:
    if len(sys.argv) != 3 or sys.argv[2] not in ("expand", "new"):
        sys.exit("usage: check_wave.py <wave-dir> expand|new")
    wave_dir, mode = sys.argv[1], sys.argv[2]
    files = sorted(glob.glob(os.path.join(wave_dir, "*.json")))
    if not files:
        sys.exit(f"no .json files in {wave_dir}")

    current = bundled()
    problems: list[str] = []
    (check_expand if mode == "expand" else check_new)(files, current, problems)

    print(f"{mode} wave: {len(files)} files · {len(problems)} problem(s)")
    for p in problems:
        print(f"  - {p}")
    if problems:
        sys.exit(1)
    print("CLEAN — safe to merge")


if __name__ == "__main__":
    main()
