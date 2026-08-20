"""The JavaScript runtime yt-dlp needs for YouTube, served from inside this process.

⚠️ **WHY THIS FILE EXISTS.** yt-dlp has required an external JavaScript runtime for full YouTube
support since **2025.11.12**. Run against the exact pinned version, extraction says so in its own
words: *"No supported JavaScript runtime could be found. Only deno is enabled by default... YouTube
extraction without a JS runtime has been deprecated, and some formats may be missing."* Chaquopy
ships a bare CPython, so the phone has no Deno, Node, Bun or QuickJS anywhere on it.

⚠️ **WHY A PROVIDER RATHER THAN A BINARY.** yt-dlp invokes every built-in runtime through `Popen`
with a configurable path, so shipping the `qjs` executable looks like the easy route. It is not:
Android 10+ refuses to exec anything outside `nativeLibraryDir`, which needs
`extractNativeLibs=true` — a flag that applies to **every** `.so` in the app on an APK already
around 144 MB. So QuickJS is compiled into `liblcarsnative.so` instead and reached through JNI, and
this file is the seam. `EJSBaseJCP` requires implementing exactly one method.

⚠️ **THE CONTRACT IS STANDARD OUTPUT.** Read out of yt-dlp's own `_construct_stdin`, the script
handed to `_run_js_runtime` ends with `console.log(JSON.stringify(jsc(...)))` and the caller does
`json.loads()` on what comes back. So the return value is what `console.log` printed — not the value
the last statement evaluated to. `js_jni.cpp` installs its own console for exactly this reason.

⚠️ **THIS COUPLES TO A yt-dlp INTERNAL, AND MUST DEGRADE RATHER THAN BREAK.** `EJSBaseJCP` lives in
a `_builtin` package, which upstream is free to move. The pin makes that controlled, but every
import here is guarded and [available] reports honestly: when anything is missing the app falls back
to extraction without a JS runtime, which is exactly today's behaviour. Losing some formats is a bad
outcome; losing extraction altogether is a much worse one.
"""

from __future__ import annotations

# What went wrong, for the diagnostics the extractor already reports. Empty means it registered.
setup_error: str | None = None
registered: bool = False

_TIMEOUT_MS = 120_000
"""Measured rather than guessed — see `JsRuntime.DEFAULT_TIMEOUT_MS`, which carries the numbers."""

try:
    from java import jclass
except ImportError as e:  # pragma: no cover - only off-device
    jclass = None
    setup_error = f'not running under Chaquopy: {e}'

_engine = None


def _runtime():
    """The Kotlin `JsRuntime` singleton, looked up once.

    ⚠️ Its Python-facing methods are `@JvmStatic`, so they hang off the class rather than an
    `INSTANCE` field. That is deliberate on the Kotlin side: reaching a Kotlin `object`'s instance
    methods through Chaquopy means going through `INSTANCE` and, for anything taking a lambda,
    building a dynamic proxy — for no gain when the whole surface is three calls.
    """
    global _engine
    if _engine is None:
        _engine = jclass('dev.mascwa.pulse.data.media.JsRuntime')
    return _engine


def available() -> bool:
    """True when the engine is really in this build and the provider registered."""
    if not registered or jclass is None:
        return False
    try:
        return bool(_runtime().available())
    except Exception:
        return False


def engine_version() -> str | None:
    """The engine's version, for the extractor's notes. None when there is no engine."""
    if jclass is None:
        return None
    try:
        return _runtime().version()
    except Exception:
        return None


if setup_error is None:
    try:
        from yt_dlp.extractor.youtube.jsc._builtin.ejs import EJSBaseJCP
        from yt_dlp.extractor.youtube.jsc.provider import (
            JsChallengeProvider,
            JsChallengeProviderError,
            JsChallengeRequest,
            register_preference,
            register_provider,
        )
        from yt_dlp.utils._jsruntime import JsRuntimeInfo
    except Exception as e:  # noqa: BLE001 - any import failure must degrade, not raise
        setup_error = f'yt-dlp JS challenge API not available: {e}'


if setup_error is None:

    _RUNTIME_NAME = 'lcars-quickjs'

    _INFO_CACHE: list = []  # 0 or 1 entries: the computed runtime_info, or nothing yet.

    def _runtime_info():
        """The engine described to yt-dlp, computed once.

        Absence is cached as well as presence — every input is fixed for the life of the process
        (`NativeBridge.available` is a `val` assigned at class-init, `JsRuntime.available()` is a
        `by lazy`), so a second look could not return a different answer and would only pay the
        JNI hop again.
        """
        if _INFO_CACHE:
            return _INFO_CACHE[0]
        # ⚠️ Do NOT cache before registration completes. `registered` is set on the module's last
        # line, and `available()` is False until it is — so a call that somehow arrived during
        # class definition (a future decorator that probes the provider, say) would otherwise
        # freeze "no engine" for the life of the process. Nothing does that today; this costs one
        # comparison and removes a way for that to become permanent silently.
        if not registered:
            return None
        info = None
        if available():
            version = engine_version() or '0.0.0'
            parts = []
            for piece in version.split('.')[:3]:
                digits = ''.join(c for c in piece if c.isdigit())
                parts.append(int(digits) if digits else 0)
            info = JsRuntimeInfo(
                name=_RUNTIME_NAME,
                path='(linked into liblcarsnative.so)',
                version=version,
                version_tuple=tuple(parts),
                supported=True,
            )
        _INFO_CACHE.append(info)
        return info

    @register_provider
    class LcarsJsJCP(EJSBaseJCP):
        """Solves YouTube's JS challenges in the QuickJS compiled into this app.

        ⚠️ The class name **must** end in `JCP`: `PROVIDER_KEY` is the class name minus that
        suffix, asserted by `register_provider_generic`, and the key is also what must not collide
        with the built-in `QuickJSJCP` (key `QuickJS`). This one's key is `LcarsJs`.
        """

        PROVIDER_NAME = _RUNTIME_NAME
        JS_RUNTIME_NAME = _RUNTIME_NAME
        BUG_REPORT_LOCATION = 'the LCARS app itself - this provider is not part of yt-dlp'

        @property
        def runtime_info(self):
            """Override, because the base class looks for an *external* runtime and there is none.

            ⚠️ This is the load-bearing override. `EJSBaseJCP.runtime_info` reads
            `self.ie._downloader._js_runtimes`, which is populated by scanning the device for
            executables — and finds nothing, so `is_available()` would return False and the
            provider would never be asked to solve anything. The engine here is a linked library,
            not a program on a path, so its presence is reported directly.

            The `path` is descriptive rather than real, for the same reason: nothing execs it.

            ⚠️ Answered from a module-level cache, which is a tidy-up rather than a fix — the
            honest framing, because a first draft of this note implied otherwise. Uncached, each
            read pays two JNI round trips and re-parses the version string, and `is_available()`
            reads it every time. That is real but trivial, and it is also what upstream itself
            does: `EJSBaseJCP.runtime_info` is likewise a plain uncached property, and yt-dlp
            memoises only the genuinely expensive half (`JsRuntime.info`, which spawns a
            subprocess to read `--version`). Ours has no subprocess to spawn. The cache cannot go
            stale because every input is a process constant — the library either loaded at
            class-init or did not, and `JsRuntime.available()` is a Kotlin `by lazy` over that.
            """
            return _runtime_info()

        def _run_js_runtime(self, stdin: str, /) -> str:
            """Hand the script to QuickJS and return everything it printed.

            ⚠️ A failure has to become a `JsChallengeProviderError`, not a bare exception: the
            director catches that type per provider and carries on, where anything else would
            propagate out of extraction entirely. The message from the Kotlin side is preserved
            because it is the only description of what went wrong — a syntax error, a thrown value
            with its stack, or the interrupt that fires when a script runs past its budget.

            ⚠️ **Printing NOTHING is a failure, and has to be reported as one here.** The base class
            does `json.loads()` on whatever comes back, so an empty string raises `JSONDecodeError`
            — inside yt-dlp, from a provider that reported success, and NOT of the type the director
            catches per provider. It would surface as a broken extraction rather than as one
            provider declining, which is the whole reason this method is expected to raise a typed
            error. A script that ran cleanly but printed nothing means the solver never reached its
            `console.log`, and saying so is far more use than a decode error two frames away.
            """
            try:
                out = _runtime().evalOrThrow(stdin, _TIMEOUT_MS)
            except JsChallengeProviderError:
                raise
            except Exception as e:  # noqa: BLE001 - includes the JVM exception Chaquopy re-raises
                raise JsChallengeProviderError(f'QuickJS failed: {e}') from e
            if not out or not out.strip():
                raise JsChallengeProviderError('QuickJS ran the script but it printed nothing')
            return out

    @register_preference(LcarsJsJCP)
    def _preference(provider: JsChallengeProvider, requests: list[JsChallengeRequest]) -> int:
        """Rank it just above the built-in QuickJS provider, which scores 850.

        Not because it is better at solving — it runs the same engine — but because on this device
        it is the only one that can run at all, and being asked first saves the director probing
        four runtimes that are certain to be absent.
        """
        return 900

    registered = True
