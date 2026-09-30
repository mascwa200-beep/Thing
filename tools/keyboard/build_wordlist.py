#!/usr/bin/env python3
"""Build the LCARS keyboard's word list from AOSP LatinIME's English dictionary source.

    tools/keyboard/build_wordlist.py [--source en_US_wordlist.combined.gz] [--check]

The source is AOSP's `dictionaries/en_US_wordlist.combined.gz` (Apache-2.0), the list the stock
Android keyboard's English dictionary is compiled from:

    https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/refs/heads/main/dictionaries/en_US_wordlist.combined.gz

Fetched with `?format=TEXT` googlesource answers it base64-encoded; `--source` takes the raw gzip,
the base64 of it, or the plain text, whichever is to hand. `--check` rebuilds and compares against
what is committed without writing, so the 1.2 MB asset is reproducible rather than opaque.

## What is kept, and why

- **Frequency 40 and up**, of the source's 0-255 scale. Measured over the whole list: 160,668 words
  once the flagged ones are gone, 91,541 at 40 and up (about 1.2 MB), 59,773 at 60, 30,681 at 80.
  Breadth is what stops autocorrect "correcting" a real word into a commoner one, so the cut is made
  low; below 40 the list is mostly surnames and inflections of rare words.
- ⚠️ **Nothing flagged `possibly_offensive` or `not_a_word`.** A suggestion strip that offers a slur
  in the middle of a message is worse than one that offers nothing.
- One `word<TAB>freq` line each, **sorted by the lowercased word, then the word** — the order the
  keyboard binary-searches in, and the order `KeyboardWordListAssetTest` checks with Kotlin's own
  comparison.
"""
import argparse
import base64
import gzip
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "app/src/main/assets/keyboard/en_US.tsv"
MIN_FREQ = 40
DROP_FLAGS = ("possibly_offensive", "not_a_word")
LINE = re.compile(r"^ word=([^,]*),f=(\d+),flags=([^,]*)")


def read_source(path: Path) -> str:
    raw = path.read_bytes()
    if raw[:2] != b"\x1f\x8b":
        try:
            decoded = base64.b64decode(raw, validate=False)
            if decoded[:2] == b"\x1f\x8b":
                raw = decoded
        except Exception:
            pass
    if raw[:2] == b"\x1f\x8b":
        raw = gzip.decompress(raw)
    return raw.decode("utf-8")


def build(text: str) -> str:
    words = {}
    for line in text.splitlines():
        m = LINE.match(line)
        if not m:
            continue
        word, freq, flags = m.group(1), int(m.group(2)), m.group(3)
        if any(f in flags for f in DROP_FLAGS) or freq < MIN_FREQ:
            continue
        if not word or "\t" in word or "\n" in word:
            continue
        words[word] = max(freq, words.get(word, 0))
    rows = sorted(words.items(), key=lambda kv: (kv[0].lower(), kv[0]))
    return "".join(f"{w}\t{f}\n" for w, f in rows)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--source", type=Path, required=True)
    ap.add_argument("--out", type=Path, default=OUT)
    ap.add_argument("--check", action="store_true")
    a = ap.parse_args()
    out = build(read_source(a.source))
    n = out.count("\n")
    if a.check:
        have = a.out.read_text(encoding="utf-8") if a.out.exists() else ""
        if have != out:
            print(f"DIFFERS: {a.out} is not what the source builds ({n} words)")
            return 1
        print(f"ok: {a.out} matches ({n} words)")
        return 0
    a.out.parent.mkdir(parents=True, exist_ok=True)
    a.out.write_text(out, encoding="utf-8")
    print(f"wrote {a.out}: {n} words, {len(out.encode())} bytes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
