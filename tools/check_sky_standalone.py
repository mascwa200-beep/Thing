#!/usr/bin/env python3
"""Prove the star map's APK stands alone, rather than asserting it.

The claim is about the ARTIFACT, not the source: `:core:sky` is shared on purpose — it is what
keeps one star renderer instead of two that can drift — so "standalone" cannot mean "shares no
code". It means the shipped APK is a complete application that needs nothing of LCARS: its own
package name, its own launcher, and not one class from LCARS's own tree.

Three checks, and the third is the one that would actually catch a regression:

  1. **The project-dependency graph never reaches `:app`.** Walked transitively from `:sky`, so a
     shared module growing a dependency on the application is caught, not only a direct one.
  2. **The manifest names no LCARS component or package.**
  3. **The dex carries no class from a package that exists only in `:app`.** This is the real
     test. The other two are declarations; this is what shipped.

⚠️ **THE MARKER SET IS DERIVED, NEVER HAND-LISTED**, for the reason this repository has now
corrected several times over: a hand-kept list beside a set of real facts drifts silently, and the
drift always goes the same way — the fact changes and the list does not. Carve a package out of
`:app` into a shared module and it leaves this set on its own; add an LCARS-only package and it
joins. Nobody has to remember.

⚠️ **AND PACKAGE NAMES ARE NOT A PARTITION**, which is why the derivation subtracts rather than
prefix-matching `dev.mascwa.pulse`. Measured: seven packages are declared in BOTH `:app` and a
shared module — `core.network`, `core.util`, `data.health`, `data.settings`, `data.weather`,
`feature.health` and `feature.sky` — the last of which is `:core:sky`'s own. A check that read
`dev.mascwa.pulse` as "LCARS code" would fail on the star chart itself, and a check that read only
`:app`'s directory would miss nothing but claim far more than it proved.

⚠️ **The positive control is load-bearing and is not decoration.** Every check here is an ABSENCE,
and an absence proves nothing until the search is known to find something. If the dex were
obfuscated, or extracted wrongly, or empty, every marker would be "absent" and the gate would pass
while proving nothing at all. So a class that MUST be present is required to be found first. R8 is
off for this module today (`sky/build.gradle.kts` says so and says why), but this gate does not
depend on that staying true: if minification is ever turned on, the positive control fails and the
gate says the method no longer works, rather than quietly going green.
"""

from __future__ import annotations

import os
import re
import subprocess
import sys
import tempfile
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# The class that must be found for any absence below to mean anything. An `object`, so it is a real
# class in the dex rather than a file-facade whose name gains a `Kt` suffix — a distinction that
# would otherwise make a correct gate report a false failure.
POSITIVE_CONTROL = "Ldev/mascwa/pulse/core/telemetry/SkyBudget;"

# A descriptor that cannot exist. If this is "found", the search matches anything and says nothing.
SENTINEL = "Ldev/mascwa/pulse/this_package_must_not_exist/Marker;"


def modules() -> list[str]:
    """Every module in the build, from settings.gradle.kts rather than a list kept here."""
    text = open(os.path.join(ROOT, "settings.gradle.kts")).read()
    return sorted(set(re.findall(r'include\("(:[\w:-]+)"\)', text)))


def module_dir(module: str) -> str:
    return os.path.join(ROOT, module.strip(":").replace(":", os.sep))


def project_deps(module: str) -> list[str]:
    """The `project(":x")` dependencies a module declares.

    ⚠️ Line comments are stripped first. Several build files in this repository discuss modules they
    deliberately do NOT depend on — including this one's own note about `:core:update` dragging
    `:core:feeds` — and reading a mention inside a comment as a declaration would report a
    dependency that does not exist.
    """
    path = os.path.join(module_dir(module), "build.gradle.kts")
    if not os.path.isfile(path):
        return []
    text = re.sub(r"//.*", "", open(path).read())
    return sorted(set(re.findall(r'project\("(:[\w:-]+)"\)', text)))


def reachable(start: str) -> set[str]:
    seen: set[str] = set()
    stack = [start]
    while stack:
        m = stack.pop()
        if m in seen:
            continue
        seen.add(m)
        stack.extend(project_deps(m))
    return seen


def declared_packages(root: str) -> set[str]:
    """Every package declared under a source root, read from the files themselves."""
    found: set[str] = set()
    for dirpath, _, filenames in os.walk(root):
        for name in filenames:
            if not name.endswith((".kt", ".java")):
                continue
            with open(os.path.join(dirpath, name), errors="ignore") as handle:
                for line in handle:
                    match = re.match(r"package\s+([\w.]+)", line)
                    if match:
                        found.add(match.group(1))
                        break
    return found


def app_only_packages() -> set[str]:
    """Packages declared in `:app` and in no shared module — LCARS's own code.

    The shared set is every non-application module in the build, so a module added later is included
    without this file being edited.
    """
    app = declared_packages(os.path.join(ROOT, "app", "src", "main"))
    shared: set[str] = set()
    for module in modules():
        if module in (":app", ":sky", ":nutrition", ":desktop"):
            continue
        main = os.path.join(module_dir(module), "src", "main")
        if os.path.isdir(main):
            shared |= declared_packages(main)
    return app - shared


def dex_bytes(apk: str) -> bytes:
    """The APK's dex files, concatenated.

    ⚠️ **The dex files, never the whole APK.** The R8 keep gate in `android-build.yml` learned this
    the expensive way: an asset can carry a class name as ordinary text, so a search over the
    archive can find a string that is not a class and report the opposite of the truth.
    """
    blob = bytearray()
    with zipfile.ZipFile(apk) as archive:
        names = [n for n in archive.namelist() if re.fullmatch(r"classes\d*\.dex", n)]
        if not names:
            raise SystemExit("::error::no classes.dex in the APK — nothing to check.")
        for name in sorted(names):
            blob += archive.read(name)
    return bytes(blob)


def check_graph() -> bool:
    seen = reachable(":sky")
    print("modules the star map reaches: " + " ".join(sorted(seen)))
    if ":app" in seen:
        print("::error::the star map's dependency graph reaches :app — it is not standalone.")
        return False
    print("  the graph never reaches :app")
    return True


def check_manifest(apk: str) -> bool:
    """No LCARS package named anywhere in the merged manifest.

    ⚠️ The binary manifest is not text, but a package name survives in it as a UTF-16 string, so the
    check searches for both encodings. Finding `dev.mascwa.pulse` here would mean a `<queries>`
    entry, a provider authority or a component reaching into the other application.
    """
    with zipfile.ZipFile(apk) as archive:
        raw = archive.read("AndroidManifest.xml")
    needle = "dev.mascwa.pulse"
    hits = [
        label
        for label, encoded in (("utf-8", needle.encode()), ("utf-16-le", needle.encode("utf-16-le")))
        if encoded in raw
    ]
    if hits:
        print(f"::error::the manifest names dev.mascwa.pulse ({', '.join(hits)}) — it should name only dev.mascwa.sky.")
        return False
    if b"dev.mascwa.sky" not in raw and "dev.mascwa.sky".encode("utf-16-le") not in raw:
        print("::error::the manifest does not name dev.mascwa.sky — this check is not reading it.")
        return False
    print("  the manifest names dev.mascwa.sky and no LCARS package")
    return True


def check_dex(apk: str) -> bool:
    blob = dex_bytes(apk)
    print(f"  dex: {len(blob)} bytes")

    if POSITIVE_CONTROL.encode() not in blob:
        print(f"::error::the positive control {POSITIVE_CONTROL} is NOT in the dex.")
        print("Every other result here is an absence, and an absence proves nothing when the search")
        print("cannot find something that is certainly there. Either the dex is obfuscated (R8 was")
        print("turned on for :sky), or it was extracted wrongly. This gate is not working — fix it")
        print("before trusting a pass.")
        return False
    if SENTINEL.encode() in blob:
        print("::error::the sentinel matched — this search cannot tell present from absent.")
        return False

    markers = sorted(app_only_packages())
    if not markers:
        print("::error::no LCARS-only packages were derived — the derivation is broken, not the APK.")
        return False

    # ⚠️ **A CLASS IN THIS PACKAGE, NOT ANYTHING BENEATH IT — and the first version of this check
    # got that wrong in a way that would have failed every real build.** `dev.mascwa.pulse` is
    # itself an LCARS-only package (it holds `MainActivity` and `PulseApplication`), so as a PREFIX
    # its descriptor `Ldev/mascwa/pulse/` matches every shared class as well: the star renderer, the
    # catalogue reader, the whole pure core. Found by running the gate over a synthetic APK carrying
    # nothing but sky classes, which it duly rejected.
    #
    # A dex type descriptor is `Lpath/to/Name;`, so a class declared DIRECTLY in a package is the
    # package path, one more segment, and the semicolon — no further slash. Sub-packages are not
    # missed by this: each is derived on its own merits, joining the set when it is LCARS-only and
    # staying out when it is shared. That is the whole reason the set is per-package rather than a
    # single prefix.
    found = []
    for package in markers:
        pattern = re.compile(rb"L" + re.escape(package.replace(".", "/").encode()) + rb"/[^/;]+;")
        if pattern.search(blob):
            found.append(package)

    if found:
        print(f"::error::the star map's APK carries {len(found)} package(s) that exist only in LCARS:")
        for package in found:
            print(f"    {package}")
        print("A shared module has grown a dependency on application code, or a package moved.")
        return False

    print(f"  none of the {len(markers)} LCARS-only packages is in the dex")
    print(f"  (positive control found, sentinel correctly absent)")
    return True


def main() -> int:
    apk = sys.argv[1] if len(sys.argv) > 1 else None
    print("== the star map stands alone ==")

    # ⚠️ **NO argument means "graph only" — an argument that is not a file means the caller is
    # broken, and those must not read the same.** CI resolves the path with `$(ls .../*.apk)`, which
    # yields an EMPTY STRING when the build produced nothing; treating that as "graph only" would
    # skip both APK checks and exit 0, reporting the artifact clean without having opened it. That
    # is the silent false pass `tools/android_compile_check.sh` was hardened against an hour before
    # this was written, in a different disguise.
    if apk is not None and not os.path.isfile(apk):
        shown = apk if apk else "(empty string)"
        print(f"::error::no APK at {shown} — nothing was inspected; this is NOT a pass.")
        return 1

    ok = check_graph()
    if apk:
        ok = check_manifest(apk) and ok
        ok = check_dex(apk) and ok
    else:
        print("  (no APK given — the graph check is all that ran)")
    print("standalone" if ok else "NOT standalone")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
