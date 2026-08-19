#!/usr/bin/env python3
"""
Render every bundled knowledge-base diagram as one scannable page.

    tools/kb/contact_sheet.py out.html                 # the whole corpus
    tools/kb/contact_sheet.py out.html --since <report>  # only what a wave just added

⚠️ **This exists because reading the report by hand is what caught what the automation missed,
twice.** The gates in `source_images.py` are now good enough that the *machine* is not the weak
link — but "is this picture actually about this guide?" is a judgement no filename test makes, and
the only way to make 300 of those judgements affordably is to see them all at once beside their
titles. A wall of thumbnails is read in a minute; a JSON report of the same 300 is not read at all.

Images are inlined as data URIs so the page is one self-contained file that can be published
without an asset host — and **thumbnailed**, which is the honest trade and worth stating rather
than discovering. Inlining the shipped bytes measured ~300 kB a card, about 90 MB across the
corpus against a 16 MB cap. Small vectors ride as-is; everything else is re-encoded to card size.
So this page answers "is it the right picture?" and not "is it sharp enough?" — the second is what
`BundledImagesTest` and the readers' own 1280 px ceiling are for.
"""

from __future__ import annotations

import argparse
import base64
import glob
import html
import io
import json
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ASSETS = os.path.join(ROOT, "app/src/main/assets/survival")
IMAGES = os.path.join(ASSETS, "images")


def corpus() -> list[dict]:
    """Every guide section carrying a diagram, with the guide it belongs to."""
    out = []
    for path in sorted(glob.glob(os.path.join(ASSETS, "guides*.json"))):
        doc = json.load(open(path, encoding="utf-8"))
        items = doc if isinstance(doc, list) else doc.get("guides", [])
        for g in items:
            for s in g.get("sections", []):
                if s.get("image"):
                    out.append({"id": g["id"], "title": g.get("title", g["id"]),
                                "category": g.get("category", ""),
                                "heading": s.get("heading", ""), "image": s["image"]})
    return out


def provenance() -> dict[str, tuple[str, str]]:
    """
    image path -> (licence-and-author line, Commons page).

    ⚠️ **There are TWO notice files in TWO formats, and reading only one is a real defect this
    tool shipped with.** `images/NOTICE.txt` carries the four-line blocks the current sourcer
    writes (path / source title / licence — author / URL); `images/kb/NOTICE.txt` carries the
    earlier waves' one-line `file — source — licence — author` entries, with no URL and a bare
    filename rather than a path. Parsing only the first matched **19 of 362** diagrams, so every
    other card on the sheet would have read "bundled before the provenance convention" — which is
    exactly wrong for the 300 images whose attribution is sitting in the other file, and it would
    have made the licence column useless on the one page anybody actually reads them on.
    """
    out: dict[str, tuple[str, str]] = {}

    top = os.path.join(IMAGES, "NOTICE.txt")
    if os.path.exists(top):
        text = open(top, encoding="utf-8").read()
        for m in re.finditer(r"^(kb/\S+)\n\s+(.+)\n\s+(.+)\n\s+(https?://\S+)", text, re.M):
            out[m.group(1)] = (f"{m.group(3)} · {m.group(2)}", m.group(4))

    # ⚠️ And a THIRD format, in the same top-level file: the original survival diagrams are
    # recorded as an indented entry whose description wraps over several lines, then a
    # `by <author> — <licence>` line, then the source URL. 183 of the corpus is in this shape.
    if os.path.exists(top):
        text = open(top, encoding="utf-8").read()
        entry = re.compile(r"^  (\S+\.(?:webp|svg|png|jpe?g|gif)) — ", re.M)
        marks = list(entry.finditer(text))
        for i, m in enumerate(marks):
            chunk = text[m.end():marks[i + 1].start() if i + 1 < len(marks) else len(text)]
            by = re.search(r"^\s+by (.+?) — (.+?)(?:,\s*Wikimedia Commons)?\s*$", chunk, re.M)
            url = re.search(r"(https?://\S+)", chunk)
            if by:
                out.setdefault(m.group(1), (f"{by.group(2)} · {by.group(1)}",
                                            url.group(1) if url else ""))
            elif url or " — " in chunk[:200]:
                lic = re.search(r"\(([^)]*(?:public domain|CC[ -]|PD)[^)]*)\)", chunk, re.I)
                out.setdefault(m.group(1), (lic.group(1) if lic else "see NOTICE.txt",
                                            url.group(1) if url else ""))

    kb = os.path.join(IMAGES, "kb", "NOTICE.txt")
    if os.path.exists(kb):
        text = open(kb, encoding="utf-8").read()
        # ⚠️ The author field is OPTIONAL here — 35 entries carry only `file — source — licence`,
        # and requiring four fields silently dropped every one of them.
        for m in re.finditer(
                r"^(\S+\.(?:webp|svg|png|jpe?g|gif))\s+—\s+(.+?)\s+—\s+([^—]+?)(?:\s+—\s+(.+))?$",
                text, re.M):
            who = f" · {m.group(4)}" if m.group(4) else ""
            # first writer wins: the four-line blocks carry a source URL, these do not
            out.setdefault("kb/" + m.group(1), (f"{m.group(3)}{who} · {m.group(2)}", ""))

    # ⚠️ Last resort, and it is a VERIFICATION rather than a guess. The oldest diagrams are
    # covered by a shared paragraph — "the knot illustrations … are scanned engravings from the
    # 1911 Encyclopaedia Britannica … PUBLIC DOMAIN" — with the files listed as a comma-separated
    # run. Structuring that with a regex would mean inventing a per-file licence; confirming the
    # filename really appears in a notice does not, and anything left over after this genuinely
    # has no record and should be shouted about on the page.
    for path in (top, kb):
        if not os.path.exists(path):
            continue
        text = open(path, encoding="utf-8").read()
        for root, _, files in os.walk(IMAGES):
            for f in files:
                if f == "NOTICE.txt":
                    continue
                rel = os.path.relpath(os.path.join(root, f), IMAGES).replace(os.sep, "/")
                if rel not in out and f not in out and f in text:
                    out[rel] = ("attribution recorded in NOTICE.txt", "")
    return out


# The card frame is 190 px tall, so a thumbnail this wide is already more than it can show on a
# 2× display. ⚠️ Measured before this existed: inlining the shipped bytes cost ~300 kB a card, which
# is ~90 MB across the corpus against an artifact cap of 16 MB. The sheet is for judging subject
# matter, not sharpness, so the thumbnail is the honest size — and the page says so.
THUMB_PX = 420
THUMB_QUALITY = 70
SVG_INLINE_LIMIT = 60_000    # small vectors ride as-is; big ones are rasterised like everything else


def data_uri(rel: str) -> str | None:
    path = os.path.join(IMAGES, rel)
    if not os.path.isfile(path):
        return None
    raw = open(path, "rb").read()
    if rel.lower().endswith(".svg"):
        if len(raw) <= SVG_INLINE_LIMIT:
            return f"data:image/svg+xml;base64,{base64.b64encode(raw).decode()}"
        raw = rasterise_svg(path) or raw
        if raw[:5] == b"<?xml" or raw[:4] == b"<svg":
            return f"data:image/svg+xml;base64,{base64.b64encode(raw).decode()}"
        return f"data:image/png;base64,{base64.b64encode(raw).decode()}"
    return f"data:image/webp;base64,{base64.b64encode(thumb(raw)).decode()}"


def thumb(raw: bytes) -> bytes:
    """A card-sized WebP, or the original if it will not decode — never nothing."""
    try:
        from PIL import Image
        img = Image.open(io.BytesIO(raw))
        img.load()
        if img.width > THUMB_PX:
            img = img.resize((THUMB_PX, max(1, round(img.height * THUMB_PX / img.width))),
                             Image.LANCZOS)
        if img.mode not in ("RGB", "RGBA"):
            img = img.convert("RGB")
        out = io.BytesIO()
        img.save(out, "WEBP", quality=THUMB_QUALITY, method=4)
        return out.getvalue()
    except Exception:  # noqa: BLE001 — a card with the full image beats a card with none
        return raw


def rasterise_svg(path: str) -> bytes | None:
    try:
        import cairosvg
        return cairosvg.svg2png(url=path, output_width=THUMB_PX)
    except Exception:  # noqa: BLE001
        return None


CARD = """
  <figure class="card">
    <div class="frame">{img}</div>
    <figcaption>
      <div class="t">{title}</div>
      <div class="s">{category} · {heading}</div>
      <div class="p">{prov}</div>
    </figcaption>
  </figure>"""


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--since", default="", help="a wave report; limits the sheet to its guides")
    args = ap.parse_args()

    rows = corpus()
    if args.since and os.path.exists(args.since):
        wanted = {r["guide"] for r in json.load(open(args.since))}
        rows = [r for r in rows if r["id"] in wanted]
    prov = provenance()

    cards, missing = [], []
    for r in rows:
        uri = data_uri(r["image"])
        if uri is None:
            missing.append(r["image"])
            continue
        # ⚠️ Path first, then bare filename. The notices identify a file by NAME; a guide
        # identifies it by path relative to images/, so `kb/ada-lovelace-….webp` and the
        # `ada-lovelace-….webp` entry recording it are the same file under two spellings.
        licence, page = prov.get(r["image"]) or prov.get(os.path.basename(r["image"])) \
            or ("⚠️ no provenance entry", "")
        prov_html = html.escape(licence)
        if page:
            prov_html += f' · <a href="{html.escape(page)}">source</a>'
        cards.append(CARD.format(
            img=f'<img loading="lazy" src="{uri}" alt="{html.escape(r["title"])}">',
            title=html.escape(r["title"]),
            category=html.escape(r["category"]),
            heading=html.escape(r["heading"]),
            prov=prov_html,
        ))

    doc = f"""<title>Knowledge Base Diagrams</title>
<style>
  :root {{ --bg:#faf9f7; --ink:#1a1a1a; --dim:#6b6864; --line:#e2ded8; --card:#fff; }}
  :root:not([data-theme="light"]) {{ }}
  @media (prefers-color-scheme: dark) {{
    :root:not([data-theme="light"]) {{ --bg:#141414; --ink:#ececec; --dim:#9a9691; --line:#2c2c2c; --card:#1c1c1c; }}
  }}
  :root[data-theme="dark"] {{ --bg:#141414; --ink:#ececec; --dim:#9a9691; --line:#2c2c2c; --card:#1c1c1c; }}
  body {{ background:var(--bg); color:var(--ink); margin:0; padding:28px 20px 60px;
         font:15px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif; }}
  header {{ max-width:1400px; margin:0 auto 26px; }}
  h1 {{ font-size:22px; margin:0 0 6px; letter-spacing:-0.01em; }}
  .lede {{ color:var(--dim); margin:0; max-width:62ch; }}
  .grid {{ max-width:1400px; margin:0 auto; display:grid; gap:18px;
           grid-template-columns:repeat(auto-fill,minmax(240px,1fr)); }}
  .card {{ margin:0; background:var(--card); border:1px solid var(--line); border-radius:8px;
           overflow:hidden; display:flex; flex-direction:column; }}
  /* The readers draw these on a light card because the corpus is line art on white — showing them
     on the page background instead would review them in conditions the app never uses. */
  .frame {{ background:#f3f1ec; display:flex; align-items:center; justify-content:center;
            height:190px; padding:10px; }}
  .frame img {{ max-width:100%; max-height:100%; object-fit:contain; }}
  figcaption {{ padding:10px 12px 12px; border-top:1px solid var(--line); }}
  .t {{ font-weight:600; font-size:13.5px; line-height:1.35; }}
  .s {{ color:var(--dim); font-size:11.5px; margin-top:3px; }}
  .p {{ color:var(--dim); font-size:11px; margin-top:6px; }}
  a {{ color:inherit; }}
</style>
<header>
  <h1>Knowledge Base diagrams — {len(cards)} of {len(rows)}</h1>
  <p class="lede">Every bundled diagram beside the guide it illustrates, its licence and its source.
  Read for the one thing no automated gate can check: whether the picture is actually about the
  subject. Drawn on the same light card the app uses.</p>
</header>
<div class="grid">{''.join(cards)}
</div>
"""
    with open(args.out, "w", encoding="utf-8") as fh:
        fh.write(doc)
    size = os.path.getsize(args.out) / 1024 / 1024
    print(f"{len(cards)} diagrams -> {args.out}  ({size:.1f} MB)")
    if missing:
        print(f"⚠️ {len(missing)} referenced but not on disk: {missing[:5]}")
    if size > 15:
        print("⚠️ over 15 MB — an artifact is capped at 16 MB; use --since to narrow it")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
