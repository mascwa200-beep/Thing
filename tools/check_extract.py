#!/usr/bin/env python3
"""Local gate for `app/src/main/python/lcars_extract.py`.

⚠️ **Nothing else checks this file.** CI runs `:app:testDebugUnitTest`, which is Kotlin; the bundled
Python is compiled by nobody and tested by nothing, and it holds two rules that are expensive to get
wrong:

* `_redact` is a **privacy guarantee** — it is what allows yt-dlp's own words to be shown and
  uploaded in a diagnostic bundle without carrying a piece of viewing history along with them;
* `_pick`'s `adaptive` decision is the difference between a video that plays and one that plays
  perfectly, in silence, reporting no error at all.

Run before committing a change to that file:

    python3 tools/check_extract.py           # offline, fast, no network
    python3 tools/check_extract.py --live    # also runs real yt-dlp once

⚠️ `--live` needs yt-dlp installed *and* an IP YouTube will talk to. A datacenter address gets
bot-checked, so a failure there is not evidence of a defect in this file — which is exactly why it is
opt-in rather than part of the default run.
"""

from __future__ import annotations

import importlib.util
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
TARGET = ROOT / "app" / "src" / "main" / "python" / "lcars_extract.py"


def load(path: pathlib.Path):
    spec = importlib.util.spec_from_file_location("lcars_extract_under_test", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def check(lx) -> list[str]:
    """Every rule worth holding. Returns a list of failures, empty when clean."""
    bad: list[str] = []

    def want(cond, msg):
        if not cond:
            bad.append(msg)

    # --- redaction ------------------------------------------------------------------------------
    media = "https://rr3---sn-x.googlevideo.com/videoplayback?expire=1&sig=SECRETTOKEN&id=abc"
    out = lx._redact(f"Failed to fetch {media}")
    want("SECRETTOKEN" not in out, "redaction leaked a signed token")
    want("googlevideo" not in out, "redaction leaked a media host")
    want("<link>" in out, "a removed address should leave a marker, not vanish")

    doc = "See https://github.com/yt-dlp/yt-dlp/wiki/EJS for details"
    want("wiki/EJS" in lx._redact(doc), "a documentation link should survive — it is the useful half")

    # A documentation URL still loses its query: that is where tokens live, on any host.
    want("?" not in lx._redact("https://github.com/a/b?token=zzz"), "query kept on a doc link")
    # Host-suffix matching must not be fooled by a lookalike domain.
    want("<link>" in lx._redact("https://github.com.evil.tld/x"), "suffix match fooled by a lookalike")

    # --- expiry ---------------------------------------------------------------------------------
    later, sooner, silent = "h://a?expire=2000000000", "h://b?expire=1900000000", "h://c"
    want(lx._earliest_expiry(later, sooner) == 1900000000 * 1000,
         "the shorter-lived half of a pair must win")
    want(lx._earliest_expiry(later, silent) == 2000000000 * 1000,
         "an unknown expiry must not win a min() against a real one")
    want(lx._earliest_expiry(silent, "") == 0, "no stated expiry anywhere should report 0")

    # --- the merge decision ---------------------------------------------------------------------
    # A pair whose audio half carries no address: audio comes from the formats list instead, and the
    # stream is still VIDEO ONLY. Merging is mandatory or it plays silent.
    orphaned = {
        "requested_formats": [
            {"vcodec": "av01", "acodec": "none", "url": "https://v"},
            {"vcodec": "none", "acodec": "opus"},
        ],
        "formats": [{"vcodec": "none", "acodec": "opus", "url": "https://a"}],
    }
    want(lx._pick(orphaned)["adaptive"] is True,
         "a video-only stream with separate audio must merge, or it plays in silence")

    # A muxed stream beside a separate audio-only rendition (what LISTEN uses). Merging these plays
    # the audio twice.
    muxed = {
        "url": "https://muxed", "acodec": "aac", "vcodec": "h264",
        "formats": [{"vcodec": "none", "acodec": "opus", "url": "https://a"}],
    }
    want(lx._pick(muxed)["adaptive"] is False, "a muxed stream must never be merged with audio")

    # A fully-muxed entry can appear inside requested_formats; taken as the video half it must not
    # then be merged either.
    muxed_pair = {
        "requested_formats": [{"vcodec": "h264", "acodec": "aac", "url": "https://both"}],
        "formats": [{"vcodec": "none", "acodec": "opus", "url": "https://a"}],
    }
    want(lx._pick(muxed_pair)["adaptive"] is False,
         "a muxed entry inside requested_formats must not be merged")

    # Headers travel with their own track, never crossed.
    paired = {
        "requested_formats": [
            {"vcodec": "av01", "acodec": "none", "url": "https://v", "http_headers": {"User-Agent": "V"}},
            {"vcodec": "none", "acodec": "opus", "url": "https://a", "http_headers": {"User-Agent": "A"}},
        ],
    }
    picked = lx._pick(paired)
    want(picked["stream_headers"].get("User-Agent") == "V", "video header set went to the wrong track")
    want(picked["audio_headers"].get("User-Agent") == "A", "audio header set went to the wrong track")

    # --- the browse path stays silent -----------------------------------------------------------
    # A search query is viewing history that no URL pattern can catch, so that path must capture
    # nothing at all.
    silent_logger = lx._Silent()
    silent_logger.warning("no results for <what the user typed>")
    want(not hasattr(silent_logger, "notes"), "the browse logger must not accumulate anything")

    return bad


def live(lx) -> list[str]:
    import json
    bad = []
    raw = lx.resolve("https://www.youtube.com/watch?v=BaW_jenozKc")
    result = json.loads(raw)
    if "notes" not in result:
        bad.append("resolve() must carry `notes` on every path, success or failure")
    print("  live resolve ->", result.get("kind", "success"))
    for note in result.get("notes", []):
        print("   ", note[:140])
    return bad


def main() -> int:
    if not TARGET.exists():
        print(f"cannot find {TARGET}", file=sys.stderr)
        return 2
    lx = load(TARGET)
    failures = check(lx)
    if "--live" in sys.argv:
        print("live check (needs yt-dlp and an IP YouTube will talk to):")
        failures += live(lx)
    if failures:
        print(f"\n{len(failures)} FAILED:", file=sys.stderr)
        for f in failures:
            print(f"  - {f}", file=sys.stderr)
        return 1
    print("lcars_extract: all checks pass")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
