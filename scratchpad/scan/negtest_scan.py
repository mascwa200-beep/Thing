#!/usr/bin/env python3
"""Negative-test the pure halves of :core:scan, with no Gradle and no Android SDK.

Compiles :core:telemetry plus the two Android-free files of :core:scan and runs their JUnit
classes, exactly as `tools/run_core_test.sh` does for the telemetry core. Then applies one
perturbation at a time and reports which tests noticed.

Guarded against every way a green run has proved nothing here before:
  - the baseline is asserted green before anything is perturbed
  - every edit asserts its own substitution count, so a perturbation that never applied is an error
  - a compile failure is reported as such, NOT as a passing guard
  - the restore is in a `finally`, because a tool timeout has left a perturbation in a tree once
"""
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
G = Path("/opt/gradle-8.14.3/lib")
OUT = Path("/tmp/scanneg")

SCAN = ROOT / "core/scan/src/main/java/dev/mascwa/pulse/scan"
ROTATE = SCAN / "LumaRotate.kt"
STATE = SCAN / "ScanState.kt"
SOURCES = [
    ROTATE, STATE,
    ROOT / "core/scan/src/test/java/dev/mascwa/pulse/scan/LumaRotateTest.kt",
    ROOT / "core/scan/src/test/java/dev/mascwa/pulse/scan/ScanHintTest.kt",
]
CLASSES = ["dev.mascwa.pulse.scan.LumaRotateTest", "dev.mascwa.pulse.scan.ScanHintTest"]

# (name, file, needle, replacement, tests expected to fail)
PERTURBATIONS = [
    ("the 90 degree turn goes anti-clockwise instead", ROTATE,
     "                    90 -> (height - 1 - y) + x * outW\n",
     "                    90 -> y + (width - 1 - x) * outW\n",
     # Two, not four. My first prediction here named the round-trip test and the three-turn test as
     # well, and both correctly survive: the round trip is TRUE of an anti-clockwise rule too, and
     # the three-turn test never asserts 90. See the note on `fourQuarterTurns...` in the test file.
     ["aQuarterTurnGoesClockwiseAndSwapsTheDimensions",
      "rowPaddingIsDroppedRatherThanTreatedAsImage"]),

    ("the row is indexed by width instead of the padded stride", ROTATE,
     "            val row = y * stride\n",
     "            val row = y * width\n",
     ["rowPaddingIsDroppedRatherThanTreatedAsImage",
      "anInterleavedPlaneIsReadAtItsOwnPixelStride"]),

    ("pixelStride ignored: an interleaved plane is read as noise", ROTATE,
     "                val v = src[row + x * pixelStride]\n",
     "                val v = src[row + x]\n",
     ["anInterleavedPlaneIsReadAtItsOwnPixelStride"]),

    ("the short-buffer guard is dropped", ROTATE,
     "        if (needed > src.size) return null\n",
     "",
     ["aBufferTooShortForItsOwnMetadataIsRefused"]),

    ("a confirmed code no longer outranks a broken camera", STATE,
     "            state.progress.confirmed -> GOT_IT\n            state.failure != null -> BROKEN\n",
     "            state.failure != null -> BROKEN\n            state.progress.confirmed -> GOT_IT\n",
     ["aConfirmedCodeOutranksEverythingElseAtOnce", "aBrokenCameraOutranksEveryMessageAboutTheFrame"]),

    ("struggling is checked before darkness, so the actionable message is never shown", STATE,
     "            state.tooDark -> TOO_DARK\n            state.quietMs >= ScanTuning.STRUGGLING_AFTER_MS -> STRUGGLING\n",
     "            state.quietMs >= ScanTuning.STRUGGLING_AFTER_MS -> STRUGGLING\n            state.tooDark -> TOO_DARK\n",
     ["darknessOutranksMerelyStruggling"]),

    ("a code coming through no longer outranks the light", STATE,
     "            state.progress.candidate.isNotBlank() -> READING\n", "",
     ["aCodeComingThroughOutranksTheLightAndTheTimer"]),
]


def jars():
    def find(pat, root=Path("/root/.gradle")):
        hits = sorted(root.rglob(pat))
        return hits[0] if hits else None
    j = {
        "jsoup": find("jsoup-*.jar"),
        "junit": find("junit-4.13.2.jar"),
        "ham": find("hamcrest-core-1.3.jar"),
        "plug": find("kotlin-serialization-compiler-plugin-embeddable-2.0.21.jar"),
        "cor": next(iter(sorted(G.glob("kotlinx-coroutines-core-jvm*.jar"))), None),
        "ser": G / "kotlinx-serialization-core-jvm-1.6.2.jar",
        "serj": G / "kotlinx-serialization-json-jvm-1.6.2.jar",
        "stdlib": G / "kotlin-stdlib-2.0.21.jar",
        "kc": G / "kotlin-compiler-embeddable-2.0.21.jar",
        "trove": G / "trove4j-1.0.20200330.jar",
        "annot": G / "annotations-24.0.1.jar",
    }
    missing = [k for k, v in j.items() if v is None or not Path(v).exists()]
    if missing:
        sys.exit("MISSING JAR(S): " + ", ".join(missing))
    return {k: str(v) for k, v in j.items()}


J = jars()
TARGET_CP = ":".join([J["stdlib"], J["jsoup"], J["ser"], J["serj"], J["junit"], J["ham"]])
CORE = [str(p) for p in (ROOT / "core/telemetry/src/main").rglob("*.kt")
        if not re.search(r"^import android[.x]?", p.read_text(), re.M)]


def run_suite():
    """-> (error or None, {test name: passed})"""
    subprocess.run(["rm", "-rf", str(OUT)], check=False)
    OUT.mkdir(parents=True, exist_ok=True)
    compile_cp = ":".join([J["kc"], J["stdlib"], J["trove"], J["annot"], J["cor"]])
    proc = subprocess.run(
        ["java", "-cp", compile_cp, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
         *CORE, *[str(p) for p in SOURCES],
         "-cp", TARGET_CP, f"-Xplugin={J['plug']}", "-d", str(OUT / "out"), "-nowarn"],
        capture_output=True, text=True, timeout=900, cwd=ROOT,
    )
    marker = OUT / "out/dev/mascwa/pulse/scan/LumaRotateTest.class"
    if not marker.exists():
        errs = [l for l in (proc.stdout + proc.stderr).splitlines() if "error:" in l]
        return ("did not compile: " + (errs[0] if errs else "no error line found")), {}

    outcome = {}
    for cls in CLASSES:
        run = subprocess.run(
            ["java", "-cp", ":".join([str(OUT / "out"), TARGET_CP]),
             "org.junit.runner.JUnitCore", cls],
            capture_output=True, text=True, timeout=600, cwd=ROOT,
        )
        text = run.stdout + run.stderr
        total = re.search(r"OK \((\d+) tests?\)", text)
        failed = set(re.findall(r"^\d+\) (\w+)\(", text, re.M))
        ran = re.search(r"Tests run: (\d+),", text)
        names = failed
        count = int(total.group(1)) if total else (int(ran.group(1)) if ran else 0)
        if count == 0 and not names:
            return f"{cls}: produced no result", {}
        outcome[cls] = (count, names)
    flat = {}
    for cls, (count, names) in outcome.items():
        for n in names:
            flat[n] = False
        flat[f"__count__{cls}"] = count
    return None, flat


def main():
    originals = {p: p.read_text() for p in {ROTATE, STATE}}
    try:
        print("baseline...", flush=True)
        err, base = run_suite()
        if err:
            sys.exit("BASELINE " + err)
        failed = [k for k, v in base.items() if v is False]
        if failed:
            sys.exit("BASELINE NOT GREEN: " + ", ".join(failed))
        counts = {k.replace("__count__", ""): v for k, v in base.items() if k.startswith("__count__")}
        print(f"baseline: {sum(counts.values())} tests, all passing {counts}\n")

        asleep = 0
        for name, path, needle, repl, expected in PERTURBATIONS:
            src = originals[path]
            n = src.count(needle)
            assert n == 1, f"perturbation {name!r} matched {n} times, not once — it would not apply"
            path.write_text(src.replace(needle, repl, 1))
            err, got = run_suite()
            path.write_text(src)
            if err:
                print(f"  !! {name}\n     {err}\n     — NOT evidence a guard is awake")
                asleep += 1
                continue
            fails = sorted(k for k, v in got.items() if v is False)
            ok = set(fails) == set(expected)
            print(f"  {'OK    ' if ok else 'ASLEEP'} {name}")
            print(f"         failed: {fails or '(nothing — the rule is untested)'}")
            if not ok:
                print(f"         expected: {sorted(expected)}")
                asleep += 1
        print()
        print("all guards awake" if asleep == 0 else f"{asleep} guard(s) not awake")
        return 1 if asleep else 0
    finally:
        for p, text in originals.items():
            p.write_text(text)


if __name__ == "__main__":
    sys.exit(main())
