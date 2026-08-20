#!/usr/bin/env python3
"""Local gate for `app/src/main/python/lcars_jsi.py`.

⚠️ **Nothing else checks this file**, and it is the seam that makes YouTube extraction work at all.
CI runs `:app:testDebugUnitTest`, which is Kotlin; the bundled Python is compiled by nobody. What is
expensive to get wrong here:

* the class name and `PROVIDER_KEY` — `register_provider_generic` **asserts** on a bad suffix or a
  duplicate key, so a mistake is an exception at import, on the device, inside extraction;
* the `runtime_info` override — without it `is_available()` returns False and the provider is never
  asked to solve anything, silently. Extraction keeps working and quietly loses formats, which is
  precisely the failure this whole change exists to remove;
* the degradation path — every import is guarded so that a yt-dlp internal moving under us costs
  some formats rather than costing extraction altogether.

The interesting runs need a JavaScript engine, and this machine has no Chaquopy and no
`liblcarsnative.so`. So `--engine` stands one in: point it at a binary that reads a script file and
prints what the script printed, and the shipped provider is driven for real through a fake `java`
module. That binary is trivially built from quickjs-ng plus `app/src/main/cpp/js_jni.cpp`; see the
QuickJS block in `app/src/main/cpp/CMakeLists.txt`.

    python3 tools/check_jsi.py                          # offline, no engine: degradation only
    python3 tools/check_jsi.py --engine /path/runscript  # drives the real provider through QuickJS
"""

from __future__ import annotations

import importlib.util
import json
import pathlib
import subprocess
import sys
import tempfile
import types

ROOT = pathlib.Path(__file__).resolve().parent.parent
TARGET = ROOT / "app" / "src" / "main" / "python" / "lcars_jsi.py"

# The version the workflow pins. Reported by the stand-in so the version parsing is exercised on a
# real string rather than a tidy one.
ENGINE_VERSION = "0.16.0"


def load(path: pathlib.Path, name: str):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def install_fake_java(engine: str | None) -> None:
    """Stand in for Chaquopy's `java` module, backed by a real QuickJS binary when given one."""

    class _Runtime:
        @staticmethod
        def available() -> bool:
            return engine is not None

        @staticmethod
        def version() -> str | None:
            return ENGINE_VERSION if engine else None

        @staticmethod
        def evalOrThrow(script: str, timeout_ms: int) -> str:  # noqa: N802 - mirrors the JVM name
            if engine is None:
                raise RuntimeError("no JavaScript engine in this build")
            with tempfile.NamedTemporaryFile("w", suffix=".js", delete=False, encoding="utf-8") as f:
                f.write(script)
                path = f.name
            try:
                proc = subprocess.run(
                    [engine, path, str(timeout_ms)], capture_output=True, text=True, check=False)
            finally:
                pathlib.Path(path).unlink(missing_ok=True)
            if proc.returncode != 0:
                raise RuntimeError(proc.stderr.strip() or "JavaScript failed")
            return proc.stdout

    module = types.ModuleType("java")
    module.jclass = lambda name: _Runtime  # noqa: ARG005 - one class, one answer
    sys.modules["java"] = module


def check_registration(jsi) -> list[str]:
    bad: list[str] = []
    if jsi.setup_error:
        return [f"provider did not set up: {jsi.setup_error}"]
    if not jsi.registered:
        return ["provider did not register"]

    from yt_dlp.extractor.youtube.jsc._registry import _jsc_providers

    registry = _jsc_providers.value
    cls = jsi.LcarsJsJCP
    # ⚠️ The suffix rule is an assert inside yt-dlp, so a bad name is an exception at import rather
    # than a quiet no-op. Stated here so the reason survives a rename.
    if not cls.__name__.endswith("JCP"):
        bad.append(f"class name {cls.__name__} must end with JCP")
    if cls.PROVIDER_KEY != "LcarsJs":
        bad.append(f"unexpected PROVIDER_KEY {cls.PROVIDER_KEY!r}")
    if registry.get(cls.PROVIDER_KEY) is not cls:
        bad.append("provider is not in yt-dlp's registry")
    # The collision that would matter: the built-in QuickJS provider is registered too.
    if "QuickJS" not in registry:
        bad.append("built-in QuickJS provider missing - registry probe is not meaningful")
    return bad


def check_runtime_info(jsi, *, expect_engine: bool) -> list[str]:
    bad: list[str] = []
    provider = jsi.LcarsJsJCP.__new__(jsi.LcarsJsJCP)  # __init__ needs an extractor; nothing here does
    info = provider.runtime_info
    if not expect_engine:
        if info is not None:
            bad.append("runtime_info must be None with no engine, or the provider claims to work")
        return bad
    if info is None:
        bad.append("runtime_info is None despite an engine being present")
        return bad
    if info.name != "lcars-quickjs":
        bad.append(f"unexpected runtime name {info.name!r}")
    if info.version_tuple != (0, 16, 0):
        bad.append(f"version_tuple {info.version_tuple} does not parse {ENGINE_VERSION}")
    if not info.supported:
        bad.append("runtime reports itself unsupported, so is_available() would be False")
    return bad


def check_solves(jsi) -> list[str]:
    """Drive the real `_run_js_runtime` with a script shaped exactly like yt-dlp's own."""
    provider = jsi.LcarsJsJCP.__new__(jsi.LcarsJsJCP)
    # The shape from `_construct_stdin`: the product is what console.log printed.
    script = "function jsc(d){return {type:'result', n:d.n*2};}\nconsole.log(JSON.stringify(jsc({n:21})));\n"
    bad: list[str] = []
    try:
        out = provider._run_js_runtime(script)
    except Exception as e:  # noqa: BLE001
        return [f"_run_js_runtime raised on a good script: {e}"]
    try:
        parsed = json.loads(out)
    except Exception as e:  # noqa: BLE001
        return [f"output is not the JSON the caller will parse ({e}): {out[:80]!r}"]
    if parsed != {"type": "result", "n": 42}:
        bad.append(f"wrong result {parsed!r}")

    # ⚠️ And a failure must arrive as JsChallengeProviderError, not as a bare exception: the
    # director catches that type per provider and carries on, where anything else escapes extraction.
    from yt_dlp.extractor.youtube.jsc.provider import JsChallengeProviderError
    try:
        provider._run_js_runtime("throw new Error('deliberate')")
    except JsChallengeProviderError as e:
        if "deliberate" not in str(e):
            bad.append(f"error text lost the reason: {e}")
    except Exception as e:  # noqa: BLE001
        bad.append(f"a thrown script raised {type(e).__name__}, not JsChallengeProviderError")
    else:
        bad.append("a thrown script was not reported as a failure at all")
    return bad


def check_extractor_wiring(jsi, engine: str | None) -> list[str]:
    """The provider is useless unless something imports it, and that something is `lcars_extract`.

    ⚠️ Registration happens at import, so nothing *calls* the provider — which means a broken import
    would look exactly like a working one right up until YouTube quietly lost half its formats. This
    drives `_enable_js_runtime` for real and reads the line it puts in the notes, because that line
    is the only place the app ever says whether it has a JavaScript engine.

    ⚠️ The already-loaded module is published under its real name rather than letting the import
    find the file again, and that is not a shortcut — it is the faithful arrangement. On the device
    Chaquopy imports `lcars_jsi` exactly once, and a second import under a second name would run
    `@register_provider` twice, which yt-dlp **asserts** against. Loading it again here would fail on
    a duplicate key that can never happen in the app.
    """
    sys.modules["lcars_jsi"] = jsi
    lx = load(ROOT / "app" / "src" / "main" / "python" / "lcars_extract.py", "lcars_extract_wiring")
    status = lx._enable_js_runtime()
    bad: list[str] = []
    if engine:
        if "quickjs" not in status or ENGINE_VERSION not in status:
            bad.append(f"status line does not name the live engine: {status!r}")
    elif "NONE" not in status:
        bad.append(f"status line must say NONE with no engine: {status!r}")
    # And it must reach the notes, deduped, without a warning prefix that would read as yt-dlp's.
    notes = lx._Notes()
    notes.note(status)
    notes.note(status)
    if notes.notes != [status[: lx._Notes.MAX_CHARS]]:
        bad.append(f"note() did not record exactly one copy: {notes.notes!r}")
    print(f"  extractor reports: {status}")
    return bad


def main() -> int:
    if not TARGET.exists():
        print(f"cannot find {TARGET}", file=sys.stderr)
        return 2

    engine = None
    if "--engine" in sys.argv:
        engine = sys.argv[sys.argv.index("--engine") + 1]
        if not pathlib.Path(engine).exists():
            print(f"no such engine binary: {engine}", file=sys.stderr)
            return 2

    failures: list[str] = []

    # 1. No Chaquopy at all — the case every non-Android import takes, and the one that must never
    #    raise. `java` is deliberately absent from sys.modules here.
    sys.modules.pop("java", None)
    offline = load(TARGET, "lcars_jsi_offline")
    if offline.setup_error is None:
        failures.append("expected a setup_error with no Chaquopy present")
    if offline.available():
        failures.append("available() must be False with no Chaquopy")
    if offline.engine_version() is not None:
        failures.append("engine_version() must be None with no Chaquopy")
    print(f"  no-Chaquopy path: degrades with {offline.setup_error!r}")

    # 2. Chaquopy present. Registration happens at import, so this can only be done once per process.
    install_fake_java(engine)
    jsi = load(TARGET, "lcars_jsi_under_test")
    failures += check_registration(jsi)
    failures += check_runtime_info(jsi, expect_engine=engine is not None)
    if engine:
        print(f"  engine: {engine} reporting {ENGINE_VERSION}")
        failures += check_solves(jsi)
    else:
        print("  no --engine given: the solve and runtime_info checks are NOT proven")
        if jsi.available():
            failures.append("available() must be False when the engine reports absent")

    # 3. The seam: lcars_extract has to import the provider, and say so.
    failures += check_extractor_wiring(jsi, engine)

    if failures:
        print(f"\n{len(failures)} FAILED:", file=sys.stderr)
        for f in failures:
            print(f"  - {f}", file=sys.stderr)
        return 1
    print("lcars_jsi: all checks pass")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
