#!/usr/bin/env python3
"""
A local twin of GuidesJsonValidationTest.kt — run it after kb_pipeline.py, before committing.

Why this exists: `kb_pipeline.py`'s own validation is NOT a twin of the CI test. It omits several
assertions that live only in Kotlin, and every one of them shares the same nasty shape — the content
merges cleanly, the shipped asset files under app/src/main/assets/ are rewritten, and the failure
only surfaces later in CI, after the damage is committed. The gap, concretely:

  * a blank `safetyNote` ("") — the field must be omitted, not emptied
  * a `Cooking — <anything but Food Safety>` guide with no safetyNote (a recipe with no warning)
  * an empty but non-null `ingredients`/`steps` list, or a blank string inside one
  * a category that isn't in the taxonomy at all (kb_pipeline checks headings and images, not this)
  * a category with no GuideTaxonomy supergroup — it would silently fall into the OTHER bucket
  * guide_index.json drifting out of lockstep with the shards

It also compares the measured full-page count against FULL_PAGE_BASELINE and prints the exact ratchet
to apply, since forgetting to ratchet is silent: CI still passes, the regression guard just stops
guarding.

Exit 0 and "CLEAN" means the Kotlin test will pass. Exit 1 lists every problem.

Usage:  python3 tools/kb/ci_parity_lint.py [assets-dir]

`assets-dir` defaults to the real bundled assets. Pointing it at a copy is how this lint gets
negative-tested — a gate that has only ever returned "clean" has not been shown to catch anything.
"""
import glob
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
ASSETS = os.path.abspath(sys.argv[1]) if len(sys.argv) > 1 else os.path.join(
    REPO, "app", "src", "main", "assets", "survival"
)
IMAGES = os.path.join(ASSETS, "images")
TEST_KT = os.path.join(
    REPO, "app", "src", "test", "java", "dev", "mascwa", "pulse", "data", "survival",
    "GuidesJsonValidationTest.kt",
)
TAXONOMY_KT = os.path.join(
    REPO, "app", "src", "main", "java", "dev", "mascwa", "pulse", "data", "survival",
    "GuideTaxonomy.kt",
)

FULL_PAGE_WORDS = 400


def words(section: dict) -> int:
    """Whitespace-token count over body + ingredients + steps — identical to the Kotlin counter."""
    text = section.get("body", "") or ""
    for key in ("ingredients", "steps"):
        value = section.get(key)
        if value:
            text += " " + " ".join(value)
    return len(text.split())


def kotlin_string_set(path: str, anchor: str) -> set[str]:
    """Pull a Kotlin `setOf(...)` / `mapOf(...)` literal's string keys straight out of the source, so
    this lint can never drift from the real allowlist the way a hand-copied constant would."""
    src = open(path, encoding="utf-8").read()
    start = src.find(anchor)
    if start < 0:
        sys.exit(f"could not find {anchor!r} in {path}")
    depth, i = 0, src.find("(", start)
    for j in range(i, len(src)):
        if src[j] == "(":
            depth += 1
        elif src[j] == ")":
            depth -= 1
            if depth == 0:
                return set(re.findall(r'"([^"]+)"', src[i:j]))
    sys.exit(f"unbalanced parentheses after {anchor!r} in {path}")


def main() -> None:
    problems: list[str] = []

    known = kotlin_string_set(TEST_KT, "private val knownCategories = setOf")
    # CATEGORY_SUPERGROUP maps category -> supergroup; both sides are string literals, and every
    # category must appear as a key or its guides fall into the OTHER fallback bucket.
    supergroup_src = kotlin_string_set(TAXONOMY_KT, "val CATEGORY_SUPERGROUP")

    guides, by_file = [], {}
    for path in sorted(glob.glob(os.path.join(ASSETS, "guides*.json"))):
        name = os.path.basename(path)
        try:
            book = json.load(open(path, encoding="utf-8"))
        except Exception as exc:  # noqa: BLE001 - report, don't crash
            problems.append(f"{name}: failed to decode: {exc}")
            continue
        by_file[name] = book["guides"]
        guides.extend(book["guides"])

    if not guides:
        sys.exit("no guides found under app/src/main/assets/survival/")

    bundled_images = {
        os.path.relpath(os.path.join(root, f), IMAGES).replace(os.sep, "/")
        for root, _, files in os.walk(IMAGES)
        for f in files
    }

    seen_ids: dict[str, str] = {}
    for g in guides:
        where = f"guide '{g.get('id','')}' (\"{g.get('title','')}\")"
        for field in ("id", "title", "category", "summary"):
            if not str(g.get(field, "")).strip():
                problems.append(f"blank {field} on {where}")
        if not g.get("sections"):
            problems.append(f"{where} has zero sections")

        gid = g.get("id", "")
        if gid:
            prior = seen_ids.setdefault(gid, g.get("title", ""))
            if prior != g.get("title", ""):
                problems.append(f"duplicate id '{gid}' (\"{prior}\" vs \"{g.get('title','')}\")")

        category = g.get("category", "")
        if category and category not in known:
            problems.append(f"{where} has unrecognized category {category!r}")

        # The Kotlin-only safetyNote pair.
        note = g.get("safetyNote")
        if note is not None and not str(note).strip():
            problems.append(f"{where} has a blank safetyNote (omit the field instead of \"\")")
        if (
            category.startswith("Cooking — ")
            and category != "Cooking — Food Safety"
            and not str(note or "").strip()
        ):
            problems.append(f"{where} is a recipe (category {category!r}) but has no safetyNote")

        headings = [s.get("heading", "") for s in g.get("sections", [])]
        if len(headings) != len(set(headings)):
            dupes = {h for h in headings if headings.count(h) > 1}
            problems.append(f"{where} has duplicate section heading(s): {sorted(dupes)}")

        for s in g.get("sections", []):
            heading = s.get("heading", "")
            if not heading.strip():
                problems.append(f"{where} has a blank section heading")
            if not (s.get("body", "").strip() or s.get("ingredients") or s.get("steps")):
                problems.append(f"{where} section {heading!r} is completely empty")
            # The Kotlin-only empty/blank list pair.
            for key in ("ingredients", "steps"):
                value = s.get(key)
                if value is None:
                    continue
                if len(value) == 0:
                    problems.append(f"{where} section {heading!r} has an empty (non-null) {key} list")
                for i, line in enumerate(value):
                    if not str(line).strip():
                        problems.append(f"{where} section {heading!r} {key}[{i}] is blank")
            image = s.get("image")
            if image and image not in bundled_images:
                problems.append(f"{where} section {heading!r} references missing image {image!r}")

    unmapped = sorted(c for c in known if c not in supergroup_src)
    if unmapped:
        problems.append(f"categories with no GuideTaxonomy supergroup mapping: {unmapped}")

    # guide_index.json lockstep, including that `file` really contains the id.
    index_path = os.path.join(ASSETS, "guide_index.json")
    if not os.path.isfile(index_path):
        problems.append("guide_index.json missing — run tools/kb/kb_pipeline.py")
    else:
        entries = json.load(open(index_path, encoding="utf-8"))["entries"]
        if len(entries) != len(guides):
            problems.append(
                f"index entry count {len(entries)} != guide count {len(guides)} — re-run kb_pipeline.py"
            )
        by_id = {g["id"]: g for g in guides}
        for e in entries:
            g = by_id.get(e["id"])
            if g is None:
                problems.append(f"index entry '{e['id']}' has no matching guide")
                continue
            if (e["title"], e["category"], e["summary"]) != (g["title"], g["category"], g["summary"]):
                problems.append(f"index entry '{e['id']}' drifted (title/category/summary)")
            if e["headings"] != [s.get("heading", "") for s in g["sections"]]:
                problems.append(f"index entry '{e['id']}' headings drifted")
            shard = by_file.get(e.get("file", ""))
            if shard is None or not any(x["id"] == e["id"] for x in shard):
                problems.append(f"index entry '{e['id']}' points at {e.get('file')!r} which lacks it")

    full_pages = sum(1 for g in guides for s in g.get("sections", []) if words(s) >= FULL_PAGE_WORDS)
    baseline = int(
        re.search(r"FULL_PAGE_BASELINE\s*=\s*(\d+)", open(TEST_KT, encoding="utf-8").read()).group(1)
    )
    print(f"guides {len(guides)} · full pages {full_pages} · baseline {baseline}")
    if full_pages < baseline:
        problems.append(f"FULL_PAGE_BASELINE {baseline} > actual {full_pages} — CI will fail")
    elif full_pages > baseline:
        print(f"  -> RATCHET FULL_PAGE_BASELINE {baseline} -> {full_pages} in GuidesJsonValidationTest.kt")

    if problems:
        print(f"\n{len(problems)} PROBLEM(S):")
        for p in problems:
            print(f"  - {p}")
        sys.exit(1)
    print("CLEAN — the Kotlin test's assertions all hold")


if __name__ == "__main__":
    main()
