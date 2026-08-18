#!/usr/bin/env python3
"""
Find a freely-licensed diagram on Wikimedia Commons for guides that have none.

    tools/kb/source_images.py --limit 20                # dry run, prints what it would take
    tools/kb/source_images.py --limit 20 --apply        # fetch, encode, patch the shards

⚠️ **THE USER-AGENT IS NOT OPTIONAL AND IS NOT A COURTESY.** Wikimedia's API policy requires a
descriptive one, and without it the API answers **429** — which is exactly what was recorded in
this repo for two sessions as "Commons rate-limits this container's shared IP", blocking the whole
image task. It is not an IP block. With the User-Agent below, both the API and
``upload.wikimedia.org`` answer 200 first time.

⚠️ **THE RELEVANCE GATE IS THE POINT OF THIS SCRIPT, not the downloading.** Commons search always
returns *something*, and a plausible-looking but wrong diagram on a reference guide is worse than
no diagram at all: it implies instruction it does not give, and a reader in a hurry will trust it.
So a candidate is taken only when the **rarest word of the guide's title** appears in the file's
own title — the same idea as ``GuideSearch``'s rarity weighting, for the same reason. Guides where
nothing clears that bar are **skipped and reported**, never filled with the best of a bad set.

Licences: public domain, CC0, CC BY and CC BY-SA only. Anything else — including the
non-free/fair-use tags Commons does carry for some logos — is refused outright. Every accepted
file's licence, author and source page is appended to ``images/NOTICE.txt``.

Output format matches the existing corpus exactly (see ``optimize_images.py``): WebP, quality 85,
scaled down where wider than 1280. Nothing is cropped, recoloured or overlaid.
"""

from __future__ import annotations

import argparse
import io
import json
import glob
import os
import re
import sys
import time
import urllib.parse
import urllib.request
from collections import Counter

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ASSETS = os.path.join(ROOT, "app/src/main/assets/survival")
IMAGES = os.path.join(ASSETS, "images")
KB_IMAGES = os.path.join(IMAGES, "kb")
NOTICE = os.path.join(IMAGES, "NOTICE.txt")

API = "https://commons.wikimedia.org/w/api.php"
UA = (
    "LCARS-KB-Image-Sourcing/1.0 "
    "(https://github.com/mascwa200-beep/Thing; mascwa200@gmail.com) python-urllib"
)

# Matches optimize_images.py, which chose these by measuring re-encodes against the size the
# readers actually display. Do not diverge: the corpus should be one format at one quality.
MAX_WIDTH = 1280
WEBP_QUALITY = 85

# One request every PACE_S. Commons refuses a burst even from a well-identified client, so this is
# the steady state that keeps a long run alive rather than a politeness gesture.
PACE_S = 1.6
RATE_LIMIT_BACKOFF_S = 20.0

# Commons states the licence in several vocabularies. These are the ones that permit bundling in a
# sideloaded app with attribution. Everything else is refused — there is no "probably fine" here.
LICENCE_OK = re.compile(
    r"^(public domain|pd(-|$)|cc0|cc[- ]by([- ]sa)?([- ]\d(\.\d)?)?|"
    r"attribution|no restrictions)",
    re.I,
)
LICENCE_REFUSE = re.compile(r"fair use|non[- ]free|copyright|all rights", re.I)

# Words that carry no subject weight, so they must never be what makes a match look relevant.
STOP = set(
    """a an and are as at be by for from how in into is it its of on or that the their them then
    there these this to was were what when where which who why will with your you understanding
    basics guide introduction overview fundamentals principles work works working using use used
    make making about""".split()
)

# Categories whose subject is this project's own original fiction. Commons has real photographs of
# real actors, props and studio artwork under licences that do NOT extend to the underlying rights,
# and the lore arc's whole IP position is that no franchise artwork is bundled. Refused by name.
LORE_CATEGORIES = {"Starfleet & Federation", "Warp & Starship Technology",
                   "Species & Civilisations", "Worlds & Stations", "Notable Figures",
                   "Federation Timeline"}


def get(url: str, binary: bool = False, tries: int = 3):
    """One request, politely: a real User-Agent, a timeout, and a backoff that is not a burst."""
    for attempt in range(tries):
        time.sleep(PACE_S)
        req = urllib.request.Request(url, headers={"User-Agent": UA})
        try:
            with urllib.request.urlopen(req, timeout=40) as r:
                data = r.read()
            return data if binary else json.loads(data)
        except Exception as exc:  # noqa: BLE001 — any failure is just "try again, then give up"
            # ⚠️ A good User-Agent stops the FIRST request being refused; it does not buy a burst.
            # Measured live: three searches inside ~1.5 s earn a 429 regardless. So a rate-limit
            # answer backs off much harder than an ordinary failure, and PACE_S below keeps the
            # steady state under the limit rather than relying on this to catch it.
            code = getattr(exc, "code", None)
            if attempt == tries - 1:
                print(f"    ! {type(exc).__name__}: {str(exc)[:80]}", file=sys.stderr)
                return None
            time.sleep(RATE_LIMIT_BACKOFF_S if code == 429 else 2 ** attempt)
    return None


def load_corpus():
    """Every guide, its shard file, and whether it already carries a diagram."""
    guides = {}
    for path in sorted(glob.glob(os.path.join(ASSETS, "guides*.json"))):
        if os.path.basename(path) == "guide_index.json":
            continue
        doc = json.load(open(path, encoding="utf-8"))
        items = doc if isinstance(doc, list) else doc.get("guides", [])
        for g in items:
            g["_file"] = path
            guides[g["id"]] = g
    return guides


def title_terms(title: str) -> list[str]:
    """The title's own words, longest first — a proxy for rarest, and it needs no corpus pass."""
    words = [w for w in re.findall(r"[A-Za-z][A-Za-z'-]{2,}", title.lower()) if w not in STOP]
    return sorted(set(words), key=len, reverse=True)


# Words in a file's own name that say it was drawn to explain something, rather than photographed
# because someone was there. This is the difference between teaching and decorating.
DIAGRAM_WORDS = re.compile(
    r"\b(diagram|schematic|scheme|chart|cross[- ]?section|cutaway|labell?ed|anatomy|"
    r"illustration|figure|fig\b|plan|map of|graph|plot|structure|cycle|flow)\b",
    re.I,
)


def relevant(file_title: str, terms: list[str]) -> str | None:
    """
    The gate. Returns the term that justified the match, or None.

    Only the first few (longest, so most distinctive) title words count. Matching on a short common
    word is how "Making Soap" ends up illustrating a guide about burns.
    """
    low = file_title.lower()
    for term in terms[:4]:
        if len(term) >= 5 and term in low:
            return term
    return None


# Files whose subject is an organisation, a product or a person rather than the thing the guide is
# about. ⚠️ Every one of these was met in a real dry run: "New Zealand Breakers logo.svg" matched a
# guide on circuit breakers and is an SVG, so the drawing test passed it and only a size check
# happened to stop it. A word that names a subject also names teams, brands and places.
NOT_SUBJECT = re.compile(
    r"\b(logo|logotype|wordmark|coat of arms|crest|emblem|seal|flag|banner|"
    r"icon|favicon|stamp|postage|poster|cover|portrait|selfie|album|"
    r"jersey|kit|badge|trademark|signature)\b",
    re.I,
)


def teaches(file_title: str, mime: str) -> bool:
    """
    Whether this file is likely to *explain* the subject rather than merely depict it.

    ⚠️ **This is the judgement the relevance gate cannot make, and skipping it produces decoration.**
    Measured against the live index: searching "induction cooking" returns
    ``Induction Cooktop Rolling Boil.jpg`` first — a photograph of a pan of water, which passes any
    subject-word test and teaches a reader nothing whatsoever about how a magnetic field heats
    steel. Two signals separate the cases, and a candidate must carry one: it is a **drawing**
    (SVG, or Commons' "drawing" filetype), or its own filename says it is a diagram, a section, a
    chart or a labelled figure. Everything else is skipped, and skipping is the right outcome —
    a guide with no picture is honest, a guide with a decorative one is not.
    """
    if NOT_SUBJECT.search(file_title):
        return False
    if mime in ("image/svg+xml",):
        return True
    return bool(DIAGRAM_WORDS.search(file_title))


def strip_html(value) -> str:
    return re.sub(r"\s+", " ", re.sub(r"<[^>]+>", "", str(value or ""))).strip()


def search(query: str, limit: int = 12):
    url = (
        f"{API}?action=query&format=json&list=search&srnamespace=6"
        f"&srsearch={urllib.parse.quote(query)}&srlimit={limit}"
    )
    doc = get(url)
    return [r["title"] for r in (doc or {}).get("query", {}).get("search", [])]


def image_info(file_title: str):
    url = (
        f"{API}?action=query&format=json&prop=imageinfo"
        f"&iiprop=url|size|mime|extmetadata&iiurlwidth={MAX_WIDTH}"
        f"&titles={urllib.parse.quote(file_title)}"
    )
    doc = get(url)
    pages = (doc or {}).get("query", {}).get("pages", {})
    for page in pages.values():
        info = (page.get("imageinfo") or [None])[0]
        if info:
            return info
    return None


def acceptable(info) -> tuple[bool, str]:
    meta = info.get("extmetadata", {})
    licence = strip_html(meta.get("LicenseShortName", {}).get("value"))
    if not licence:
        return False, "no licence stated"
    if LICENCE_REFUSE.search(licence) and not LICENCE_OK.match(licence):
        return False, f"licence refused: {licence}"
    if not LICENCE_OK.match(licence):
        return False, f"licence not on the allowlist: {licence}"
    if info.get("width", 0) < 600:
        return False, f"too small: {info.get('width')}px"
    if not str(info.get("mime", "")).startswith("image/"):
        return False, f"not an image: {info.get('mime')}"
    return True, licence


def encode(raw: bytes) -> bytes | None:
    """Down to the corpus format: WebP q85, never wider than 1280. Nothing else is changed."""
    from PIL import Image

    try:
        img = Image.open(io.BytesIO(raw))
        img.load()
    except Exception:  # noqa: BLE001 — a saved error page named .png is the normal failure here
        return None
    if img.width > MAX_WIDTH:
        img = img.resize((MAX_WIDTH, round(img.height * MAX_WIDTH / img.width)), Image.LANCZOS)
    if img.mode not in ("RGB", "RGBA"):
        img = img.convert("RGBA" if "A" in img.getbands() else "RGB")
    out = io.BytesIO()
    img.save(out, "WEBP", quality=WEBP_QUALITY, method=6)
    return out.getvalue()


def pick_section(guide) -> int:
    """
    Which section gets the diagram: the first with no image and a body worth illustrating.

    The first section of a guide is its orientation, and a diagram there is the one most readers
    will actually see, so it is preferred over burying the picture eight screens down.
    """
    for i, sec in enumerate(guide.get("sections", [])):
        if not sec.get("image") and len(str(sec.get("body", ""))) > 300:
            return i
    return -1


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=20)
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--only", default="", help="restrict to one category")
    args = ap.parse_args()

    guides = load_corpus()
    todo = [
        g for g in guides.values()
        if not any(s.get("image") for s in g.get("sections", []))
        and g.get("category") not in LORE_CATEGORIES
        and (not args.only or g.get("category") == args.only)
    ]
    todo.sort(key=lambda g: (g.get("category", ""), g["id"]))
    print(f"{len(todo)} guides without a diagram (lore categories excluded)\n")

    taken, skipped = [], []
    for guide in todo[: args.limit]:
        terms = title_terms(guide["title"])
        print(f"  {guide['id']}  [{guide.get('category')}]  {guide['title']}")
        chosen = None
        # Two distinctive words beat the whole title: a full title behaves as a phrase and returns
        # scanned nineteenth-century cookbooks as PDFs. Drawings are asked for first because they
        # are the ones that explain.
        subject = " ".join(terms[:2]) or guide["title"]
        candidates = search(f"{subject} filetype:drawing") + search(f"{subject} filetype:bitmap")
        for candidate in candidates:
            why = relevant(candidate, terms)
            if not why:
                continue
            info = image_info(candidate)
            if not info:
                continue
            ok, note = acceptable(info)
            if not ok:
                print(f"      - {candidate[:60]}  ({note})")
                continue
            if not teaches(candidate, str(info.get("mime", ""))):
                print(f"      - {candidate[:60]}  (depicts, does not explain)")
                continue
            chosen = (candidate, info, note, why)
            break
        if not chosen:
            print("      SKIP — nothing cleared the relevance and licence gates")
            skipped.append(guide["id"])
            continue
        title, info, licence, why = chosen
        author = strip_html(info.get("extmetadata", {}).get("Artist", {}).get("value"))[:120]
        print(f"      ✓ {title[:60]}\n        matched on '{why}' · {licence} · {author[:50]}")
        taken.append((guide, title, info, licence, author))

    print(f"\n{len(taken)} matched, {len(skipped)} skipped")
    if not args.apply:
        print("dry run — pass --apply to fetch and patch")
        return 0

    os.makedirs(KB_IMAGES, exist_ok=True)
    touched_files, notice_lines = {}, []
    for guide, title, info, licence, author in taken:
        raw = get(info.get("thumburl") or info["url"], binary=True)
        if not raw:
            print(f"  ! download failed for {guide['id']}")
            continue
        data = encode(raw)
        if not data:
            print(f"  ! not decodable for {guide['id']}")
            continue
        name = f"{guide['id']}.webp"
        with open(os.path.join(KB_IMAGES, name), "wb") as fh:
            fh.write(data)
        idx = pick_section(guide)
        if idx < 0:
            print(f"  ! no section to attach to for {guide['id']}")
            os.remove(os.path.join(KB_IMAGES, name))
            continue
        guide["sections"][idx]["image"] = f"kb/{name}"
        touched_files.setdefault(guide["_file"], []).append(guide)
        page = "https://commons.wikimedia.org/wiki/" + urllib.parse.quote(title.replace(" ", "_"))
        notice_lines.append(f"kb/{name}\n    {title}\n    {licence} — {author or 'see source'}\n    {page}")
        print(f"  wrote kb/{name}  ({len(data)/1024:.0f} kB)")

    for path in touched_files:
        doc = json.load(open(path, encoding="utf-8"))
        items = doc if isinstance(doc, list) else doc.get("guides", [])
        by_id = {g["id"]: g for g in items}
        for guide in touched_files[path]:
            by_id[guide["id"]]["sections"] = guide["sections"]
        for g in items:
            g.pop("_file", None)
        with open(path, "w", encoding="utf-8") as fh:
            json.dump(doc, fh, ensure_ascii=False, indent=1)
            fh.write("\n")
        print(f"  patched {os.path.basename(path)}")

    if notice_lines:
        with open(NOTICE, "a", encoding="utf-8") as fh:
            fh.write("\n\nSourced from Wikimedia Commons (see the re-encoding notice above)\n")
            fh.write("-" * 62 + "\n")
            fh.write("\n".join(notice_lines) + "\n")
        print(f"  appended {len(notice_lines)} entries to NOTICE.txt")

    print(f"\nskipped, no acceptable match: {len(skipped)}")
    for gid in skipped:
        print("   ", gid)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
