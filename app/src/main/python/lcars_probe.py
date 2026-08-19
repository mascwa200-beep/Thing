"""The toolchain proof, and nothing more.

⚠️ This module exists to answer ONE question: did a real CPython interpreter get packaged into the
APK, unpack itself on the device, and run our code? It deliberately imports nothing outside the
standard library, so that when it fails the failure is unambiguously the toolchain rather than a
dependency — the same reason the CMake/NDK proof shipped by itself before whisper.cpp and llama.cpp
were built on top of it.

Everything here returns a plain string, because a string crosses the JNI boundary with no marshalling
question to get wrong: `PyObject.toJava(String::class.java)` is exact. Returning a dict would drag in
`PyMap` conversion semantics that are worth understanding when there is something to convert, and are
noise while proving an interpreter starts.
"""

import platform
import sys


def report() -> str:
    """One line naming the interpreter that is actually running on the phone.

    The build ABI is checked from Python's own view rather than taken from the Gradle config,
    because the interesting failure is the two disagreeing.
    """
    return "python {v} · {impl} · {machine}".format(
        v=platform.python_version(),
        impl=platform.python_implementation(),
        machine=platform.machine() or "unknown",
    )


def version() -> str:
    """Just the version, for anything that wants to compare rather than display."""
    return platform.python_version()


def echo(text: str) -> str:
    """Round-trip a string through Python.

    Proves the call path in both directions — an argument marshalled in, a value marshalled out —
    which `report()` alone does not: a function taking no arguments would still work if argument
    conversion were broken.
    """
    return "".join(reversed(str(text)))


def stdlib_check() -> str:
    """Prove the standard library was packaged, not just the interpreter binary.

    ⚠️ This is a distinct failure from "Python did not start". Chaquopy ships the stdlib as a zip
    asset extracted on first run, so a build can produce a working interpreter that cannot import
    anything — and the extractor that lands on top of this will need `ssl`, `json` and `zlib` in
    particular. Each is reported by name so a partial packaging says which piece is missing rather
    than failing as one opaque import error.
    """
    results = []
    for name in ("json", "ssl", "zlib", "sqlite3", "hashlib"):
        try:
            __import__(name)
            results.append(name)
        except Exception as exc:  # noqa: BLE001 - the reason is the payload
            results.append("{n}!({e})".format(n=name, e=type(exc).__name__))
    return " ".join(results) + " | " + sys.platform


def extractor_check() -> str:
    """Whether the pip-installed extractor actually imports here, and which version.

    ⚠️ A DISTINCT question from "did the wheel get packaged", which the build already asserts by
    looking inside the APK. A pure-Python wheel can be present and still fail to import on Android —
    Chaquopy serves packages out of a zip, so anything reading `__file__` or a data file next to
    itself needs `extractPackages` to be told about it, and the failure is an ImportError at runtime
    that no build check can see.

    The version is reported because yt-dlp ships most weeks and extraction breakage is
    version-specific: "which one was in that APK" is the first question when a site stops working.
    """
    try:
        import yt_dlp
        return "yt-dlp " + getattr(yt_dlp, "__version__", "?")
    except Exception as exc:  # noqa: BLE001 - the reason IS the answer
        return "yt-dlp unavailable: {e}: {m}".format(e=type(exc).__name__, m=exc)
