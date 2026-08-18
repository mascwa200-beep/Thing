#!/usr/bin/env python3
"""
Find a freely-licensed, English-labelled, genuinely explanatory diagram for guides that have none.

    tools/kb/source_images.py --selftest              # replay the known-bad matches; must refuse all
    tools/kb/source_images.py --limit 20              # dry run, prints what it would take
    tools/kb/source_images.py --limit 20 --apply      # fetch, encode, patch the shards
    tools/kb/source_images.py --report out.json       # machine-readable record of what was chosen

────────────────────────────────────────────────────────────────────────────────────────────────
⚠️  THE USER-AGENT IS NOT OPTIONAL AND IS NOT A COURTESY
────────────────────────────────────────────────────────────────────────────────────────────────
Wikimedia's API policy requires a descriptive one and answers **429** without it — which is exactly
what this repo recorded for two sessions as "Commons rate-limits this container's shared IP",
blocking the whole image task. It is not an IP block. With the User-Agent below, both the API and
``upload.wikimedia.org`` answer 200 first time. A *burst* also earns a 429 independently (measured:
three requests inside ~1.5 s), so both effects are real and only one had been diagnosed; PACE_S is
the steady state that keeps a long run alive.

────────────────────────────────────────────────────────────────────────────────────────────────
⚠️  DO NOT SEARCH COMMONS BLIND — ASK WIKIPEDIA WHAT ILLUSTRATES THE SUBJECT
────────────────────────────────────────────────────────────────────────────────────────────────
The first version of this script searched Commons by the guide's title words. Measured on a real
150-guide run it accepted 72 files, of which roughly a third were wrong, e.g.::

    guide "opportunity cost"
      Commons search -> File:Rocher El Capitan ... spherules Opportunity.jpg   (a Mars rover)
      Wikipedia      -> article "Opportunity cost"
                        Comparative Advantage Example.png
                        Comparison of economic profit and accounting profit.png

An image used in a Wikipedia article is, by construction, *about that article's subject* — an
editor already did the relevance work. That is a far stronger signal than any string match on a
filename, so Wikipedia is the primary source and Commons search is the last resort.

────────────────────────────────────────────────────────────────────────────────────────────────
THE SIX GATES — every one written from an observed failure, not in anticipation
────────────────────────────────────────────────────────────────────────────────────────────────
1. LICENCE   PD / CC0 / CC BY / CC BY-SA only.
2. ENGLISH   11 of the first run's 72 accepted files were labelled in Hebrew, Russian, Kurdish,
             Bengali, Arabic, Vietnamese, Ukrainian or Japanese. A diagram captioned in Kurdish
             teaches an English reader nothing.
3. SUBJECT   >=2 words of the guide's own title/category/headings in the file NAME, or one word
             rare in the corpus. ⚠️ Strip the "File:" prefix and the extension first — without
             that, "Secondary Model.svg" passes on the word "file", which is how it got in.
4. TEACHES   A drawing, or a filename or Commons category that says diagram/schematic/chart/
             labelled/cross-section. Rejects logos, posters, portraits, clip art, mascots — a
             photograph of a boiling pan passes any subject test and explains no induction.
5. WATERMARK ⚠️ The obvious rule is backwards: Commons carries "Images which had their watermark
             removed", and those are the CLEAN ones.
6. PIXELS    Decodes, is not near-blank, carries enough distinct colour to be information, and is
             not an extreme sliver. A saved error page named .png is the normal silent failure.

SVG is preferred: simultaneously the highest quality (crisp at any size, usually the English
variant) and by far the smallest. Rasters match the corpus format exactly — WebP q85, capped at
1280 wide, per optimize_images.py. Nothing is cropped, recoloured or overlaid.
"""

from __future__ import annotations

import argparse
import collections
import glob
import io
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ASSETS = os.path.join(ROOT, "app/src/main/assets/survival")
IMAGES = os.path.join(ASSETS, "images")
KB_IMAGES = os.path.join(IMAGES, "kb")
NOTICE = os.path.join(IMAGES, "NOTICE.txt")

COMMONS = "https://commons.wikimedia.org/w/api.php"
WIKIPEDIA = "https://en.wikipedia.org/w/api.php"
UA = (
    "LCARS-KB-Image-Sourcing/1.0 "
    "(https://github.com/mascwa200-beep/Thing; mascwa200@gmail.com) python-urllib"
)

MAX_WIDTH = 1280           # what optimize_images.py measured as the right cap for both readers
WEBP_QUALITY = 85
MAX_SVG_BYTES = 400_000    # a pathological SVG can stall a phone renderer; a diagram never needs this
MIN_SOURCE_WIDTH = 600

# ⚠️ 1.6 s was not enough. A real 10-guide run hitting BOTH en.wikipedia and Commons still earned
# 429s, because the limit is per-client across the whole Wikimedia estate rather than per-host.
PACE_S = 2.6
RATE_LIMIT_BACKOFF_S = 45.0

# ── gate 1: licence ─────────────────────────────────────────────────────────────────────────────
LICENCE_OK = re.compile(
    r"^(public domain|pd(-|$)|cc0|cc[- ]by([- ]sa)?([- ]\d(\.\d)?)?|attribution|no restrictions)",
    re.I,
)
LICENCE_REFUSE = re.compile(r"fair use|non[- ]free|copyright|all rights", re.I)

# ── gate 2: English ─────────────────────────────────────────────────────────────────────────────
# Commons names a translated diagram with a language tag. These are the ones actually met in the
# first run plus the rest of the common set. "en" is deliberately ABSENT — it is what we want.
LANG_TAG = re.compile(
    r"[-_ ](ru|ar|he|iw|ku|vi|bn|zh|fa|uk|tr|pl|cs|el|hi|ta|ko|th|id|ja|nl|pt|es|fr|de|it|sv|fi|"
    r"da|no|nb|hu|ro|sr|hr|bg|sk|sl|lt|lv|et|ca|eu|gl|az|kk|uz|ka|hy|ne|si|my|km|lo|mn|ur|ps|sd|"
    r"am|sw|yo|ha|zu|ml|mr|te|kn|gu|pa|or|as|bo|ug|tg|ky|tk|be|mk|sq|is|ga|cy|gd|mt|af|jv|su|tl|"
    r"ms|ceb|war|pnb|arz|azb|bpy|nan|yue|wuu|hak|gan)([-_. ]|$)",
    re.I,
)
NON_LATIN = re.compile(r"[Ѐ-ӿ֐-׿؀-ۿऀ-ॿ一-鿿"
                       r"぀-ヿ가-힯฀-๿Ⴀ-ჿ԰-֏]")

# ── gate 3: subject ─────────────────────────────────────────────────────────────────────────────
STOP = set(
    """a an and are as at be by for from how in into is it its of on or that the their them then
    there these this to was were what when where which who why will with your you understanding
    basics guide introduction overview fundamentals principles work works working using use used
    make making about general common simple basic new old first second file svg png jpg jpeg webp
    image images picture photo""".split()
)
RARE_DF = 25               # a single matching word may carry a match only if it is this rare

# ── gate 4: teaches, not depicts ────────────────────────────────────────────────────────────────
DIAGRAM_WORDS = re.compile(
    r"\b(diagram|schematic|scheme|chart|cross[- ]?section|cutaway|labell?ed|anatomy|illustration|"
    r"figure|fig\b|graph|plot|structure|cycle|flow|map of|profile|timeline|comparison|process)\b",
    re.I,
)
DIAGRAM_CATEGORIES = re.compile(
    r"(diagram|schematic|chart|illustration|cross.?section|annotations|graph|infographic|"
    r"svg (drawing|diagram)|line art|technical drawing)",
    re.I,
)
NOT_SUBJECT = re.compile(
    r"\b(logo|logotype|wordmark|coat of arms|crest|emblem|seal|flag|banner|icon|favicon|stamp|"
    r"postage|poster|cover|portrait|selfie|album|jersey|badge|trademark|signature|cartoon|"
    r"clip.?art|wordart|fan.?art|mascot|meme|screenshot|barnstar|userbox)\b",
    re.I,
)
# Wikipedia article chrome: maintenance banners, portal icons, licence marks. Never the subject.
WIKI_CHROME = re.compile(
    r"(commons[- ]logo|wiki[a-z]*[- ]logo|wiki letter|wikipedia|wiktionary|wikiquote|wikisource|"
    r"wikidata|wikibooks|wikinews|wikiversity|wikivoyage|question[ _]book|stub|sound-icon|"
    r"ambox|imbox|edit-|padlock|lock-|symbol_|nuvola|crystal|oojs|portal|folder_|disambig|"
    r"merge-|split-|text_document|magnify-clip|red_pencil|emblem-|increase2?\.svg|decrease2?\.svg|"
    r"steady2?\.svg|yes_check|x_mark|star_(full|empty|half))",
    re.I,
)

# ── gate 5: watermarks ──────────────────────────────────────────────────────────────────────────
WATERMARK_CAT = re.compile(r"watermark", re.I)
WATERMARK_CLEARED = re.compile(r"removed|without|free of|no watermark", re.I)

# Categories whose subject is this project's own original fiction. Commons holds photographs of real
# actors, props and studio artwork under licences that do NOT extend to the underlying rights, and
# the lore arc's position is that no franchise artwork is bundled. Refused by name.
LORE_CATEGORIES = {"Starfleet & Federation", "Warp & Starship Technology",
                   "Species & Civilisations", "Worlds & Stations", "Notable Figures",
                   "Federation Timeline"}


# ════════════════════════════════════════════════════════════════════════════════════════════════
# transport
# ════════════════════════════════════════════════════════════════════════════════════════════════

def get(url: str, binary: bool = False, tries: int = 3):
    """One paced request. A rate-limit answer backs off far harder than an ordinary failure."""
    for attempt in range(tries):
        time.sleep(PACE_S)
        req = urllib.request.Request(url, headers={"User-Agent": UA})
        try:
            with urllib.request.urlopen(req, timeout=45) as r:
                data = r.read()
            return data if binary else json.loads(data)
        except Exception as exc:  # noqa: BLE001 — every failure here means "try again, then skip"
            code = getattr(exc, "code", None)
            if attempt == tries - 1:
                print(f"      ! {type(exc).__name__}: {str(exc)[:70]}", file=sys.stderr)
                return None
            time.sleep(RATE_LIMIT_BACKOFF_S if code == 429 else 2 ** attempt)
    return None


def api(base: str, **params) -> dict | None:
    params.setdefault("action", "query")
    params.setdefault("format", "json")
    return get(base + "?" + urllib.parse.urlencode(params))


# ════════════════════════════════════════════════════════════════════════════════════════════════
# corpus
# ════════════════════════════════════════════════════════════════════════════════════════════════

def load_corpus():
    """Every guide with its shard path, plus the body-frequency table the subject gate needs."""
    guides, freq = {}, collections.Counter()
    for path in sorted(glob.glob(os.path.join(ASSETS, "guides*.json"))):
        if os.path.basename(path) == "guide_index.json":
            continue
        doc = json.load(open(path, encoding="utf-8"))
        items = doc if isinstance(doc, list) else doc.get("guides", [])
        for g in items:
            g["_file"] = path
            guides[g["id"]] = g
            body = " ".join(str(s.get("body", "")) for s in g.get("sections", [])).lower()
            for w in set(re.findall(r"[a-z][a-z'-]{2,}", body)):
                freq[w] += 1
    return guides, freq


def vocabulary(guide) -> set[str]:
    """
    Every word that legitimately describes this guide: its title, its category, its headings.

    Using all three is what separates a soil guide (which owns *soil*, *texture* and *triangle*)
    from a Mars photograph that shares only the single generic word *opportunity*.
    """
    text = " ".join([
        guide.get("title", ""),
        guide.get("category", ""),
        " ".join(str(s.get("heading", "")) for s in guide.get("sections", [])),
    ])
    return {w for w in re.findall(r"[a-z][a-z'-]{3,}", text.lower()) if w not in STOP}


def subject_query(guide) -> str:
    """What to ask Wikipedia. The title, minus the scaffolding words that carry no subject."""
    words = [w for w in re.findall(r"[A-Za-z][A-Za-z'-]{2,}", guide.get("title", ""))
             if w.lower() not in STOP]
    return " ".join(words[:6]) or guide.get("title", "")


# ════════════════════════════════════════════════════════════════════════════════════════════════
# the six gates
# ════════════════════════════════════════════════════════════════════════════════════════════════

def file_stem(file_title: str) -> str:
    """
    "File:Foo bar.svg" -> "foo bar".

    ⚠️ Both halves matter. Leaving the namespace prefix on lets the literal word "file" satisfy the
    subject gate, which is exactly how "Secondary Model.svg" was accepted; leaving the extension on
    lets "svg"/"png" do the same.
    """
    stem = file_title.split(":", 1)[1] if ":" in file_title else file_title
    return re.sub(r"\.[A-Za-z0-9]{2,4}$", "", stem).replace("_", " ").lower()


def is_english(file_title: str) -> bool:
    """Gate 2. A translated diagram is a good diagram that this reader cannot read."""
    stem = file_stem(file_title)
    return not (LANG_TAG.search(stem) or NON_LATIN.search(stem))


def subject_hits(file_title: str, vocab: set[str], freq: collections.Counter) -> list[str]:
    stem = file_stem(file_title)
    return sorted({w for w in vocab if len(w) >= 4 and w in stem})


def on_subject(file_title: str, vocab: set[str], freq: collections.Counter,
               source: str = "commons") -> bool:
    """
    Gate 3. Two ordinary words of the guide's own vocabulary, or one genuinely rare one.

    ⚠️ **Deliberately NOT applied to Wikipedia-sourced candidates, and the selftest is what proved
    it must not be.** A file used in the English Wikipedia article on a subject has already had its
    relevance established by an editor; re-deriving that from the filename only throws good
    diagrams away. `File:Osmose en.svg` IS the osmosis diagram on the osmosis article, and no
    string test relates "osmose" to "osmosis" — the file is named for the French and German root.
    Filename matching is a safety net for the blind Commons paths, not a second opinion on
    Wikipedia's.
    """
    if source == "wikipedia":
        return True
    hits = subject_hits(file_title, vocab, freq)
    if len(hits) >= 2:
        return True
    return len(hits) == 1 and freq.get(hits[0], 10 ** 6) < RARE_DF


def teaches(file_title: str, mime: str, categories: list[str], source: str = "commons") -> bool:
    """
    Gate 4: does it EXPLAIN the subject, or merely decorate the page?

    A blind Commons search for "induction cooking" returns a photograph of a pan of boiling water
    first. It passes any subject test and teaches a reader nothing about how a magnetic field heats
    steel — so for the blind sources the file must be a *drawing*: an SVG, a filename that says
    diagram/schematic/chart/labelled, or a Commons category that says so (`Category:Knot diagrams`
    is an editor stating outright that the file is a diagram, and is the strongest signal here).

    ⚠️ **Photographs are allowed from the Wikipedia path, and refusing them there was measured to
    be wrong.** A live run with the strict rule skipped *Reading and Calibrating Kitchen
    Thermometers* even though the article on infrared thermometers offers a clear photograph of one
    in use — which teaches the subject perfectly well. The distinction that matters is not
    photograph-versus-drawing, it is whether anyone with judgement decided this picture belongs to
    this subject. On the article path somebody did; on a search path nobody did.

    What is refused everywhere: logos, crests, posters, portraits, clip art, mascots, and the
    maintenance icons Wikipedia hangs on its own articles. Those are never the subject.
    """
    if NOT_SUBJECT.search(file_stem(file_title)) or WIKI_CHROME.search(file_title):
        return False
    if source == "wikipedia":
        return True
    if any(DIAGRAM_CATEGORIES.search(c) for c in categories):
        return True
    if mime == "image/svg+xml":
        return True
    return bool(DIAGRAM_WORDS.search(file_stem(file_title)))


def watermarked(categories: list[str]) -> bool:
    """
    Gate 5. ⚠️ Backwards-looking rule, deliberately.

    Commons carries "Images which had their watermark removed" — files in that category are the
    CLEAN ones, and a naive /watermark/ match would reject precisely the set that was fixed.
    """
    for c in categories:
        if WATERMARK_CAT.search(c) and not WATERMARK_CLEARED.search(c):
            return True
    return False


def pixels_ok(raw: bytes, mime: str) -> tuple[bool, str]:
    """
    Gate 6. Does the file actually contain a legible picture?

    An SVG is vector and is checked by size and well-formedness instead — rasterising it here would
    need a renderer this container has no reason to carry.
    """
    if mime == "image/svg+xml":
        if len(raw) > MAX_SVG_BYTES:
            return False, f"svg too heavy: {len(raw)//1024} kB"
        head = raw[:4096].lstrip()
        if not (head.startswith(b"<?xml") or head.startswith(b"<svg") or b"<svg" in raw[:4096]):
            return False, "not an svg"
        return True, "svg"
    from PIL import Image
    try:
        img = Image.open(io.BytesIO(raw))
        img.load()
    except Exception:  # noqa: BLE001 — a saved error page named .png is the normal silent failure
        return False, "does not decode"
    w, h = img.size
    if w < MIN_SOURCE_WIDTH:
        return False, f"too small: {w}px"
    if not 0.15 <= (w / max(h, 1)) <= 8.0:
        return False, f"extreme aspect {w}x{h}"
    small = img.convert("RGB").resize((64, 64))
    colours = len(set(small.getdata()))
    if colours < 12:
        return False, f"near-blank: {colours} distinct colours"
    return True, f"{w}x{h}, {colours} colours"


def acceptable_licence(info) -> tuple[bool, str]:
    meta = info.get("extmetadata", {})
    licence = strip_html(meta.get("LicenseShortName", {}).get("value"))
    if not licence:
        return False, "no licence stated"
    if LICENCE_REFUSE.search(licence) and not LICENCE_OK.match(licence):
        return False, f"licence refused: {licence}"
    if not LICENCE_OK.match(licence):
        return False, f"licence not allowed: {licence}"
    return True, licence


def strip_html(value) -> str:
    return re.sub(r"\s+", " ", re.sub(r"<[^>]+>", "", str(value or ""))).strip()


# ════════════════════════════════════════════════════════════════════════════════════════════════
# candidate sources — best first
# ════════════════════════════════════════════════════════════════════════════════════════════════

def wikipedia_candidates(query: str, vocab: set[str], freq: collections.Counter,
                         limit: int = 24) -> tuple[list[str], str]:
    """
    The primary source: files used by the English Wikipedia article on this subject.

    ⚠️ **The article must be checked before its images are trusted, and skipping that check is a
    silent, total failure.** Because Wikipedia-sourced candidates bypass the filename subject gate
    — an editor's judgement beats a string match — the ARTICLE becomes the only thing standing
    between a guide and an arbitrary picture. A live run proved it: searching for
    *"Carryover Cooking Resting Meat"* returned the article **Skeletal system**, and every image on
    it sailed through every remaining gate. The article's own title must therefore share the
    guide's vocabulary, exactly as a filename would have to.

    Several results are fetched rather than one, so a poor first hit does not doom the guide.
    """
    doc = api(WIKIPEDIA, generator="search", gsrsearch=query, gsrlimit=3,
              prop="images", imlimit="max")
    best, best_hits, best_title = [], 0, ""
    for page in (doc or {}).get("query", {}).get("pages", {}).values():
        article = page.get("title", "")
        hits = {w for w in vocab if len(w) >= 4 and w in article.lower()}
        rare = any(freq.get(h, 10 ** 6) < RARE_DF for h in hits)
        if not (len(hits) >= 2 or rare or (len(hits) == 1 and len(vocab) <= 3)):
            continue                       # this article is not about the guide's subject
        images = [im["title"] for im in page.get("images", [])
                  if not WIKI_CHROME.search(im["title"])]
        if images and len(hits) > best_hits:
            best, best_hits, best_title = images[:limit], len(hits), article
    return best, best_title


def commons_category_candidates(seed_categories: list[str], limit: int = 12) -> list[str]:
    """Siblings of a good file: the other diagrams an editor filed in the same category."""
    for cat in seed_categories:
        if not DIAGRAM_CATEGORIES.search(cat):
            continue
        doc = api(COMMONS, list="categorymembers", cmtitle=cat, cmtype="file", cmlimit=limit)
        members = [m["title"] for m in (doc or {}).get("query", {}).get("categorymembers", [])]
        if members:
            return members
    return []


def commons_search_candidates(query: str, limit: int = 10) -> list[str]:
    """Last resort. This is the path that produced the Mars rover; it is no longer the only one."""
    out = []
    for filt in ("filetype:drawing", "filetype:bitmap"):
        doc = api(COMMONS, list="search", srnamespace=6, srsearch=f"{query} {filt}", srlimit=limit)
        out += [r["title"] for r in (doc or {}).get("query", {}).get("search", [])]
    return out


def image_details(file_titles: list[str]) -> dict:
    """
    imageinfo AND categories for up to etitles in ONE request.

    The MediaWiki API takes up to 50 titles at a time, which turns a per-candidate round trip into
    a single one — the difference between a run of hours and a run of days.
    """
    out = {}
    for i in range(0, len(file_titles), 25):
        batch = file_titles[i:i + 25]
        doc = api(COMMONS, prop="imageinfo|categories", cllimit="max",
                  iiprop="url|size|mime|extmetadata", iiurlwidth=MAX_WIDTH,
                  titles="|".join(batch))
        for page in (doc or {}).get("query", {}).get("pages", {}).values():
            info = (page.get("imageinfo") or [None])[0]
            if info:
                out[page["title"]] = (info, [c["title"] for c in page.get("categories", [])])
    return out


# ════════════════════════════════════════════════════════════════════════════════════════════════
# choosing
# ════════════════════════════════════════════════════════════════════════════════════════════════

def score(file_title: str, info, categories, vocab, freq, source_rank: int) -> int:
    """
    Higher is better. Only reached by candidates that already passed every gate, so this is about
    picking the BEST survivor rather than about safety.
    """
    s = 100 - source_rank * 20                                    # Wikipedia beats category beats search
    s += 12 * len(subject_hits(file_title, vocab, freq))          # more of the subject named
    if info.get("mime") == "image/svg+xml":
        s += 25                                                   # crisp at any size, and tiny
    if any(DIAGRAM_CATEGORIES.search(c) for c in categories):
        s += 20                                                   # an editor called it a diagram
    if DIAGRAM_WORDS.search(file_stem(file_title)):
        s += 10
    if re.search(r"[-_ ]en([-_. ]|$)", file_stem(file_title)):
        s += 8                                                    # the explicitly-English variant
    return s


def choose(guide, freq, verbose=True):
    """Walk the source ladder; return the best gate-passing candidate, or None."""
    vocab = vocabulary(guide)
    query = subject_query(guide)
    wiki_titles, article = wikipedia_candidates(query, vocab, freq)
    if verbose and article:
        print(f"      · article: {article}")
    ladder = [("wikipedia", wiki_titles)]
    best = None
    seen_cats: list[str] = []

    for rank, (source, titles) in enumerate(ladder):
        titles = [t for t in titles if is_english(t) and on_subject(t, vocab, freq, source)]
        if not titles:
            continue
        for title, (info, cats) in image_details(titles).items():
            seen_cats = cats or seen_cats
            ok, licence = acceptable_licence(info)
            if not ok or watermarked(cats) or not teaches(title, str(info.get("mime", "")), cats, source):
                continue
            if info.get("width", 0) < MIN_SOURCE_WIDTH and info.get("mime") != "image/svg+xml":
                continue
            cand = (score(title, info, cats, vocab, freq, rank), title, info, cats, licence, source)
            if best is None or cand[0] > best[0]:
                best = cand
        if best:
            return best

    # Fall through: siblings of whatever the article gave, then a blind search.
    for rank, (source, titles) in enumerate(
        [("commons-category", commons_category_candidates(seen_cats)),
         ("commons-search", commons_search_candidates(query))], start=1
    ):
        titles = [t for t in titles if is_english(t) and on_subject(t, vocab, freq, source)]
        if not titles:
            continue
        for title, (info, cats) in image_details(titles).items():
            ok, licence = acceptable_licence(info)
            if not ok or watermarked(cats) or not teaches(title, str(info.get("mime", "")), cats, source):
                continue
            if info.get("width", 0) < MIN_SOURCE_WIDTH and info.get("mime") != "image/svg+xml":
                continue
            cand = (score(title, info, cats, vocab, freq, rank), title, info, cats, licence, source)
            if best is None or cand[0] > best[0]:
                best = cand
        if best:
            return best
    return None


# ════════════════════════════════════════════════════════════════════════════════════════════════
# fetch and write
# ════════════════════════════════════════════════════════════════════════════════════════════════

def encode(raw: bytes) -> bytes | None:
    """To the corpus format: WebP q85, never wider than 1280. Nothing else is changed."""
    from PIL import Image
    try:
        img = Image.open(io.BytesIO(raw))
        img.load()
    except Exception:  # noqa: BLE001
        return None
    if img.width > MAX_WIDTH:
        img = img.resize((MAX_WIDTH, round(img.height * MAX_WIDTH / img.width)), Image.LANCZOS)
    if img.mode not in ("RGB", "RGBA"):
        img = img.convert("RGBA" if "A" in img.getbands() else "RGB")
    out = io.BytesIO()
    img.save(out, "WEBP", quality=WEBP_QUALITY, method=6)
    return out.getvalue()


def pick_section(guide) -> int:
    """The first section with no image and a body worth illustrating — where readers actually look."""
    for i, sec in enumerate(guide.get("sections", [])):
        if not sec.get("image") and len(str(sec.get("body", ""))) > 300:
            return i
    return -1


def selftest() -> int:
    """
    Replay the matches the first run got WRONG. Every one must now be refused.

    ⚠️ These are real files that a real run really accepted, not invented fixtures — which is the
    only kind of regression test worth having here.
    """
    freq = collections.Counter({"opportunity": 66, "research": 223, "legacy": 51, "secondary": 156,
                                "memory": 254, "building": 329, "relative": 361, "computer": 96,
                                "thermoluminescence": 7})
    cases = [
        # (file, guide vocabulary, mime, categories, source, must_pass, why)
        # ── every one of these was really accepted by the first run and is really wrong ──
        ("File:Rocher El Capitan gros plan avec spherules Opportunity.jpg",
         {"opportunity", "cost", "scarcity"}, "image/jpeg", [], "commons-search", False,
         "a Mars rover, on opportunity cost"),
        ("File:Space Jam; A New Legacy (Print).svg",
         {"legacy", "systems"}, "image/svg+xml", [], "commons-search", False, "a film poster"),
        ("File:Acceptance of Homosexuality Worldwide (Pew Research Poll).svg",
         {"research", "sources"}, "image/svg+xml", [], "commons-search", False, "unrelated poll"),
        ("File:Secondary Model.svg",
         {"secondary", "research"}, "image/svg+xml", [], "commons-search", False,
         "was accepted on the word 'file'"),
        ("File:Triumphant Cartoon Woman Using A Computer At Home.svg",
         {"computer", "history"}, "image/svg+xml", [], "commons-search", False, "clip art"),
        ("File:Protein synthesis ku.svg",
         {"protein", "synthesis"}, "image/svg+xml", [], "wikipedia", False, "Kurdish"),
        ("File:Radiocarbon dating calibration-HE.svg",
         {"radiocarbon", "dating"}, "image/svg+xml", [], "wikipedia", False, "Hebrew"),
        ("File:NuclearPore crop-bn.svg",
         {"nuclear", "pore"}, "image/svg+xml", [], "wikipedia", False, "Bengali"),
        ("File:New Zealand Breakers logo.svg",
         {"breakers", "relays"}, "image/svg+xml", [], "commons-search", False, "a basketball team"),
        ("File:Watermarked chart of something.png",
         {"chart", "something"}, "image/png", ["Category:Images with watermarks"], "wikipedia",
         False, "carries a watermark"),
        # ── and these MUST pass, so the gates are not simply refusing everything ──
        ("File:Osmose en.svg",
         {"osmosis", "osmotic"}, "image/svg+xml", [], "wikipedia", True,
         "the osmosis diagram ON the osmosis article — no string relates 'osmose' to 'osmosis'"),
        ("File:Triangle texture soil.svg",
         {"soil", "texture", "triangle"}, "image/svg+xml", [], "commons-search", True,
         "two subject words even from a blind search"),
        ("File:0313 Endoplasmic Reticulum c labeled.png",
         {"endoplasmic", "reticulum"}, "image/png", ["Category:Cell diagrams"], "wikipedia", True,
         "labelled, and an editor filed it under diagrams"),
        ("File:Knot bowline.svg",
         {"knot", "bowline"}, "image/svg+xml", ["Category:Images which had their watermark removed"],
         "wikipedia", True, "watermark REMOVED — the clean set, must not be refused"),
        # ⚠️ Added because negative-testing showed two gates were never the deciding one in any
        # case above, so deleting either changed nothing and the test proved nothing about them.
        ("File:Solar power diagram.svg",
         {"solar", "power", "diagram"}, "image/svg+xml", [], "commons-search", True,
         "control for the pair below — same shape, but not a logo"),
        ("File:Solar Power logo.svg",
         {"solar", "power"}, "image/svg+xml", [], "commons-search", False,
         "TWO subject words, so only the not-subject list can refuse it"),
        # ⚠️ The pair below pins the source-awareness of the teaches gate in BOTH directions —
        # a bare photograph is refused from a blind search and accepted from the article that
        # chose it. Without the second, making teaches() unconditional would break nothing.
        ("File:Infrared thermometer in use.jpg",
         {"thermometer", "infrared", "calibrating"}, "image/jpeg", [], "commons-search", False,
         "a photograph from a blind search — nobody decided it belongs to this subject"),
        ("File:Infrared thermometer in use.jpg",
         {"thermometer", "infrared", "calibrating"}, "image/jpeg", [], "wikipedia", True,
         "the same photograph, taken from the article on the subject — an editor placed it there"),
        # ⚠️ The three below are shaped precisely so that ONE guard decides each. Written after
        # negative-testing showed the earlier cases all passed with the guard deleted, which means
        # they proved nothing about it. A test that cannot fail is worse than no test.
        ("File:Format.svg",
         {"file", "format"}, "image/svg+xml", [], "commons-search", False,
         "one hit only — but leaving the 'File:' prefix on would supply a second and accept it"),
        ("File:Raster.webp",
         {"raster", "webp"}, "image/webp", ["Category:Diagrams"], "commons-search", False,
         "one hit only — but leaving the '.webp' extension on would supply a second. ⚠️ Uses a "
         "four-letter extension deliberately: subject_hits ignores words under four characters, "
         "so an '.svg' version of this case can never fail. The Diagrams category is there so "
         "the teaches gate passes and the subject gate is genuinely the one deciding"),
        ("File:Thermoluminescence curves.svg",
         {"thermoluminescence"}, "image/svg+xml", [], "commons-search", True,
         "a single hit, but the word is rare enough in the corpus to carry the match alone"),
    ]
    bad = 0
    for title, vocab, mime, cats, source, must_pass, why in cases:
        passed = (is_english(title)
                  and on_subject(title, vocab, freq, source)
                  and teaches(title, mime, cats, source)
                  and not watermarked(cats))
        ok = passed == must_pass
        bad += 0 if ok else 1
        print(f"  {'ok  ' if ok else 'FAIL'} {'accept' if passed else 'refuse':<6} "
              f"(want {'accept' if must_pass else 'refuse'})  {title[:52]:<52} {why}")
    print("\nall gates behave as intended" if not bad else f"\n{bad} GATE(S) WRONG")
    return 1 if bad else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=20)
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--only", default="")
    ap.add_argument("--report", default="")
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()

    if args.selftest:
        return selftest()

    guides, freq = load_corpus()
    todo = [g for g in guides.values()
            if not any(s.get("image") for s in g.get("sections", []))
            and g.get("category") not in LORE_CATEGORIES
            and (not args.only or g.get("category") == args.only)]
    todo.sort(key=lambda g: (g.get("category", ""), g["id"]))
    print(f"{len(todo)} guides without a diagram (lore categories excluded)\n")

    taken, skipped = [], []
    for guide in todo[: args.limit]:
        print(f"  {guide['id']}  [{guide.get('category')}]")
        best = choose(guide, freq)
        if not best:
            print("      SKIP — nothing cleared the gates")
            skipped.append(guide["id"])
            continue
        sc, title, info, cats, licence, source = best
        author = strip_html(info.get("extmetadata", {}).get("Artist", {}).get("value"))[:100]
        print(f"      ✓ [{source} {sc}] {title[:56]}\n        {licence} · {author[:44]}")
        taken.append((guide, title, info, cats, licence, author, source, sc))

    print(f"\n{len(taken)} matched, {len(skipped)} skipped")
    if args.report:
        json.dump([{"guide": g["id"], "title": g["title"], "file": t, "licence": l,
                    "author": a, "source": s, "score": sc}
                   for g, t, i, c, l, a, s, sc in taken], open(args.report, "w"), indent=1)
        print(f"report -> {args.report}")
    if not args.apply:
        print("dry run — pass --apply to fetch and patch")
        return 0

    os.makedirs(KB_IMAGES, exist_ok=True)
    touched, notice_lines = {}, []
    for guide, title, info, cats, licence, author, source, sc in taken:
        mime = str(info.get("mime", ""))
        svg = mime == "image/svg+xml"
        raw = get(info["url"] if svg else (info.get("thumburl") or info["url"]), binary=True)
        if not raw:
            print(f"  ! download failed: {guide['id']}")
            continue
        ok, note = pixels_ok(raw, mime)
        if not ok:
            print(f"  ! {guide['id']}: {note}")
            continue
        data = raw if svg else encode(raw)
        if not data:
            print(f"  ! not encodable: {guide['id']}")
            continue
        name = f"{guide['id']}.{'svg' if svg else 'webp'}"
        idx = pick_section(guide)
        if idx < 0:
            print(f"  ! no section to attach to: {guide['id']}")
            continue
        with open(os.path.join(KB_IMAGES, name), "wb") as fh:
            fh.write(data)
        guide["sections"][idx]["image"] = f"kb/{name}"
        touched.setdefault(guide["_file"], []).append(guide)
        page = "https://commons.wikimedia.org/wiki/" + urllib.parse.quote(title.replace(" ", "_"))
        notice_lines.append(f"kb/{name}\n    {title}\n    {licence} — {author or 'see source'}\n    {page}")
        print(f"  wrote kb/{name}  ({len(data)/1024:.0f} kB, {note})")

    for path, changed in touched.items():
        doc = json.load(open(path, encoding="utf-8"))
        items = doc if isinstance(doc, list) else doc.get("guides", [])
        by_id = {g["id"]: g for g in items}
        for guide in changed:
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
            fh.write("-" * 62 + "\n" + "\n".join(notice_lines) + "\n")
        print(f"  appended {len(notice_lines)} entries to NOTICE.txt")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
