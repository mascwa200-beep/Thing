#!/usr/bin/env python3
"""Can this build actually CONSUME these Android dependencies?

    tools/check_aar_metadata.py androidx.health.connect:connect-client:1.1.0 [more...]
    tools/check_aar_metadata.py --catalog          # every version-catalog androidx/google coord

⚠️ WHY THIS EXISTS. Adding `androidx.health.connect:connect-client:1.1.0` cost a full CI round. Its
API was verified locally against the real AAR with javap and compiled clean — and the build still
failed, because an AAR carries a **separate declaration of the toolchain it requires** that has
nothing to do with its API:

    META-INF/com/android/build/gradle/aar-metadata.properties
        minCompileSdk=36
        minAndroidGradlePluginVersion=8.9.1

This project is on compileSdk 35 and AGP 8.7.3, so `:app:checkDebugAarMetadata` refused the artifact
before a line of Kotlin was compiled. Nothing local caught it: the compile check puts `classes.jar`
on a classpath and never looks at `META-INF`, and `javap` answers a question about types.

⚠️ It reads the constraint from the artifact rather than from a version number. "Newer needs newer"
is the intuition, and the real table is not monotonic in a way anybody should guess at — of the
health-connect releases, 1.1.0-beta01 requires AGP **8.6.0** while beta02 jumps to **8.9.1**, so the
newest usable version was one release BEHIND the first one that fails.

Exit 1 when any dependency is out of reach, naming the constraint and what this build offers.
"""
from __future__ import annotations

import io
import pathlib
import re
import sys
import urllib.request
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
CATALOG = ROOT / "gradle" / "libs.versions.toml"

# Google's maven first for androidx/com.google, Maven Central otherwise. Both are tried either way,
# because a coordinate's home is not always guessable from its group.
REPOS = (
    "https://dl.google.com/dl/android/maven2",
    "https://repo1.maven.org/maven2",
)

METADATA = "META-INF/com/android/build/gradle/aar-metadata.properties"


def catalog_versions() -> dict[str, str]:
    """The `[versions]` table, which is where compileSdk and agp live."""
    out: dict[str, str] = {}
    text = CATALOG.read_text()
    body = text.split("[libraries]")[0]
    for line in body.splitlines():
        m = re.match(r'\s*([A-Za-z0-9_.-]+)\s*=\s*"([^"]+)"', line)
        if m:
            out[m.group(1)] = m.group(2)
    return out


def catalog_coords() -> list[str]:
    """Every `group:name:version` the `[libraries]` table names, with version refs resolved."""
    versions = catalog_versions()
    text = CATALOG.read_text()
    if "[libraries]" not in text:
        return []
    body = text.split("[libraries]", 1)[1].split("[plugins]")[0]
    coords = []
    for line in body.splitlines():
        g = re.search(r'group\s*=\s*"([^"]+)"', line)
        n = re.search(r'name\s*=\s*"([^"]+)"', line)
        if not (g and n):
            continue
        v = re.search(r'version\s*=\s*"([^"]+)"', line)
        ref = re.search(r'version\.ref\s*=\s*"([^"]+)"', line)
        ver = v.group(1) if v else versions.get(ref.group(1), "") if ref else ""
        if ver:
            coords.append(f"{g.group(1)}:{n.group(1)}:{ver}")
    return coords


def fetch_metadata(coord: str) -> dict[str, str] | None:
    """The AAR's toolchain declaration, or None when there is no AAR (a plain jar imposes none)."""
    group, name, version = coord.split(":")
    path = f"{group.replace('.', '/')}/{name}/{version}/{name}-{version}.aar"
    for repo in REPOS:
        try:
            with urllib.request.urlopen(f"{repo}/{path}", timeout=30) as r:
                blob = r.read()
        except Exception:
            continue
        try:
            with zipfile.ZipFile(io.BytesIO(blob)) as z:
                if METADATA not in z.namelist():
                    return {}
                props: dict[str, str] = {}
                for line in z.read(METADATA).decode("utf-8", "replace").splitlines():
                    if "=" in line and not line.startswith("#"):
                        k, _, v = line.partition("=")
                        props[k.strip()] = v.strip()
                return props
        except zipfile.BadZipFile:
            continue
    return None


def version_tuple(v: str) -> tuple[int, ...]:
    """`8.7.3` -> (8, 7, 3). Pre-release suffixes are dropped; they never relax a floor."""
    return tuple(int(p) for p in re.findall(r"\d+", v)[:3]) or (0,)


def main() -> int:
    versions = catalog_versions()
    have_sdk = int(versions.get("compileSdk", "0"))
    have_agp = versions.get("agp", "0")
    if not have_sdk or have_agp == "0":
        print("could not read compileSdk / agp from the version catalog — refusing to judge")
        return 2

    args = sys.argv[1:]
    coords = catalog_coords() if args == ["--catalog"] else args
    if not coords:
        print(__doc__)
        return 2

    print(f"this build offers: compileSdk {have_sdk}, AGP {have_agp}\n")
    bad = 0
    checked = 0
    for coord in coords:
        props = fetch_metadata(coord)
        if props is None or not props:
            continue  # not an AAR, or no metadata: it constrains nothing
        checked += 1
        need_sdk = int(props.get("minCompileSdk", "0") or 0)
        need_agp = props.get("minAndroidGradlePluginVersion", "0")
        problems = []
        if need_sdk > have_sdk:
            problems.append(f"needs compileSdk {need_sdk}")
        if version_tuple(need_agp) > version_tuple(have_agp):
            problems.append(f"needs AGP {need_agp}")
        if problems:
            bad += 1
            print(f"OUT OF REACH  {coord}\n                {', '.join(problems)}")

    print(f"\n{checked} AAR(s) carried a toolchain declaration; {bad} out of reach")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
