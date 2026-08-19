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
    """image path -> (licence-and-author line, Commons page). Parsed out of NOTICE.txt."""
    notice = os.path.join(IMAGES, "NOTICE.txt")
    if not os.path.exists(notice):
        return {}
    out: dict[str, tuple[str, str]] = {}
    text = open(notice, encoding="utf-8").read()
    # The wave writes four-line blocks: path / source title / licence — author / URL.
    for m in re.finditer(r"^(kb/\S+)\n\s+(.+)\n\s+(.+)\n\s+(https?://\S+)", text, re.M):
        out[m.group(1)] = (f"{m.group(3)} · {m.group(2)}", m.group(4))
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
        licence, page = prov.get(r["image"], ("bundled before the provenance convention", ""))
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
