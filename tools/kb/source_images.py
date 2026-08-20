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
# ⚠️ Above this, do not even try to render it — and the number comes from timing cairosvg on the
# shape that actually causes trouble (one enormous path, as in a country outline), NOT from a guess:
#
#     500 kB -> 3.0 s     1 MB -> 14.0 s     2 MB -> 68.8 s
#
# Super-linear, so a first instinct of 2 MB was far too GENEROUS: one such file would cost more than
# a minute, and across a 169-guide pass that is hours. 1.2 MB caps the worst case near 18 s, against
# the ~2.6 s every request already pays. It admits the largest genuinely useful discard (the 1.1 MB
# metric/imperial chart) and refuses the 2.8 MB LocationBurkinaFaso.svg — which is a real correction
# to what this comment first claimed: that file was NOT going to fail the relevance gates, it passed
# them and was chosen. It is refused on cost, and because the same timing run showed the rendered
# PNG shrinking as the vector grows (1144 kB -> 282 kB -> 30 kB), a path that dense averages out to
# near-flat colour at 1280 px and would be caught by the near-blank check anyway.
MAX_SVG_RENDER_BYTES = 1_200_000
MIN_SOURCE_WIDTH = 600

# The vector counterpart of MIN_SOURCE_WIDTH — see pixels_ok for the measurement that placed it.
# A vector's canvas is arbitrary, so what is counted is how much is actually drawn.
MIN_SVG_ELEMENTS = 6
MIN_SVG_BYTES = 1_000     # ...unless it is byte-rich instead; see pixels_ok for both measurements
SVG_ELEMENT_RE = re.compile(
    r"<(?:path|circle|rect|line|polyline|polygon|ellipse|text|image|use)\b", re.I
)

# ⚠️ **The same two numbers live in three places, in two languages, and only one of them decides
# what actually gets written.** This module bundles the file; `BundledImagesTest` (app) and
# `BundledSvgDiagramsParseTest` (desktop) refuse it. They agree today — 1280 and 400_000 on all
# three — but nothing made them agree, so a wave could bundle 300 diagrams over two hours and only
# find out from CI, long after the run that produced them is gone. A duplicated definition is a
# mistake this repository has now corrected in six places.
#
# There is no shared home for a constant spanning Python and two Gradle modules, so the sourcer
# reads the gate's own source instead and refuses to start on a mismatch — the same trick
# `ci_parity_lint.py` uses to keep its category allowlist honest. Best-effort by design: a moved
# or renamed test must not stop a wave, it just stops being cross-checked, and says so.
GATE_TEST = "app/src/test/java/dev/mascwa/pulse/data/survival/BundledImagesTest.kt"


def check_gate_parity() -> None:
    path = os.path.join(ROOT, GATE_TEST)
    if not os.path.exists(path):
        print(f"  ⚠️ cannot cross-check size limits — {GATE_TEST} not found")
        return
    src = open(path, encoding="utf-8").read()
    for name, ours in (("MAX_DISPLAY_PX", MAX_WIDTH), ("MAX_SVG_BYTES", MAX_SVG_BYTES)):
        m = re.search(rf"\b{name}\s*=\s*([\d_]+)", src)
        if not m:
            print(f"  ⚠️ cannot cross-check {name} — not found in the gate")
            continue
        theirs = int(m.group(1).replace("_", ""))
        if theirs != ours:
            raise SystemExit(
                f"size limits disagree: this sourcer would bundle up to {ours}, "
                f"but {name} in the build gate is {theirs}. Every diagram this wave "
                f"writes would fail CI. Reconcile them before running."
            )
FLUSH_EVERY = 1            # diagrams that may sit on disk unreferenced at once — see flush()
# ⚠️ 1, not a batch. The batching was justified as "that would rewrite a half-megabyte shard for
# every diagram" — which is true and costs nothing: a shard rewrite is milliseconds against the
# ~2.6 s of paced API calls each diagram already takes, so 363 of them add well under a minute to
# a run measured in hours. Measured instead of assumed: a hard kill mid-run at FLUSH_EVERY=10 left
# 2 orphan images behind. At 1, with the atomic replace in flush(), it leaves none.

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
# ⚠️ **Word boundaries, and a real run proved every one of them necessary.** Written without them
# this pattern fires inside the strongest words in the language for NOT a diagram: a bare `graph`
# matches "photographs", "photography", "photographic" and "stratigraphy", and a bare `chart`
# matches "Charters" and "Uncharted". Measured against the categories those files really carry,
# the unbounded form accepted `2012 photographs of Malta`, `Earth photography during STS-39`,
# `Non-photographic images by Hans Hillewaert` and `Archaeological stratigraphy` — 10 of 23 probe
# cases wrong, against 0 bounded — and it put an hourglass photograph on relative dating and an
# aurora photographed from the shuttle on aerial archaeology, each with this as its only evidence.
DIAGRAM_CATEGORIES = re.compile(
    r"(\bdiagrams?\b|\bschematics?\b|\bcharts?\b|\billustrations?\b|cross.?section|"
    r"\bannotations?\b|\bgraphs?\b|\binfographics?\b|svg (drawing|diagram)|"
    r"\bline art\b|technical drawing)",
    re.I,
)
# ⚠️ **Word boundaries defeat this list in BOTH directions, and a real run proved both.**
# Under-matching: `\blogo\b` misses `Foodlogo2` and `EFTA logo2` (glued to a word, suffixed with a
# digit) and `\bclip.?art\b` misses `openclipart` — all three were accepted onto real guides, a food
# logo onto food spoilage, a trade-bloc logo onto passports, a desktop sound icon onto film noir.
# Over-matching is the subtler half: a bare `seal` rejects a harbour seal's anatomy, a bare `flag`
# rejects a semaphore chart, a bare `crest` rejects a wave crest, and `signature` rejects a
# dolphin's signature whistle — all genuine subjects in this library. So the compound-prone terms
# lose their leading boundary and gain a negative lookahead, while the ambiguous nouns are required
# to appear in their heraldic phrasing. `seal of` is deliberately absent: it cannot tell a state
# seal from a mechanical one. 27 cases, both directions, are pinned in --selftest.
NOT_SUBJECT = re.compile(
    # compound-prone: no leading boundary, a lookahead instead of a trailing one
    r"logo(?![a-z])|logotype|wordmark|clip.?art|wordart|fan.?art|cartoon|"
    # ordinary nouns that only ever mean decoration
    r"\bposter(?![a-z])|\bicon(?![a-z])|\bfavicon|\bemblem|\bmascot|\bmeme(?![a-z])|"
    r"\bbarnstar|\buserbox|\bscreenshot|\bselfie|\bportrait(?![a-z])|"
    r"\bjersey|\bbadge(?![a-z])|\btrademark|"
    # A social-media handle in the filename means the file IS a post — a screenshot of a tweet,
    # never an illustration of anything. Found by hand-reading a real run: a guide on debris-flow
    # hazards after a wildfire was given a file called "I just got off the phone with
    # @GovJoshGreenMD – following a call with…". Narrow on purpose: `@` is vanishingly rare in a
    # Commons filename and when it appears it is a handle or an address, neither of which teaches.
    r"@[a-z0-9_]{2,}|"
    # ambiguous nouns, required in their heraldic phrasing so the real subjects survive
    r"signature of|coat of arms|crest of|great seal|\bflag of|flags of|"
    r"(book|album|magazine|dvd|game|comic) cover|postage stamp",
    re.I,
)
# Wikipedia article chrome: maintenance banners, portal icons, licence marks. Never the subject.
#
# ⚠️ **Every pattern here is written against norm(), and the reason is a measured failure.** This
# list used to spell its two commonest targets `symbol_` and `text_document`, with underscores,
# while the API hands back `File:Symbol category class.svg` and `File:Text document with red
# question mark.svg`, with spaces. So neither could ever fire, and those two maintenance icons were
# chosen for **20 of 268 guides** on a real run with the blocklist meant to stop them sitting right
# there. A blocklist that cannot match is worse than no blocklist, because it reads as protection.
#
# The second block is the portal and WikiProject furniture the first run met and nothing covered:
# a voting box on "how markets coordinate", the psi glyph on four memory guides, a globe on three
# more. These are template images — they appear on an article because a navbox put them there, not
# because the article is about them.
WIKI_CHROME = re.compile(
    # unchanged, except that every `_` became a space — see the note above. Hyphens are literal in
    # a Commons filename and are deliberately left alone; widening them to `[- ]` would start
    # refusing real subjects (`lock ` would take a canal lock, `edit ` an edit-distance figure).
    r"(commons[- ]logo|wiki[a-z]*[- ]logo|wiki letter|wikipedia|wiktionary|wikiquote|wikisource|"
    r"wikidata|wikibooks|wikinews|wikiversity|wikivoyage|wikiproject|wikimedia commons|"
    # ⚠️ `lock-` used to stand bare here and refused `Canal lock-gate operation.svg`. Wikipedia's
    # page-protection glyphs are named for their colour, so naming the colours keeps the icons out
    # without taking canal locks, lock washers or lock stitches with them. Found by writing the
    # control case, not by reading the pattern.
    r"question book|stub|sound-icon|ambox|imbox|edit-|padlock|"
    r"lock-(green|red|blue|silver|gray|grey|orange|purple|yellow|black|white|icon)|"
    r"symbol |nuvola|crystal|oojs|"
    r"portal|folder |disambig|merge-|split-|text document|magnify-clip|red pencil|emblem-|"
    r"increase2?\.svg|decrease2?\.svg|steady2?\.svg|yes check|x mark|star (full|empty|half)|"
    # portal and WikiProject furniture the first real run met and nothing covered. Each is a
    # template image: it is on the article because a navbox put it there, not because the article
    # is about it. Kept narrow on purpose — `noun-`, `asterisk` and `compass rose` were considered
    # and dropped, because this library really does hold guides on noun phrases and on reading a
    # compass rose. Anything decorative but ambiguously named is the evidence floor's job, not this
    # list's.
    r"voting box|global thinking|\bpsi\d|disc plain|animation disc|gnome-|oxygen480)",
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

def file_key(file_title: str) -> str:
    """
    "File:Foo_bar.svg" -> "foo bar.svg". Canonical spelling, extension KEPT.

    ⚠️ **Nothing may pattern-match a raw API title, and this function exists because something did.**
    Commons treats spaces and underscores as the same character in a title and the API returns
    SPACES, so a pattern written with an underscore can never fire. Every filename rule here went
    through the normaliser except [WIKI_CHROME], which was applied to the raw title while spelling
    its two commonest targets `symbol_` and `text_document` — so on a real run
    `File:Symbol category class.svg` and `File:Text document with red question mark.svg` were
    chosen for 20 of 268 guides, past a blocklist naming both.

    The extension is kept because three chrome rules key on it (`increase2.svg` and its siblings are
    the arrow glyphs in financial infoboxes, and the bare words are far too common to match alone).
    [file_stem] drops it for the rules that must not see it.
    """
    name = file_title.split(":", 1)[1] if ":" in file_title else file_title
    return re.sub(r"[\s_]+", " ", name).strip().lower()


def file_stem(file_title: str) -> str:
    """
    "File:Foo bar.svg" -> "foo bar". Canonical, extension dropped.

    ⚠️ Both halves matter. Leaving the namespace prefix on lets the literal word "file" satisfy the
    subject gate, which is exactly how "Secondary Model.svg" was accepted; leaving the extension on
    lets "svg"/"png" do the same.
    """
    return re.sub(r"\.[a-z0-9]{2,4}$", "", file_key(file_title))


def is_english(file_title: str) -> bool:
    """Gate 2. A translated diagram is a good diagram that this reader cannot read."""
    stem = file_stem(file_title)
    return not (LANG_TAG.search(stem) or NON_LATIN.search(stem))


def subject_hits(file_title: str, vocab: set[str], freq: collections.Counter) -> list[str]:
    stem = file_stem(file_title)
    return sorted({w for w in vocab if len(w) >= 4 and w in stem})


# Licence tags and housekeeping ride on nearly every file and say nothing about its subject, so a
# vocabulary word landing in one of these is a coincidence rather than a filing decision.
CATEGORY_NOISE = re.compile(
    r"^(cc|pd|gfdl|public domain|licen[cs]|copyright|self-published|files? |media |"
    r"images? (from|with|without|which|by|uploaded)|photographs? (by|taken)|"
    r"uploaded |taken with|artworks? without|information field|.*wikidata item)",
    re.I,
)


def category_hits(categories, vocab: set[str], freq: collections.Counter) -> list[str]:
    """
    The guide's own subject words appearing in a Commons category the file is filed under.

    ⚠️ **The bar is STRICTER than [on_subject]'s, and it has to be — "two ordinary words" was
    written first and measured wrong on the real corpus.** A filename is a phrase somebody wrote
    for one file; a category is a phrase about a group, so its ordinary words collide by accident,
    and substring matching makes that far worse than it looks. Measured against the real
    vocabularies:

        Earth photography during STS-39      vs remote sensing  ->  earth(243)  photograph(74)
        Collections of the Malta Maritime …  vs relative dating ->  time(596)   ← inside "Mari-TIME"
        Osmosis                              vs osmosis         ->  osmosis(16)
        Harris matrix                        vs the Harris matrix -> harris(12) matrix(74)

    Two hits would readmit the aurora photograph on "earth" and "photography", which is the exact
    picture this floor exists to refuse. So the rule is one word rarer than [RARE_DF]: a category
    naming something DISTINCTIVE about this guide, not merely a word the guide happens to use.
    """
    hits: set[str] = set()
    for c in categories:
        if CATEGORY_NOISE.match(c.replace("Category:", "").strip()):
            continue
        low = c.lower()
        hits |= {w for w in vocab if len(w) >= 4 and w in low}
    if not any(freq.get(w, 10 ** 6) < RARE_DF for w in hits):
        return []
    return sorted(hits)


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
    if NOT_SUBJECT.search(file_stem(file_title)) or WIKI_CHROME.search(file_key(file_title)):
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


def render_svg(raw: bytes) -> bytes | None:
    """
    A too-heavy vector, rendered to PNG at the corpus width so it can be kept instead of lost.

    ⚠️ **This exists because the size gate was throwing away the right picture.** A run discarded
    nine chosen diagrams on [MAX_SVG_BYTES] alone, several of them plainly correct — the electricity
    grid schematic for an electricity guide, Earth's atmosphere for the stratosphere, trophic layers
    for an ecology one. They had passed every relevance gate; the only objection was byte count.

    ⚠️ And the reason given for discarding them was stale. The comment here used to say rasterising
    "would need a renderer this container has no reason to carry" — it carries one: `cairosvg` and
    Pillow are installed, and `optimize_images.py` already uses exactly this route on the shipped
    corpus (circadian-clock.svg 981 kB → 93 kB, female-reproductive.svg 456 kB → 76 kB). Checked
    before writing this rather than assumed.

    Returns PNG, not WebP, deliberately: the caller then runs it through the ordinary raster path,
    so a render that came out blank or malformed meets [pixels_ok]'s decode, minimum-width, aspect
    and near-blank checks — which is a *stronger* test than the SVG branch ever applied. A silent
    blank render is the failure mode worth catching, and it is invisible in a byte count.

    None on anything that goes wrong, which is treated exactly like a file that failed a gate.
    """
    if len(raw) > MAX_SVG_RENDER_BYTES:
        return None                       # a multi-megabyte vector is a map, not a diagram
    try:
        import cairosvg
        return cairosvg.svg2png(bytestring=raw, output_width=MAX_WIDTH)
    except Exception:  # noqa: BLE001 — an unrenderable vector is a rejected candidate, not a crash
        return None


def pixels_ok(raw: bytes, mime: str) -> tuple[bool, str]:
    """
    Gate 6. Does the file actually contain a legible picture?

    An SVG is vector, so it is checked by size and well-formedness instead of by decoding. One over
    [MAX_SVG_BYTES] is not discarded outright — see [render_svg], which the caller tries first.

    ⚠️ **The vector branch had an upper bound and no lower one, and the raster branch below has both.**
    That asymmetry let graphical FRAGMENTS through as though they were diagrams. Three reached the
    shipped corpus: `File:Shogi da22.svg` is a **9x9 canvas containing one diagonal line** — a piece
    of a board-tile set, bundled as the sole illustration of a guide on shogi problems — its sibling
    `Shogi ddlh22.svg` is a line and a triangle, and `Gd&t regardlessoffeaturesize.svg` is a single
    64x64 notation glyph standing in for a whole guide on geometric tolerancing.

    ⚠️ **Canvas dimensions are the wrong measure and were tried first.** A vector's canvas is
    arbitrary: `the-cell-nucleus-and-nuclear-envelope.svg` declares 56x43 and carries 95 kB of
    detail. What separates a diagram from a fragment is how much is actually drawn. Measured across
    all 93 bundled SVGs, sorted by drawing-element count:

        1 element   151 B   Shogi da22                     <- fragment
        2 elements  180 B   Shogi ddlh22                   <- fragment
        2 elements  381 B   Gd&t regardlessoffeaturesize    <- glyph
        4 elements 6980 B   Diagram of a ball ... equilibrium  <- A REAL DIAGRAM
        7 elements  453 B   Rainbow-diagram-ROYGBIV         <- a real diagram, wrong subject
       10 elements 1309 B   Supply and demand curves        <- the smallest by element count
       20..77 elements      everything else

    ⚠️ **An element floor ALONE is also wrong, and only running it over the real corpus showed it.**
    The first cut refused anything under [MIN_SVG_ELEMENTS] and threw out the equilibrium diagram:
    seven kilobytes of path data expressed as four complex `<path>`s. Element count measures how many
    strokes there are, not how much drawing is in them.

    So a file has to be poor by BOTH measures to be refused — under [MIN_SVG_ELEMENTS] strokes AND
    under [MIN_SVG_BYTES]. Either kind of richness is enough. That refuses all three fragments (none
    over 400 B) and keeps every real diagram, with the nearest survivor an order of magnitude clear.

    It deliberately does NOT catch the rainbow: seven elements is a perfectly good drawing, it is
    simply about the wrong thing. Relevance is what the subject gates and the hand re-read are for,
    and a size floor pretending to judge it would be the worse mistake.
    """
    if mime == "image/svg+xml":
        if len(raw) > MAX_SVG_BYTES:
            return False, f"svg too heavy: {len(raw)//1024} kB"
        head = raw[:4096].lstrip()
        if not (head.startswith(b"<?xml") or head.startswith(b"<svg") or b"<svg" in raw[:4096]):
            return False, "not an svg"
        drawn = len(SVG_ELEMENT_RE.findall(raw.decode("utf-8", "replace")))
        if drawn < MIN_SVG_ELEMENTS and len(raw) < MIN_SVG_BYTES:
            return False, f"vector too sparse to be a diagram: {drawn} element(s), {len(raw)} B"
        return True, f"svg ({drawn} elements)"
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
    # getcolors rather than set(getdata()): the same count, without the deprecation warning Pillow
    # now prints for every single image — 300-odd lines of noise through a log that has to be read
    # by hand afterwards. maxcolors is the pixel count, so it can never return None by overflowing.
    colours = len(small.getcolors(64 * 64) or ())
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

    ⚠️ **Eight, not three, and the gate above is untouched.** A live run measured 81 skips of which
    54 — two thirds — never resolved an article at all, and the guides losing out were the ones with
    phrasal titles: *Testing a Business Idea With Preorders*, *Building and Maintaining Soil Organic
    Matter*. Three search results is simply too few for a title like that to surface something whose
    own name shares the guide's vocabulary, and an article is discarded here even on a perfect title
    match if it carries no images. Widening the pool cannot admit anything the vocabulary check
    refuses — every extra candidate faces exactly the same test — it only stops giving up early.
    """
    doc = api(WIKIPEDIA, generator="search", gsrsearch=query, gsrlimit=8,
              prop="images", imlimit="max")
    best, best_hits, best_title = [], 0, ""
    for page in (doc or {}).get("query", {}).get("pages", {}).values():
        article = page.get("title", "")
        hits = {w for w in vocab if len(w) >= 4 and w in article.lower()}
        rare = any(freq.get(h, 10 ** 6) < RARE_DF for h in hits)
        if not (len(hits) >= 2 or rare or (len(hits) == 1 and len(vocab) <= 3)):
            continue                       # this article is not about the guide's subject
        images = [im["title"] for im in page.get("images", [])
                  if not WIKI_CHROME.search(file_key(im["title"]))]
        if images and len(hits) > best_hits:
            best, best_hits, best_title = images[:limit], len(hits), article
    return best, best_title


def commons_category_search(query: str, limit: int = 6) -> list[str]:
    """
    Commons categories whose NAME matches the query — a seed for [commons_category_candidates].

    ⚠️ **This exists because that function had a dead branch.** It takes the categories seen on a
    good Wikipedia file, so when no article resolves at all it is handed an empty list and does
    nothing: of the two fallbacks only the blind filename search really ran, and for a phrasal guide
    title that search correctly refuses almost everything. Measured on a real run, two thirds of all
    skips were in exactly that state.

    A category is a filing decision by an editor, so *"the files an editor put in a category named
    after this subject"* is a genuinely different and better-founded source than *"files whose name
    contains these words"*. It is not a relaxed rule: every candidate it produces still faces
    [is_english], [on_subject], [teaches], [NOT_SUBJECT], [WIKI_CHROME], [watermarked], the evidence
    floor and [is_boilerplate], and it enters at the same source rank as before.
    """
    doc = api(COMMONS, list="search", srnamespace=14, srsearch=query, srlimit=limit)
    return [r["title"] for r in (doc or {}).get("query", {}).get("search", [])]


def commons_category_candidates(
    seed_categories: list[str], limit: int = 12, require_diagram: bool = True,
) -> list[str]:
    """
    Siblings of a good file: the other diagrams an editor filed in the same category.

    ⚠️ `require_diagram` exists because the two kinds of seed need opposite treatment, and getting
    it wrong makes the caller silently do nothing. Seeds taken from a FILE's own categories are a
    mixed bag — licence tags, maintenance categories, the subject — so they are filtered down to the
    one that says "diagram". Seeds from [commons_category_search] are categories whose NAME already
    matched the guide's subject, and none of those would survive that filter, so applying it there
    would throw away every seed and leave the branch as dead as it was before. The drawing
    requirement is not lost either way: [teaches] still applies to every file downstream.
    """
    for cat in seed_categories:
        if require_diagram and not DIAGRAM_CATEGORIES.search(cat):
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


BOILERPLATE_USES = 500     # above this a file is furniture, not a picture of anything
_usage_cache: dict[str, bool] = {}


def is_boilerplate(file_title: str) -> bool:
    """
    Is this file used across so much of Wikimedia that it cannot be about any one subject?

    This is the one chrome rule that does not depend on guessing filenames in advance, which is
    what every other one does. But the threshold took three attempts and only measurement found
    the first two wrong, so the numbers are recorded here rather than left as a constant:

        file                                   uses  wikis  article-space
        Harris matrix example.svg                 4      3      2   <- diagram
        Climate change feedbacks.svg             10      8      8   <- diagram
        Animal cell structure en.svg             82     48      3   <- diagram
        Supply-and-demand.svg                   151     65      6   <- diagram
        Diagram human cell nucleus multilang.svg 189     72     10   <- diagram
        A coloured voting box.svg              >500      3     23   <- chrome
        Psi2.svg                               >500      9    233   <- chrome
        Text document with red question mark   >500      7    479   <- chrome
        Symbol category class.svg              >500      1      0   <- chrome

    ⚠️ **A threshold of 50 rejects the best diagrams, and did.** A canonical illustration is used
    a few times on each of *many* wikis, because it is the picture of that subject in every
    language — 48, 65 and 72 wikis above. Counting raw uses punishes a diagram for being good, and
    a live run refused `Animal cell structure en.svg` and the human cell nucleus diagram before
    this was measured.

    ⚠️ **"Used in article space" does not separate them either**, which was the obvious second
    attempt. Maintenance icons ride templates that sit on articles: the unreferenced-article glyph
    has **479** article-space uses. That rule would have kept it.

    What does separate them cleanly is the raw count at a threshold two and a half times above the
    busiest real diagram measured. Nothing that is genuinely *about* one subject appears on five
    hundred pages; only furniture does.

    ⚠️ **ONE TITLE PER REQUEST, AND THE OBVIOUS OPTIMISATION INVERTS THE ANSWER.** `globalusage`
    can ride the batched [image_details] call for free — and `gulimit` is a budget shared across
    ALL titles in the batch, so the API spends it on the first pages and reports **zero** for the
    rest. Measured, batched, in exactly this order::

        File:Climate change feedbacks.svg              gu=10   <- a real diagram
        File:Harris matrix example.svg                 gu=4    <- a real diagram
        File:Psi2.svg                                  gu=46
        File:Symbol category class.svg                 gu=0    <- chrome, reads as unused
        File:Text document with red question mark.svg  gu=0    <- chrome, reads as unused

    A threshold over that batch rejects the diagrams and keeps the chrome. Do not batch this.

    The cost is bounded by asking only about a candidate that has already won its guide, and by the
    cache: chrome repeats, so the fourteenth sighting of an icon is free. A failed lookup answers
    False — an unreachable API must not start refusing good files.
    """
    key = file_key(file_title)
    if key in _usage_cache:
        return _usage_cache[key]
    # 500 is the API's own ceiling for this parameter, so asking for one more to see the overflow
    # is not available — the continuation token IS the overflow. A file with exactly 500 uses comes
    # back full and uncontinued and is kept; one with 501 comes back full and continued.
    doc = api(COMMONS, prop="globalusage", gulimit=BOILERPLATE_USES, titles=file_title)
    verdict = False
    if doc:
        pages = list((doc.get("query") or {}).get("pages", {}).values())
        uses = len(pages[0].get("globalusage", [])) if pages else 0
        verdict = uses > BOILERPLATE_USES or "continue" in doc
    _usage_cache[key] = verdict
    return verdict


# ════════════════════════════════════════════════════════════════════════════════════════════════
# choosing
# ════════════════════════════════════════════════════════════════════════════════════════════════

def evidence(file_title: str, categories, vocab, freq) -> list[str]:
    """
    The positive reasons to believe this file belongs to this guide. Empty means none.

    ⚠️ **This is the gate the first run had no equivalent of, and its absence was the single
    largest source of wrong pictures.** Being reachable from the right Wikipedia article is
    *necessary* evidence, not *sufficient*: an article carries navboxes, infoboxes and gallery
    tangents, so "on the page" admits anything anybody hung there. Measured, with no floor at all:

        File:Maler der Grabkammer des Sennudem 001.jpg  ->  "Cover Crops and Green Manures"

    a German-titled Egyptian tomb painting, on a guide about green manures. Turing's blue plaque,
    the ENIAC historical marker, a tourist photograph of the Giza pyramids and a `3 watt power LED`
    on *the integrated circuit* all arrived by exactly the same route, each with a bare score of
    100 and not one thing said for it.

    Three kinds of evidence count: an editor filing it under a category that names the guide's own
    SUBJECT, the guide's vocabulary in the filename, and the filename calling itself a diagram. Any
    one is enough — requiring two was tried against the first run's real output and threw away
    genuine figures whose names are Latin or German roots the guide never uses.

    ⚠️ **Being in a DIAGRAM category is deliberately not one of them, and that is a correction to an
    earlier version of this function which said it was the strongest of the three.** It answers
    "is this a diagram", never "is it a diagram about THIS" — so on the Wikipedia path, where
    [on_subject] is skipped by design, it was the only thing standing between an article's gallery
    tangents and a guide. The case that was cited here to justify it, `Osmose en.svg`, does not
    actually rest on it: the file's real categories are `Osmosis`, `Non-photographic images by
    Hans Hillewaert` and two licence tags, so what genuinely rescues it is the subject category
    below. The claim was checked against Commons rather than assumed only after two photographs had
    already been chosen on it.
    """
    out = []
    cat_hits = category_hits(categories, vocab, freq)
    if cat_hits:
        out.append("filed-under:" + "/".join(cat_hits[:3]))
    hits = subject_hits(file_title, vocab, freq)
    if hits:
        out.append("names:" + "/".join(sorted(hits)[:3]))
    if DIAGRAM_WORDS.search(file_stem(file_title)):
        out.append("diagram-word")
    return out


def score(file_title: str, info, categories, vocab, freq, source_rank: int) -> int:
    """
    Higher is better. Only reached by candidates that already passed every gate, so this is about
    picking the BEST survivor rather than about safety.

    ⚠️ **Format is a tiebreak and never a reason, and it used to be the loudest signal here.** SVG
    scored +25, above every relevance bonus — and Wikipedia's portal and maintenance icons are all
    SVG. That is not merely how chrome survived, it is how chrome *won*: `Symbol category class.svg`
    totalled 125 and outranked real diagrams on fourteen different guides. The bonus is now smaller
    than a single named subject word, so a crisp irrelevant file can no longer beat a relevant one.
    """
    s = 100 - source_rank * 20                                    # Wikipedia beats category beats search
    s += 12 * len(subject_hits(file_title, vocab, freq))          # more of the subject named
    if any(DIAGRAM_CATEGORIES.search(c) for c in categories):
        s += 20                                                   # an editor called it a diagram
    if DIAGRAM_WORDS.search(file_stem(file_title)):
        s += 10
    if re.search(r"[-_ ]en([-_. ]|$)", file_stem(file_title)):
        s += 8                                                    # the explicitly-English variant
    if info.get("mime") == "image/svg+xml":
        s += 6                                                    # crisp at any size, and tiny
    return s


def survivors(source, titles, rank, vocab, freq, taken):
    """
    Every candidate from one source that clears every gate, best first, plus the categories seen.

    ⚠️ **One function, because there used to be two.** The Wikipedia pass and the two fallback
    passes each carried their own copy of the same six-line gate sequence, so a gate added to one
    silently did not exist in the others — and a duplicated definition is a mistake this repository
    has now corrected in six places. The evidence floor and the distinctness rule are both new, and
    neither could have been added safely to a shape that says the same thing twice.
    """
    seen_cats: list[str] = []
    titles = [t for t in titles
              if is_english(t) and on_subject(t, vocab, freq, source) and file_key(t) not in taken]
    if not titles:
        return [], seen_cats
    out = []
    for title, (info, cats) in image_details(titles).items():
        seen_cats = cats or seen_cats
        ok, licence = acceptable_licence(info)
        if not ok or watermarked(cats):
            continue
        if not teaches(title, str(info.get("mime", "")), cats, source):
            continue
        if info.get("width", 0) < MIN_SOURCE_WIDTH and info.get("mime") != "image/svg+xml":
            continue
        why = evidence(title, cats, vocab, freq)
        if not why:
            continue
        out.append((score(title, info, cats, vocab, freq, rank), title, info, cats, licence, source, why))
    out.sort(key=lambda c: -c[0])
    return out, seen_cats


def choose(guide, freq, taken=None, verbose=True):
    """
    Walk the source ladder; return the best gate-passing candidate, or None.

    [taken] holds the files already given to earlier guides in this run. ⚠️ Without it, one file is
    handed to several guides: measured on the first real run, 18 files covered 59 guides, so sibling
    guides showed the same picture and the same bytes were stored twice under two names.
    """
    taken = taken if taken is not None else set()
    vocab = vocabulary(guide)
    query = subject_query(guide)
    wiki_titles, article = wikipedia_candidates(query, vocab, freq)
    if verbose and article:
        print(f"      · article: {article}")

    found, seen_cats = survivors("wikipedia", wiki_titles, 0, vocab, freq, taken)
    if not found:
        # Fall through: siblings of whatever the article gave, then a blind search.
        #
        # ⚠️ **Lazily, which the previous shape was not.** Both candidate lists were built in the
        # list literal before the loop began, so the blind search's two requests were spent on every
        # guide even when the category path had already won. `break` skipped the gate work and none
        # of the network.
        #
        # ⚠️ And when no article resolved, `seen_cats` is empty and the category path did nothing at
        # all — measured as two thirds of every skip. [commons_category_search] gives it a real seed
        # in that case; `require_diagram=False` because those categories are named after the subject
        # rather than after being diagrams.
        subject_seeded = not seen_cats
        seeds = seen_cats or commons_category_search(query)
        sources: list[tuple[str, "collections.abc.Callable[[], list[str]]"]] = [
            ("commons-category",
             lambda: commons_category_candidates(seeds, require_diagram=not subject_seeded)),
            ("commons-search", lambda: commons_search_candidates(query)),
        ]
        for rank, (source, titles_for) in enumerate(sources, start=1):
            found, _ = survivors(source, titles_for(), rank, vocab, freq, taken)
            if found:
                break

    # The boilerplate check costs a request, so it is asked only of a candidate that has already
    # won, and only until one answers. Its cache makes every repeat free, which matters precisely
    # because template glyphs are the files that repeat.
    for cand in found:
        if not is_boilerplate(cand[1]):
            return cand
        if verbose:
            print(f"      · {cand[1][5:][:48]} is template furniture, not a picture of anything")
    return None


# ════════════════════════════════════════════════════════════════════════════════════════════════
# fetch and write
# ════════════════════════════════════════════════════════════════════════════════════════════════

def encode(raw: bytes) -> bytes | None:
    """
    To the corpus format: WebP q85, never wider than 1280, **flattened onto white**.

    ⚠️ **The flattening is the corpus's rule, and this function was the one place breaking it.**
    `optimize_images.py:convert()` states it: both readers draw the diagram on a light card, so a
    transparent image already renders against white and keeping the alpha channel costs bytes for
    an effect nothing can see. 451 of the 460 bundled images are RGB because they went through that
    path; the nine that were not came through here.

    It rarely bit while this only handled photographs off Commons, which are opaque. Rasterising
    vectors makes it bite every time — a diagram's natural background IS transparent, and the grid
    schematic written moments before this fix came out **91% transparent**. Worse than a cosmetic
    difference: a white label or fill inside such a diagram is invisible against a light card, so
    the picture can lose exactly the part that explains it.
    """
    from PIL import Image
    try:
        img = Image.open(io.BytesIO(raw))
        img.load()
    except Exception:  # noqa: BLE001
        return None
    if img.width > MAX_WIDTH:
        img = img.resize((MAX_WIDTH, round(img.height * MAX_WIDTH / img.width)), Image.LANCZOS)
    if img.mode in ("RGBA", "LA", "P") or "transparency" in img.info:
        img = img.convert("RGBA")
        flat = Image.new("RGB", img.size, (255, 255, 255))
        flat.paste(img, mask=img.split()[-1])
        img = flat
    else:
        img = img.convert("RGB")
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
    # ⚠️ Every count here is MEASURED against the real 651-guide corpus, not chosen to make a case
    # pass. `osmosis: 16` in particular is load-bearing: the category floor accepts a lone hit only
    # when the word is rarer than RARE_DF, and an earlier version of this table simply omitted the
    # word — so the default of "assume common" refused the control and the failure looked like a
    # defect in the gate rather than a hole in the fixture.
    freq = collections.Counter({"opportunity": 66, "research": 223, "legacy": 51, "secondary": 156,
                                "memory": 254, "building": 329, "relative": 361, "computer": 96,
                                "thermoluminescence": 7, "osmosis": 16, "harris": 12,
                                "stratigraphy": 22, "archaeology": 29, "aerial": 29,
                                "time": 596, "earth": 243, "photograph": 74, "matrix": 74,
                                "cation": 20, "graphs": 5})
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
        # ⚠️ THE UNDERSCORE CASES. Every one of these was CHOSEN on a real 268-guide run, past a
        # blocklist that named it, because WIKI_CHROME was matched against the raw API title while
        # spelling itself with underscores. They are written here with the SPACES the API actually
        # returns, so a regression to the underscore spelling fails immediately.
        ("File:Symbol category class.svg",
         {"prices", "information", "markets"}, "image/svg+xml", [], "wikipedia", False,
         "the maintenance icon taken by 14 guides — spelled `symbol_` in the list that names it"),
        ("File:Text document with red question mark.svg",
         {"secondary", "market", "research"}, "image/svg+xml", [], "wikipedia", False,
         "the unreferenced-article icon taken by 6 guides — spelled `text_document`"),
        ("File:Symbol_category_class.svg",
         {"prices", "information"}, "image/svg+xml", [], "wikipedia", False,
         "the same file under Commons' other spelling — both must be refused, not one"),
        # The portal furniture nothing covered at all.
        ("File:A coloured voting box.svg",
         {"markets", "coordinate", "planner"}, "image/svg+xml", [], "wikipedia", False,
         "a portal icon on 'how markets coordinate without a planner'"),
        ("File:Psi2.svg",
         {"memory", "episodic", "semantic"}, "image/svg+xml", [], "wikipedia", False,
         "the psychology portal glyph, taken by four separate memory guides"),
        ("File:Global thinking.svg",
         {"global", "thinking"}, "image/svg+xml", [], "wikipedia", False,
         "⚠️ names TWO vocabulary words, so only the chrome list can refuse it"),
        # ⚠️ Controls for the chrome list, because it was widened and a blocklist that over-matches
        # is the other half of the same defect. Each of these contains a chrome pattern's stem.
        ("File:Compass rose diagram.svg",
         {"compass", "rose", "navigation"}, "image/svg+xml", [], "commons-search", True,
         "control: 'compass rose' was considered for the chrome list and dropped — this library "
         "genuinely teaches reading one"),
        ("File:Noun phrase tree diagram.svg",
         {"noun", "phrase", "grammar"}, "image/svg+xml", [], "commons-search", True,
         "control: `noun-` was considered and dropped — there are real linguistics guides"),
        ("File:Canal lock-gate operation.svg",
         {"canal", "lock", "gate"}, "image/svg+xml", [], "commons-search", True,
         "control: the chrome list keeps `lock-` hyphenated; widening it to `lock[- ]` would "
         "start refusing canal locks"),
        # ⚠️ THE WORD-BOUNDARY CASE, and it isolates [teaches] rather than the evidence floor.
        # A real file with its real categories: two subject words in the name so the subject gate
        # passes, a JPEG with no diagram word so the ONLY thing that could call it a drawing is a
        # category — and the category that matches without word boundaries is `2012 PHOTOGRAPHS of
        # Malta`. The strongest available statement that a file is not a diagram, read as proof
        # that it is one.
        ("File:Marine sandglass MMM.jpg",
         {"marine", "sandglass", "timekeeping"}, "image/jpeg",
         ["Category:2012 photographs of Malta", "Category:Hourglasses",
          "Category:Collections of the Malta Maritime Museum"], "commons-search", False,
         "a museum photograph — only an unbounded `graph` inside 'photographs' calls it a drawing"),
    ]
    # ⚠️ The not-subject list is checked separately and in BOTH directions, because a real wave
    # showed it failing each way: compounds slipped through (Foodlogo2 onto a food-spoilage guide)
    # while bare nouns rejected genuine subjects (a harbour seal, a semaphore flag chart, a wave
    # crest, a dolphin's signature whistle). Testing only the reject half would have caught one.
    ns_reject = ["Foodlogo2", "EFTA logo2", "Gnome-mime-sound-openclipart", "Solar Power logo",
                 "New Zealand Breakers logo", "Triumphant Cartoon Woman Using A Computer",
                 "Flag of France", "Great Seal of the United States", "Coat of arms of Spain",
                 "Book cover of Dune", "Postage stamp of Kenya", "Nintendo wordmark",
                 "Signature of Napoleon",
                 # A social handle means the file IS a post. Both of these are the shape a real run
                 # produced: a tweet given to a guide on debris-flow hazards after a wildfire.
                 "I just got off the phone with @GovJoshGreenMD - following a call",
                 "Screenshot of @NWSHonolulu advisory"]
    ns_accept = ["Silicon wafer diagram", "Posterior view of the heart",
                 "Flag semaphore alphabet chart", "Harbor seal anatomy",
                 "Wave crest and trough diagram", "Ground cover vegetation map",
                 "Stamp mill diagram", "Logogram examples", "Iconic memory model",
                 "Signature whistle of dolphins", "Badger sett cross-section",
                 "Portraiture lighting setup", "Seal of a bearing assembly",
                 "Cover crop rotation diagram"]
    ns_bad = 0
    for t in ns_reject:
        if not NOT_SUBJECT.search(t):
            ns_bad += 1
            print(f"  FAIL not-subject let through   {t}")
    for t in ns_accept:
        if NOT_SUBJECT.search(t):
            ns_bad += 1
            print(f"  FAIL not-subject wrongly kills {t}")
    print(f"  {'ok  ' if not ns_bad else 'FAIL'} not-subject list: "
          f"{len(ns_reject)} reject + {len(ns_accept)} accept cases")

    # ── the evidence floor ──────────────────────────────────────────────────────────────────────
    # ⚠️ Checked separately because it is the only gate that can refuse a file every OTHER gate
    # accepts, which is exactly the hole the first run fell through: a picture on the right article,
    # correctly licensed, not chrome, not watermarked, and with nothing whatever said for it.
    # Every reject below was really assigned to the guide named beside it.
    ev_cases = [
        ("File:Maler der Grabkammer des Sennudem 001.jpg",
         {"cover", "crops", "green", "manures"}, [], False,
         "a German-titled Egyptian tomb painting on 'Cover Crops and Green Manures'"),
        ("File:Alan Turing 78 High Street Hampton blue plaque.jpg",
         {"turing", "foundations", "computer", "science"}, [], True,
         "⚠️ ACCEPTED on purpose: it names Turing. The floor asks for evidence, not for taste — "
         "a wall plaque about the right man is weak, and refusing it needs a rule that would also "
         "refuse every portrait-of-the-subject diagram in the corpus"),
        ("File:ENIAC Pennsylvania state historical marker.jpg",
         {"world", "wide", "origins", "evolution"}, [], False,
         "a roadside marker on 'The World Wide Web: Origins and Evolution' — names nothing"),
        ("File:3 watt power LED after removing phosphor.jpg",
         {"integrated", "circuit", "moore"}, [], False,
         "an LED photograph on 'The Integrated Circuit and Moore's Law'"),
        ("File:All Gizah Pyramids.jpg",
         {"archaeoastronomy", "ancient", "skywatchers"}, [], False,
         "a tourist photograph on archaeoastronomy"),
        ("File:Climate change feedbacks.svg",
         {"climate", "feedback", "loops"}, [], True,
         "control: names the subject twice, and was a correct pick on the same run"),
        # ⚠️ THE CATEGORIES BELOW ARE THE ONES COMMONS REALLY RETURNS, fetched rather than written
        # from memory. The earlier version of this case invented `Category:Osmosis diagrams` and so
        # passed for a reason that does not exist — the file is not in any diagram category at all.
        ("File:Osmose en.svg",
         {"osmosis", "osmotic", "pressure"},
         ["Category:CC-BY-SA-3.0", "Category:Information field template with formatting",
          "Category:Non-photographic images by Hans Hillewaert", "Category:Osmosis",
          "Category:Self-published work"], True,
         "control: names NOTHING the guide says — 'osmose' is the French root — and is kept "
         "because an editor filed it under `Osmosis`. ⚠️ Its only diagram-ish category is "
         "`Non-photographic images…`, which the unbounded pattern matched on 'photographic'"),
        ("File:Harris matrix example.svg",
         {"stratigraphy", "harris", "matrix"},
         ["Category:Archaeological stratigraphy", "Category:CC-BY-SA-4.0",
          "Category:Harris matrix", "Category:Self-published work"], True,
         "control: names the subject, and is filed under it twice over"),
        # ⚠️ Both of these were really chosen, by the run this fix interrupted, with a bare
        # diagram-category as their ONLY evidence — and the category that matched was a
        # PHOTOGRAPH category. Two defects in one: the pattern had no word boundaries, and being
        # filed under 'diagrams' was being treated as evidence about the subject.
        # ⚠️ The vocabularies below are the guides' REAL ones, and that is what makes these cases
        # bite. `time` is in the dating guide's vocabulary and sits inside "Mari-TIME Museum";
        # `earth` and `photograph` are both in the remote-sensing guide's and both sit inside
        # "EARTH PHOTOGRAPHy during STS-39". Written with a tidy invented vocabulary neither file
        # would have had a category hit at all and the rarity rule would have decided nothing.
        ("File:Marine sandglass MMM.jpg",
         {"relative", "dating", "archaeology", "stratigraphy", "time", "sequence", "layers"},
         ["Category:2012 photographs of Malta", "Category:Hourglasses",
          "Category:Collections of the Malta Maritime Museum",
          "Category:Taken with Nikon D300s"], False,
         "an hourglass in a Maltese museum, on 'Relative Dating Methods in Archaeology'"),
        ("File:Aurora-SpaceShuttle-EO.jpg",
         {"remote", "sensing", "aerial", "archaeology", "earth", "photograph", "imaging"},
         ["Category:Aurora australis viewed from space",
          "Category:Earth photography during STS-39", "Category:PD NASA"], False,
         "an aurora from the shuttle, on 'Remote Sensing and Aerial Archaeology' — TWO real "
         "vocabulary words land in its category, so only the rarity rule can refuse it"),
        # ⚠️ THE CASE THAT ISOLATES "a diagram category is not evidence about the subject", because
        # its category matches the pattern WITH the word boundaries in place. `Images with
        # annotations` is Commons housekeeping for the ImageAnnotator gadget — it says a file page
        # carries clickable regions, not that anybody drew anything — and it was the whole of the
        # reason a photograph of a corroded bronze fragment was chosen for 'A History of Astronomy'.
        # Restoring the old floor accepts this one even with the boundaries fixed.
        ("File:Antikythera Fragment A (Front).webp",
         {"history", "astronomy"},
         ["Category:Antikythera Mechanism", "Category:Images with annotations",
          "Category:Collections of the National Archaeological Museum of Athens"], False,
         "a museum photograph on 'A History of Astronomy' — filed under a gadget category"),
        # ⚠️ THE HOUSEKEEPING-CATEGORY CASES. Unlike everything above these are not pictures a run
        # chose — they are collisions FOUND by scanning all 651 real vocabularies against the
        # licence and maintenance categories Commons really attaches, which turned up 17. Every
        # word below is rare enough to carry a match on its own, and every one of them is hiding
        # inside a longer word: `cation` in lo-CATION, `graphs` in photo-GRAPHS, `ration` in
        # mig-RATION, `tract` in ex-TRACTed, `wizard` in Upload-WIZARD. Without the noise filter a
        # copyright tag becomes evidence about soil chemistry.
        ("File:Marine sandglass MMM.jpg",
         {"soil", "testing", "cation", "exchange", "fertility"},
         ["Category:Files with coordinates missing SDC location of creation",
          "Category:CC-BY-2.5"], False,
         "real collision from 'Soil Testing and Interpreting the Results': cation ⊂ loCATION"),
        ("File:Marine sandglass MMM.jpg",
         {"stratigraphy", "harris", "matrix", "graphs", "sequence"},
         ["Category:Photographs by Hans Hillewaert", "Category:Self-published work"], False,
         "real collision from 'Stratigraphy and the Harris Matrix': graphs ⊂ photoGRAPHS"),
    ]
    ev_bad = 0
    for title, vocab, cats, must_pass, why in ev_cases:
        got = bool(evidence(title, cats, vocab, freq))
        ok = got == must_pass
        ev_bad += 0 if ok else 1
        print(f"  {'ok  ' if ok else 'FAIL'} {'evidence' if got else 'nothing ':<9}"
              f"(want {'evidence' if must_pass else 'nothing'})  {title[5:][:44]:<44} {why[:70]}")

    bad = ns_bad + ev_bad
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
    # ⚠️ Category OR guide id, comma-separated. It used to be a single category, which cannot
    # express the one question worth asking of a change to article resolution: "do THESE named
    # guides, the ones a previous run recorded as skipped, reach a picture now?" A category is the
    # wrong unit for that — the skips are scattered across all 49 of them — and measuring a change
    # by re-running a whole category mixes guides that already succeeded into the count.
    # Category names carry no commas, so a single-category argument still means what it did.
    ap.add_argument("--only", default="")
    ap.add_argument("--report", default="")
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()

    if args.selftest:
        return selftest()

    check_gate_parity()
    guides, freq = load_corpus()
    only = {t.strip() for t in args.only.split(",") if t.strip()}
    if only:
        known = {g.get("category") for g in guides.values()} | set(guides)
        unknown = sorted(only - known)
        if unknown:
            # A typo in an id silently selects nothing, and "0 guides" reads exactly like "they all
            # have pictures already" — which is the answer this flag exists to distinguish.
            print("FAIL --only names nothing in the corpus: " + ", ".join(unknown))
            return 1
    todo = [g for g in guides.values()
            if not any(s.get("image") for s in g.get("sections", []))
            and g.get("category") not in LORE_CATEGORIES
            and (not only or g.get("category") in only or g["id"] in only)]
    todo.sort(key=lambda g: (g.get("category", ""), g["id"]))
    print(f"{len(todo)} guides without a diagram (lore categories excluded)\n")

    # Files already spoken for. A picture that explains one guide is rarely the best picture for
    # its neighbour, and two guides showing the same figure reads as a bug rather than as economy.
    #
    # ⚠️ Seeded from NOTICE.txt's source URLs, NOT from the image directory. The obvious seed is
    # `os.listdir(KB_IMAGES)`, and it is inert: those names are guide ids (`climate-feedback-loops
    # .svg`) while this set is compared against Commons titles (`climate change feedbacks.svg`).
    # It would look exactly like protection and could never match one thing — the same shape as the
    # underscore bug this whole pass exists to fix. The NOTICE genuinely records the Commons file
    # each diagram came from, for the 193 entries that carry a source line; the rest predate the
    # convention, so cross-run distinctness is real but not total, and that is stated rather than
    # implied.
    claimed: set[str] = set()
    if os.path.exists(NOTICE):
        with open(NOTICE, encoding="utf-8") as fh:
            for m in re.finditer(r"commons\.wikimedia\.org/wiki/File:(\S+)", fh.read()):
                claimed.add(file_key(urllib.parse.unquote(m.group(1))))

    if args.apply:
        os.makedirs(KB_IMAGES, exist_ok=True)
    matched, skipped = [], []
    touched, notice_lines = {}, []

    # ⚠️ **One loop, because two lost two waves.** This used to choose for every guide first and
    # download afterwards — and choosing 363 guides is roughly two hours of paced API calls, so an
    # interruption anywhere in that window threw the entire run away with nothing on disk to show
    # for it. It happened twice: once to a container restart and once to a process killed while a
    # Gradle build ran alongside it, at 81 of 363 selected and zero written.
    #
    # Interleaved, the run is resumable with **no new state at all**, because `todo` above is
    # derived as "guides with no image" — a guide that got its diagram is skipped by construction
    # on the next run. Together with the periodic flush() below, a death costs at most the last
    # FLUSH_EVERY guides, and re-running the identical command picks up where it stopped.
    for guide in todo[: args.limit]:
        print(f"  {guide['id']}  [{guide.get('category')}]")
        best = choose(guide, freq, claimed)
        if not best:
            print("      SKIP — nothing cleared the gates")
            skipped.append(guide["id"])
            continue
        sc, title, info, cats, licence, source, why = best
        claimed.add(file_key(title))
        author = strip_html(info.get("extmetadata", {}).get("Artist", {}).get("value"))[:100]
        print(f"      ✓ [{source} {sc}] {title[:56]}\n        {licence} · {author[:40]}"
              f"\n        because: {', '.join(why)}")
        # ⚠️ **Recorded only once it is real.** This used to append and write the report here, before
        # the download and the pixel checks that can still reject the pick — so pass 1's report
        # claimed 170 matches against 154 files actually written. Sixteen rows described choices
        # that never became images, and the report is the thing that gets hand-reviewed and turned
        # into a contact sheet. A dry run has nothing but the choice to report, so it still records
        # here; an applying run records after the file lands.
        row = (guide, title, licence, author, source, sc, why)
        if not args.apply:
            matched.append(row)
            if args.report:
                write_report(args.report, matched)
            continue

        mime = str(info.get("mime", ""))
        svg = mime == "image/svg+xml"
        # `get` already retries three times and backs off 45 s on a 429, so an empty result here is
        # a wall rather than a blip. The guide keeps no image, which means the next pass picks it up
        # by construction — that IS the retry, and it needs no code of its own.
        raw = get(info["url"] if svg else (info.get("thumburl") or info["url"]), binary=True)
        if not raw:
            print("      ! download failed")
            continue

        # A vector over the ceiling is rendered rather than lost, and then goes through the ordinary
        # raster path — so the render faces stricter checks than the vector ever did.
        if svg and len(raw) > MAX_SVG_BYTES:
            png = render_svg(raw)
            if png:
                print(f"      · vector {len(raw)//1024} kB over the "
                      f"{MAX_SVG_BYTES//1024} kB ceiling — rendered to {MAX_WIDTH}px raster")
                raw, mime, svg = png, "image/png", False

        ok, note = pixels_ok(raw, mime)
        if not ok:
            print(f"      ! {note}")
            continue
        data = raw if svg else encode(raw)
        if not data:
            print(f"      ! not encodable")
            continue
        name = f"{guide['id']}.{'svg' if svg else 'webp'}"
        idx = pick_section(guide)
        if idx < 0:
            print(f"      ! no section to attach to")
            continue
        with open(os.path.join(KB_IMAGES, name), "wb") as fh:
            fh.write(data)
        guide["sections"][idx]["image"] = f"kb/{name}"
        touched.setdefault(guide["_file"], []).append(guide)
        page = "https://commons.wikimedia.org/wiki/" + urllib.parse.quote(title.replace(" ", "_"))
        notice_lines.append(f"kb/{name}\n    {title}\n    {licence} — {author or 'see source'}\n    {page}")
        print(f"      wrote kb/{name}  ({len(data)/1024:.0f} kB, {note})")
        # NOW it is real, so now it goes in the report — see the note at the choice above.
        matched.append(row)
        if args.report:
            write_report(args.report, matched)
        # Bounded, rather than once at the end: the window in which the tree is inconsistent is now
        # at most this many files wide instead of the whole run. Not per-file, because that would
        # rewrite a half-megabyte shard for every diagram.
        if len(notice_lines) >= FLUSH_EVERY:
            flush(touched, notice_lines)

    flush(touched, notice_lines)
    print(f"\n{len(matched)} matched, {len(skipped)} skipped")
    if not args.apply:
        print("dry run — pass --apply to fetch and patch")
    return 0


def write_report(path: str, matched: list) -> None:
    """
    Rewrite the report after every pick, not once at the end.

    `why` rides it because the report is what gets re-read by hand afterwards, and "what was said
    for this file" is the question that reading is trying to answer. Written incrementally for the
    same reason the shards are: a report that only exists if the process reaches its last line is
    a report you do not have when you most want it — after a run that died.
    """
    json.dump([{"guide": g["id"], "title": g["title"], "file": t, "licence": l,
                "author": a, "source": s, "score": sc, "why": w}
               for g, t, l, a, s, sc, w in matched], open(path, "w"), indent=1)


def flush(touched: dict, notice_lines: list) -> None:
    """
    Write the shard edits and the provenance for everything downloaded so far, then forget it.

    ⚠️ **Called during the run, not only at the end of it, and that is the whole point.** The images
    land on disk as they are fetched while the shard that points at them was patched once, after the
    last download — so any interruption at all left every file written so far referenced by nothing.
    That is not hypothetical: a wave was stopped part-way and stranded **59 images**, which shipped
    into the APK and the desktop jar with every check green, because nothing looked for a file no
    guide points at. `BundledImagesTest.nothingShipsThatNoGuidePointsAt` is now the gate that
    notices; flushing periodically is what stops it happening.

    NOTICE.txt is written in the same breath, because a diagram whose licence and author were not
    recorded is worse than one that was never fetched.

    ⚠️ **The shard is replaced atomically, and that is what makes flushing this often safe.** Writing
    in place means a kill during `json.dump` truncates a half-megabyte of shipped content — far worse
    than the orphan it is trying to prevent. `os.replace` is atomic on POSIX, so the shard is either
    the old one or the new one and never a torn one. With that, [FLUSH_EVERY] can be 1.

    ⚠️ Order matters and is deliberate: the image file is written **before** the shard points at it.
    An interruption between them leaves an unreferenced file, which is harmless and which
    `BundledImagesTest` catches; the reverse would leave a guide pointing at an image that does not
    exist, which breaks the reader on the page somebody opened.
    """
    for path, changed in touched.items():
        doc = json.load(open(path, encoding="utf-8"))
        items = doc if isinstance(doc, list) else doc.get("guides", [])
        by_id = {g["id"]: g for g in items}
        for guide in changed:
            by_id[guide["id"]]["sections"] = guide["sections"]
        for g in items:
            g.pop("_file", None)
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as fh:
            json.dump(doc, fh, ensure_ascii=False, indent=1)
            fh.write("\n")
            fh.flush()
            os.fsync(fh.fileno())
        os.replace(tmp, path)
        print(f"      patched {os.path.basename(path)}")
    touched.clear()

    if notice_lines:
        with open(NOTICE, "a", encoding="utf-8") as fh:
            fh.write("\n\nSourced from Wikimedia Commons (see the re-encoding notice above)\n")
            fh.write("-" * 62 + "\n" + "\n".join(notice_lines) + "\n")
        print(f"  appended {len(notice_lines)} entries to NOTICE.txt")
        notice_lines.clear()


if __name__ == "__main__":
    raise SystemExit(main())
