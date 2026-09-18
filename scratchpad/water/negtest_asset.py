#!/usr/bin/env python3
"""Negative-test the asset guards by perturbing the DATA, which is what they protect.

⚠️ These guards differ in kind from the core's: the rule under test is not a line of Kotlin but a
property of a three-and-a-half-thousand-row file nobody proofreads. So the perturbation is a row
edit, and the harness compiles ONCE and re-runs only the JVM — the asset is read at runtime, so a
recompile per case would be forty seconds of nothing.

Same discipline otherwise: baseline asserted green first, every perturbation asserted to have
changed the file, failing test names read out of the JUnit output, restore in a `finally` and
byte-compared.
"""
import filecmp
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path("/home/user/Thing")
ASSET = ROOT / "app/src/main/assets/water/stations.tsv"

KC = "/opt/gradle-8.14.3/lib/kotlin-compiler-embeddable-2.0.21.jar"
STD = "/opt/gradle-8.14.3/lib/kotlin-stdlib-2.0.21.jar"
TRV = "/opt/gradle-8.14.3/lib/trove4j-1.0.20200330.jar"
ANN = "/opt/gradle-8.14.3/lib/annotations-24.0.1.jar"
GC = Path.home() / ".gradle/caches/modules-2/files-2.1"


def jar(pattern, root=GC):
    hits = sorted(root.rglob(pattern))
    assert hits, f"missing a required jar: {pattern}"
    return str(hits[0])


COR = jar("kotlinx-coroutines-core-jvm-*.jar")
JU = jar("junit-4.13.2.jar")
HC = jar("hamcrest-core-1.3.jar")
JSOUP = jar("jsoup-*.jar")
SER = jar("kotlinx-serialization-core-jvm-*.jar")
SERJ = jar("kotlinx-serialization-json-jvm-*.jar")
SPLUG = jar("kotlin-serialization-compiler-plugin-embeddable-*.jar", Path.home() / ".gradle/caches/modules-2")
TARGET_CP = ":".join([STD, JSOUP, JU, HC, SER, SERJ])

OUT = tempfile.mkdtemp()
core = subprocess.run(
    ["grep", "-rLE", "^import android[.x]?", "core/telemetry/src/main", "--include=*.kt"],
    cwd=ROOT, capture_output=True, text=True,
).stdout.split()
assert core, "found no core sources"

compile_cmd = [
    "java", "-cp", ":".join([KC, STD, TRV, ANN, COR]),
    "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
    "-nowarn", f"-Xplugin={SPLUG}", "-d", OUT, "-cp", TARGET_CP,
    *core, "app/src/test/java/dev/mascwa/pulse/data/water/WaterStationsAssetTest.kt",
]
c = subprocess.run(compile_cmd, cwd=ROOT, capture_output=True, text=True)
assert list(Path(OUT).rglob("*.class")), "NOTHING COMPILED:\n" + c.stdout + c.stderr
print("compiled once; every case below re-runs only the JVM")


def run():
    p = subprocess.run(
        ["java", "-cp", f"{OUT}:{TARGET_CP}", "org.junit.runner.JUnitCore",
         "dev.mascwa.pulse.data.water.WaterStationsAssetTest"],
        cwd=ROOT / "app", capture_output=True, text=True,
    )
    return p.stdout + p.stderr


def failing(out):
    return set(re.findall(r"^\d+\) ([^(]+)\(", out, re.M))


original = ASSET.read_text()
rows = original.rstrip("\n").split("\n")
holland = next(r for r in rows if r.startswith("9087031\t"))
a_tide = next(r for r in rows if r.split("\t")[3] == "T")


def swap_lat_lon(text):
    # One row only. Enough to catch a builder that swapped the columns, which would put every
    # American station in Kazakhstan and still parse perfectly.
    f = a_tide.split("\t")
    bad = "\t".join([f[0], f[2], f[1], f[3], f[4]])
    return text.replace(a_tide, bad)


def drop_the_lakes(text):
    kept = [r for r in text.rstrip("\n").split("\n") if r.split("\t")[3] != "W"]
    return "\n".join(kept) + "\n"


def add_a_header(text):
    return "id\tlat\tlon\tkind\tname\n" + text


def duplicate_an_id(text):
    f = a_tide.split("\t")
    dupe = "\t".join(["9087031", f[1], f[2], f[3], f[4]])
    return text + dupe + "\n"


def blank_a_name(text):
    f = holland.split("\t")
    return text.replace(holland, "\t".join(f[:4] + [""]))


def add_a_sixth_column(text):
    return text.replace(holland, holland + "\tIGLD")


def readmit_a_non_lake_level(text):
    # A rebuild that took every `waterlevels` station NOAA lists, which is exactly what the first
    # cut of the builder did. Arecibo is on the Atlantic and has no IGLD elevation at all.
    return text + "9757809\t18.4806\t-66.7025\tW\tArecibo, PR\n"


# (label, transform, tests that MUST fail)
# ⚠️ `load()` asserts inside every test, so a perturbation that makes a row unparseable fails the
# WHOLE suite rather than one guard — which is the intended behaviour and is stated here so a long
# list of failures is not mistaken for the harness misfiring.
ALL = [
    "the list is there and every row of it reads",
    "both products are present, and the lakes are not the rounding error",
    "every station is somewhere on Earth, and somewhere NOAA operates",
    "every level station is on the datum the app asks for",
    "station ids are unique",
    "where the owner lives, the answer is the lake and not the tide",
    "on a coast the answer is the tide",
    "far inland there is no water reading and the block is absent",
    "the file is a table and not prose",
]

# ⚠️ A header row is EXCEPT the table-shape guard, and my first list said otherwise. `id lat lon
# kind name` has exactly five tab-separated fields and a non-blank last one, so that guard passes on
# it correctly — the header is caught by `load()`, because "lat" is not a latitude. Expecting it to
# fail there would have reported a perfectly awake guard as asleep.
ALL_BUT_SHAPE = [t for t in ALL if t != "the file is a table and not prose"]

CASES = [
    ("one row has latitude and longitude swapped", swap_lat_lon,
     ["every station is somewhere on Earth, and somewhere NOAA operates"]),
    ("the rebuild kept only the tide stations", drop_the_lakes,
     ["both products are present, and the lakes are not the rounding error",
      "where the owner lives, the answer is the lake and not the tide"]),
    ("the builder started emitting a header row", add_a_header, ALL_BUT_SHAPE),
    ("two rows share an id", duplicate_an_id, ["station ids are unique"]),
    # ⚠️ This case USED to fail only two guards, because `parse` carried an unnamed station rather
    # than refusing it. Running the harness is what found that; `parse` refuses now, so an unnamed
    # row costs its own station, `load()` sees the count fall short, and the whole suite fails.
    ("a station lost its name", blank_a_name, ALL),
    ("a sixth column appeared", add_a_sixth_column, ["the file is a table and not prose"]),
    ("the rebuild let a non-Great-Lakes level station back in", readmit_a_non_lake_level,
     ["every level station is on the datum the app asks for"]),
]

base = run()
if "OK (" not in base:
    sys.exit("BASELINE IS NOT GREEN — nothing below would mean anything:\n" + base[-2000:])
print("baseline:", re.search(r"OK \(\d+ tests\)", base).group(0))

backup = Path(tempfile.mkdtemp()) / "stations.tsv"
shutil.copy2(ASSET, backup)
asleep = []
try:
    for label, transform, expect in CASES:
        perturbed = transform(original)
        assert perturbed != original, f"{label}: the perturbation changed nothing"
        ASSET.write_text(perturbed)
        out = run()
        if "OK (" in out:
            asleep.append(label)
            print(f"  ASLEEP  {label} — the suite still passes with the file broken")
            continue
        fails = failing(out)
        missing = [t for t in expect if t not in fails]
        extra = sorted(fails - set(expect))
        status = "awake  " if not missing else "PARTIAL"
        print(f"  {status} {label}: {len(fails)} failed")
        if missing:
            print(f"           expected but did not fail: {missing}")
            asleep.append(label)
        if extra:
            print(f"           also failed (fine, but worth seeing): {extra}")
finally:
    ASSET.write_text(original)
    assert filecmp.cmp(ASSET, backup, shallow=False), "RESTORE FAILED — the asset is not as it was"
    after = run()
    assert "OK (" in after, "the restored asset does not pass:\n" + after[-2000:]
    print("restored, and green again:", re.search(r"OK \(\d+ tests\)", after).group(0))

print()
print("ALL GUARDS AWAKE" if not asleep else f"ASLEEP: {asleep}")
sys.exit(1 if asleep else 0)
