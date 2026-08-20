"""Resolve a page address to something playable, using yt-dlp.

⚠️ **NOTHING EVER RAISES ACROSS THE JNI BOUNDARY.** A Python exception reaching Chaquopy arrives in
Kotlin as a `PyException` whose message is a formatted traceback — technically catchable, useless to
show anyone, and it loses the one thing worth knowing, which is *why*. So every path here returns a
JSON object, and a failure is `{"error": ..., "kind": ...}` rather than a stack unwind.

⚠️ **The classification happens HERE and not in Kotlin**, because this is the only side that has
yt-dlp's exception types. "This is private", "this is geo-blocked", "I do not support that site" and
"the network failed" want four different sentences on screen, and by the time a message string has
crossed the boundary the type that distinguished them is gone.

⚠️ Extraction is against some sites' terms of service. This is a private, sideloaded, single-user
application and its owner has authorised it; that is stated plainly rather than papered over.
"""

import json
import re
from urllib.parse import parse_qs, urlparse

# Adaptive first, muxed as the fallback.
#
# ⚠️ This used to ask only for a muxed stream, on the reasoning that separate tracks would need a
# merging media source on the player side and "every site that offers adaptive formats also offers
# something progressive". The second half of that has stopped being true: sites have been retiring
# muxed formats, and the one that survives is well below the best available — so the app was quietly
# playing a much worse picture than it could. The player now merges, so ask for the good one.
#
# The chain degrades rather than failing: best video + best audio, then a muxed mp4, then anything
# muxed, then whatever exists at all. A site with no adaptive formats lands on exactly what it landed
# on before.
FORMAT = (
    "bestvideo+bestaudio/"
    "best[ext=mp4][acodec!=none][vcodec!=none]/"
    "best[acodec!=none][vcodec!=none]/"
    "best"
)

# Matched against the failure text ONLY when the exception type did not already settle it. Ordered,
# and each is a phrase rather than a word: "private" alone matches "privately owned".
_BLOCKED_PATTERNS = (
    "private video",
    "is private",
    "sign in to confirm",
    "age-restricted",
    "age restricted",
    "not available in your country",
    "blocked it in your country",
    "video unavailable",
    "has been removed",
    "account associated with this video has been terminated",
    "members-only",
    "requires payment",
    "login required",
)


class _Silent:
    """A yt-dlp logger that says nothing.

    yt-dlp calls `debug`/`info`/`warning`/`error` on whatever it is given, so all four have to
    exist — a partial stub raises AttributeError from inside the extractor, turning a clean refusal
    into an unrelated crash. Everything worth knowing already comes back in the return value.
    """

    def debug(self, msg):  # noqa: D102 - the interface is yt-dlp's
        pass

    def info(self, msg):  # noqa: D102
        pass

    def warning(self, msg):  # noqa: D102
        pass

    def error(self, msg):  # noqa: D102
        pass


def _expiry_ms(url: str) -> int:
    """When the resolved address stops working, in epoch milliseconds, or 0 if it does not say.

    ⚠️ Read out of the URL rather than the info dict, because yt-dlp does not carry an expiry field —
    the signed address carries its own deadline as a query parameter. Zero is returned honestly when
    there is none, and the Kotlin side treats an unknown expiry as ALREADY STALE, so the cost of not
    knowing is a re-resolve rather than a video that dies part-way through.
    """
    try:
        qs = parse_qs(urlparse(url).query)
        for key in ("expire", "expires", "Expires", "oe"):
            v = qs.get(key)
            if v and v[0].isdigit():
                seconds = int(v[0])
                # Some hosts state it in milliseconds already. A plain epoch-seconds value is ten
                # digits until the year 2286; anything much larger is not seconds.
                return seconds if seconds > 10**12 else seconds * 1000
    except Exception:  # noqa: BLE001 - an unparseable URL simply has no stated expiry
        pass
    return 0


def _classify(exc) -> str:
    """Coarse reason, from the exception type first and its text only as a fallback."""
    name = type(exc).__name__
    text = str(exc).lower()
    if name == "UnsupportedError":
        return "UNSUPPORTED"
    if any(p in text for p in _BLOCKED_PATTERNS):
        return "BLOCKED"
    if "unable to download" in text or "connection" in text or "timed out" in text:
        return "FAILED"
    if name in ("ExtractorError", "DownloadError"):
        # An extractor that ran and could not find media is different from one that refused: the
        # first is usually a site change worth reporting, the second is about this video.
        return "NO_STREAM" if "no video formats" in text or "no formats" in text else "FAILED"
    return "FAILED"


def _headers(f: dict) -> dict:
    """The headers yt-dlp says this format's address must be fetched with.

    ⚠️ **Not optional, and dropping them is what made playback fail with a bad HTTP status.** A signed
    media URL is minted for the client that asked for it; a later request carrying a different
    User-Agent — or missing the Accept/Sec-Fetch values the extractor sent — gets refused. yt-dlp
    knows exactly what it used and puts it in `http_headers`; this app used to throw that away and
    then wonder why the address it had just been handed came back 403.

    Values are coerced to strings because they cross into Kotlin as JSON and a stray non-string would
    fail to decode into a Map<String, String> at the far end.
    """
    raw = f.get("http_headers") or {}
    if not isinstance(raw, dict):
        return {}
    return {str(k): str(v) for k, v in raw.items() if k and v}


def _pick(info: dict) -> dict:
    """The chosen video and audio tracks, each with the headers its address needs.

    Three shapes come back from yt-dlp and all three are handled:

    * a **muxed** selection — one `url` on `info` carrying both, with no separate audio;
    * an **adaptive** selection — `requested_formats` holding a video half and an audio half, which
      the player merges;
    * neither, in which case the best audio-only format in the list is offered on its own so LISTEN
      still works even when nothing playable as video was found.
    """
    stream = info.get("url") or ""
    stream_headers = _headers(info) if stream else {}
    audio = ""
    audio_headers = {}
    # ⚠️ Tracked, rather than asking "is requested_formats set" at the end. That question is one step
    # removed from the one that matters, which is whether BOTH halves in front of the player came out
    # of an adaptive pair. If the video half carried no address, `stream` is still the muxed fallback
    # — and merging THAT with a separate audio track plays the audio twice.
    video_from_pair = False
    audio_from_pair = False

    if info.get("requested_formats"):
        # An adaptive selection. Take the video half as the stream and the audio half beside it, and
        # keep each one's OWN headers — yt-dlp emits them per format and the two genuinely differ.
        for f in info["requested_formats"]:
            if f.get("vcodec") not in (None, "none"):
                if f.get("url"):
                    stream, stream_headers = f["url"], _headers(f)
                    video_from_pair = True
            elif f.get("acodec") not in (None, "none"):
                if f.get("url"):
                    audio, audio_headers = f["url"], _headers(f)
                    audio_from_pair = True

    if not audio:
        for f in info.get("formats") or []:
            if f.get("acodec") not in (None, "none") and f.get("vcodec") in (None, "none"):
                # Formats arrive worst-first, so the last audio-only one is the best available.
                if f.get("url"):
                    audio, audio_headers = f["url"], _headers(f)

    return {
        "stream": stream,
        "audio": audio,
        "stream_headers": stream_headers,
        "audio_headers": audio_headers,
        # ⚠️ Whether the player has to MERGE, decided here rather than inferred there. "Both fields
        # are set" is not the same question: a muxed stream can sit beside a separate audio-only
        # rendition for LISTEN, and merging those would play the audio twice. True only when both
        # addresses came out of the same adaptive pair.
        "adaptive": video_from_pair and audio_from_pair,
    }


def resolve(url: str) -> str:
    """Resolve `url`, returning a JSON object. Never raises."""
    try:
        from yt_dlp import YoutubeDL
    except Exception as exc:  # noqa: BLE001
        return json.dumps({"error": "{}: {}".format(type(exc).__name__, exc), "kind": "UNAVAILABLE"})

    opts = {
        "quiet": True,
        "no_warnings": True,
        # ⚠️ A REAL PRIVACY FIX, not tidiness. `quiet` suppresses progress but NOT errors: yt-dlp
        # still writes a failure to stderr, and that line contains the address being resolved.
        # On Android stderr goes to logcat, and this app's diagnostic uploader includes its own
        # logcat in the bundles it sends — so a failed resolve would put a piece of viewing history
        # into an uploaded report. The credential scrubber does not catch it, because a video URL is
        # not a credential; it is history. Silencing the logger keeps the reason in the return value,
        # which never leaves the device.
        "logger": _Silent(),
        # And do not let a fatal error print separately either.
        "noprogress": True,
        "skip_download": True,
        # ⚠️ A playlist address would otherwise extract every entry — dozens of network round trips
        # for a request that meant "play this one".
        "noplaylist": True,
        "format": FORMAT,
        # No cache directory: Chaquopy's filesystem is not a normal one and a cache write failure
        # inside the extractor is a confusing way to lose a resolve.
        "cachedir": False,
        "extract_flat": False,
    }
    try:
        with YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=False)
    except Exception as exc:  # noqa: BLE001 - the classification IS the product
        return json.dumps({"error": str(exc)[:400], "kind": _classify(exc)})

    if not info:
        return json.dumps({"error": "the extractor returned nothing", "kind": "NO_STREAM"})
    # A playlist can still arrive when `noplaylist` could not be honoured; take the first entry.
    if info.get("_type") == "playlist":
        entries = [e for e in (info.get("entries") or []) if e]
        if not entries:
            return json.dumps({"error": "that address is an empty playlist", "kind": "NO_STREAM"})
        info = entries[0]

    picked = _pick(info)
    if not picked["stream"] and not picked["audio"]:
        return json.dumps({"error": "no playable stream in the result", "kind": "NO_STREAM"})

    best = picked["stream"] or picked["audio"]
    return json.dumps({
        "id": info.get("id") or "",
        "title": info.get("title") or "",
        "duration": float(info.get("duration") or 0),
        "stream": picked["stream"],
        "audio": picked["audio"],
        "stream_headers": picked["stream_headers"],
        "audio_headers": picked["audio_headers"],
        "adaptive": picked["adaptive"],
        "uploader": info.get("uploader") or info.get("channel") or "",
        "thumbnail": info.get("thumbnail") or "",
        "page": info.get("webpage_url") or url,
        "expires": _expiry_ms(best),
        "live": bool(info.get("is_live")),
        "extractor": info.get("extractor_key") or "",
    })


def download(url: str, dest_dir: str) -> str:
    """Download the media at `url` into `dest_dir`, returning JSON. Never raises.

    The harvester behind the held volume key. The whole download runs inside yt-dlp rather than a
    plain HTTP GET of the resolved address, because "the stream" is frequently not a file — an HLS
    address is a playlist of thousands of fragments, and yt-dlp is the thing that knows how to turn
    that into one playable file on disk.

    ⚠️ `dest_dir` is the app's own sandboxed storage, passed in from Kotlin — this module never picks
    a location itself, so where harvested media lives is decided (and cleaned up) in exactly one
    place on the Kotlin side.
    """
    import os
    try:
        from yt_dlp import YoutubeDL
    except Exception as exc:  # noqa: BLE001
        return json.dumps({"error": "{}: {}".format(type(exc).__name__, exc), "kind": "UNAVAILABLE"})

    try:
        os.makedirs(dest_dir, exist_ok=True)
    except Exception as exc:  # noqa: BLE001
        return json.dumps({"error": "cannot create {}: {}".format(dest_dir, exc), "kind": "FAILED"})

    opts = {
        "quiet": True,
        "no_warnings": True,
        "logger": _Silent(),  # same privacy reason as resolve(): a failure line carries the URL
        "noprogress": True,
        "noplaylist": True,
        "format": FORMAT,
        "cachedir": False,
        # ASCII-safe names, title capped at 80 BYTES (the B conversion) — a 300-byte emoji title
        # would otherwise exceed the filesystem's 255-byte limit and fail after downloading.
        "restrictfilenames": True,
        "outtmpl": os.path.join(dest_dir, "%(title).80B [%(id)s].%(ext)s"),
        "retries": 2,
    }
    try:
        with YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=True)
            if not info:
                return json.dumps({"error": "the extractor returned nothing", "kind": "NO_STREAM"})
            if info.get("_type") == "playlist":
                entries = [e for e in (info.get("entries") or []) if e]
                if not entries:
                    return json.dumps({"error": "that address is an empty playlist", "kind": "NO_STREAM"})
                info = entries[0]
            # Where the file actually landed. `requested_downloads` is authoritative on modern
            # yt-dlp (it reflects merges and remuxes); prepare_filename is the documented fallback.
            path = ""
            for rd in info.get("requested_downloads") or []:
                path = rd.get("filepath") or path
            if not path:
                path = ydl.prepare_filename(info)
    except Exception as exc:  # noqa: BLE001
        return json.dumps({"error": str(exc)[:400], "kind": _classify(exc)})

    size = 0
    try:
        if path and os.path.exists(path):
            size = os.path.getsize(path)
    except Exception:  # noqa: BLE001
        pass
    if not path or size <= 0:
        return json.dumps({"error": "the download produced no file", "kind": "FAILED"})
    return json.dumps({
        "file": os.path.basename(path),
        "path": path,
        "bytes": size,
        "title": info.get("title") or "",
    })


def video_id(url: str) -> str:
    """The source's own id for `url`, without resolving a stream.

    ⚠️ Worth having separately because the skip database is keyed on it and a lookup should not need
    a full extraction — which costs a network round trip and can fail for reasons that have nothing
    to do with knowing which video this is. Returns "" when the address is not one we can key.
    """
    try:
        m = re.search(r"(?:v=|/shorts/|youtu\.be/|/embed/|/v/)([A-Za-z0-9_-]{11})", url)
        return m.group(1) if m else ""
    except Exception:  # noqa: BLE001
        return ""


def _flat_item(e) -> dict | None:
    """Normalize one extract_flat entry, or None when it is not a listable video.

    ⚠️ Flat entries are the least stable shape yt-dlp emits — measured on the real service, `url`
    may be a full watch URL or a bare id, `duration` may be None (shorts, live), and the thumbnail
    may be a scalar `thumbnail`, a `thumbnails` list (best-quality-last), or absent entirely. Trust
    nothing; every field is read defensively and the fallbacks are stated per field.
    """
    try:
        if not isinstance(e, dict):
            return None
        vid = str(e.get("id") or "")
        page = str(e.get("url") or e.get("webpage_url") or "")
        if page and not page.startswith("http"):
            # A bare id in `url` — the flat youtube extractor does this. Rebuild the watch URL.
            page = "https://www.youtube.com/watch?v={}".format(page)
        if not page and re.fullmatch(r"[A-Za-z0-9_-]{11}", vid):
            page = "https://www.youtube.com/watch?v={}".format(vid)
        title = str(e.get("title") or "")
        if not page or not title or title == "[Deleted video]" or title == "[Private video]":
            return None
        thumb = ""
        thumbs = e.get("thumbnails")
        if isinstance(thumbs, list) and thumbs:
            thumb = str((thumbs[-1] or {}).get("url") or "")
        if not thumb:
            thumb = str(e.get("thumbnail") or "")
        if not thumb and re.fullmatch(r"[A-Za-z0-9_-]{11}", vid):
            # YouTube's stable per-id thumbnail host — a valid fallback for exactly this service.
            thumb = "https://i.ytimg.com/vi/{}/hqdefault.jpg".format(vid)
        try:
            duration = float(e.get("duration") or 0)
        except Exception:  # noqa: BLE001 - a non-numeric duration is "not stated"
            duration = 0.0
        try:
            views = int(e.get("view_count") or 0)
        except Exception:  # noqa: BLE001
            views = 0
        return {
            "id": vid,
            "title": title,
            "duration": duration,
            "uploader": str(e.get("uploader") or e.get("channel") or ""),
            "thumbnail": thumb,
            "page": page,
            "live": e.get("live_status") == "is_live",
            "views": views,
        }
    except Exception:  # noqa: BLE001 - one malformed entry must never sink the page
        return None


def _browse(target: str, limit: int) -> str:
    """Shared flat listing run. Returns JSON {"items":[...]} or {"error","kind"}. Never raises.

    ⚠️ THE OPTS ARE BUILT FRESH, NOT COPIED FROM resolve()'s — and that is load-bearing, not style.
    resolve() sets `noplaylist=True`, which collapses a playlist to its first entry; a search result
    IS a playlist, so copying that flag silently truncates a 25-result search to one item. resolve()
    also sets `format`, which is meaningless without per-video extraction and errors on flat entries
    under some yt-dlp versions. What browse needs is `extract_flat`: one cheap round trip that lists
    without resolving any stream — resolving happens later, on the one item that gets tapped.
    """
    try:
        from yt_dlp import YoutubeDL
    except Exception as exc:  # noqa: BLE001
        return json.dumps({"error": "{}: {}".format(type(exc).__name__, exc), "kind": "UNAVAILABLE"})

    opts = {
        "quiet": True,
        "no_warnings": True,
        # ⚠️ Same privacy reason as resolve(): a failure line carries the TARGET, and a search query
        # is viewing history exactly as a URL is. Nothing may reach stderr → logcat → the diagnostic
        # uploader's bundles.
        "logger": _Silent(),
        "noprogress": True,
        "cachedir": False,
        "extract_flat": "in_playlist",
        "playlist_items": "1:{}".format(limit),
    }
    try:
        with YoutubeDL(opts) as ydl:
            info = ydl.extract_info(target, download=False)
    except Exception as exc:  # noqa: BLE001 - the classification IS the product
        return json.dumps({"error": str(exc)[:400], "kind": _classify(exc)})

    if not info:
        return json.dumps({"error": "the listing returned nothing", "kind": "NO_STREAM"})
    entries = [x for x in (info.get("entries") or []) if x]
    # Some tab extractors return a playlist OF playlists (section shelves). Flatten exactly one
    # level and no more — deeper nesting is a page shape we do not understand, not data to guess at.
    flat = []
    for x in entries:
        if isinstance(x, dict) and x.get("_type") == "playlist":
            flat.extend(y for y in (x.get("entries") or []) if y)
        else:
            flat.append(x)
    items = []
    for x in flat:
        item = _flat_item(x)
        if item:
            items.append(item)
        if len(items) >= limit:
            break
    if not items:
        return json.dumps({"error": "nothing listable in the result", "kind": "NO_STREAM"})
    return json.dumps({"items": items})


def search(query: str, limit: str = "25") -> str:
    """Search for videos, returning a flat JSON listing. Never raises.

    ⚠️ `limit` arrives as a STRING because PythonRuntime's contract is string in, string out —
    marshalling an int across JNI is a conversion whose failure mode is a runtime exception on a
    device. Clamped to 1..50; an unparseable value falls back to 25 rather than failing the search.

    NO trending() sibling exists, deliberately: YouTube retired /feed/trending (it redirects to the
    home page — measured, not assumed), so "what to show before anyone types" is the Kotlin side's
    job, built from searches over what the app already knows.
    """
    q = (query or "").strip()
    if not q:
        return json.dumps({"error": "empty query", "kind": "UNSUPPORTED"})
    try:
        n = max(1, min(50, int(limit)))
    except Exception:  # noqa: BLE001
        n = 25
    return _browse("ytsearch{}:{}".format(n, q), n)
