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

# Prefer a single muxed stream. Separate video and audio would need a merging media source on the
# player side — real machinery for a first pass — and every site that offers adaptive formats also
# offers something progressive. The last fallback is "whatever you have".
FORMAT = "best[ext=mp4][acodec!=none][vcodec!=none]/best[acodec!=none][vcodec!=none]/best"

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


def _pick(info: dict) -> dict:
    """The chosen stream, plus an audio-only address when one is separately available."""
    stream = info.get("url") or ""
    audio = ""
    for f in info.get("formats") or []:
        if f.get("acodec") not in (None, "none") and f.get("vcodec") in (None, "none"):
            # Formats arrive worst-first, so the last audio-only one is the best available.
            audio = f.get("url") or audio
    if not stream and info.get("requested_formats"):
        # A merged selection: take the video half as the stream and the audio half beside it.
        for f in info["requested_formats"]:
            if f.get("vcodec") not in (None, "none"):
                stream = f.get("url") or stream
            elif f.get("acodec") not in (None, "none"):
                audio = f.get("url") or audio
    return {"stream": stream, "audio": audio}


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
        "uploader": info.get("uploader") or info.get("channel") or "",
        "thumbnail": info.get("thumbnail") or "",
        "page": info.get("webpage_url") or url,
        "expires": _expiry_ms(best),
        "live": bool(info.get("is_live")),
        "extractor": info.get("extractor_key") or "",
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
