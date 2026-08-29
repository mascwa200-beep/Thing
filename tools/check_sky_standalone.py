#!/usr/bin/env python3
"""Prove the star map's APK stands alone, rather than asserting it.

The claim is about the ARTIFACT, not the source: `:core:sky` is shared on purpose — it is what
keeps one star renderer instead of two that can drift — so "standalone" cannot mean "shares no
code". It means the shipped APK is a complete application that needs nothing of LCARS: its own
package name, its own launcher, and not one class from LCARS's own tree.

Three checks, and the third is the one that would actually catch a regression:

  1. **The project-dependency graph never reaches `:app`.** Walked transitively from `:sky`, so a
     shared module growing a dependency on the application is caught, not only a direct one.
  2. **The merged manifest declares no component from an LCARS-only package.**
  3. **The dex carries no class from a package that exists only in `:app`.** This is the real
     test. The other two are declarations; this is what shipped.

⚠️ **Checks 2 and 3 ask ONE question and now share ONE function**, `declared_in` — because the first
version of this file asked it two different ways and the weaker way was wrong. See the partition note
below: check 3 was written with the derived rule and check 2 with a bare `dev.mascwa.pulse` substring
search, and on its first real run that substring condemned `:core:update`'s own install-result
receiver — shared code the star map legitimately contains, which check 3 passed in the same run. Two
statements of one rule, one of them drifted. The syntaxes still differ (a dex type descriptor is
`Lpath/to/Name;`, a manifest component is `path.to.Name`), so the separator and the terminator are
parameters; the rule is not.

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
`:app`'s directory would miss nothing but claim far more than it proved. `:core:update`'s namespace
`dev.mascwa.pulse.data.update` is the same case and is what the substring version actually tripped
over: the module was carved out of `:app` and kept the package it came from, which is a naming
choice and not a dependency.

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


APPLICATIONS = (":app", ":sky", ":nutrition", ":desktop")


def shared_packages() -> set[str]:
    """Every package declared by a non-application module, so a module added later is included
    without this file being edited."""
    shared: set[str] = set()
    for module in modules():
        if module in APPLICATIONS:
            continue
        main = os.path.join(module_dir(module), "src", "main")
        if os.path.isdir(main):
            shared |= declared_packages(main)
    return shared


def app_only_packages() -> set[str]:
    """Packages declared in `:app` and in no shared module — LCARS's own code."""
    return declared_packages(os.path.join(ROOT, "app", "src", "main")) - shared_packages()


def all_packages() -> set[str]:
    """Every package this build declares anywhere.

    Used only to tell a sub-package from a class: a name that is itself a package we declare is a
    package reference however much it looks like a class from the outside.
    """
    every = shared_packages()
    for module in APPLICATIONS:
        main = os.path.join(module_dir(module), "src", "main")
        if os.path.isdir(main):
            every |= declared_packages(main)
    return every


def declared_in(text: str, package: str, sep: str, head: str = "", tail: str = "") -> set[str]:
    """The classes declared DIRECTLY in `package` that `text` names, as bare leaf names.

    ⚠️ **DIRECTLY — one more segment and no further separator — and getting that wrong is what made
    the first version of check 2 reject every real build.** `dev.mascwa.pulse` is itself an
    LCARS-only package (it holds `MainActivity` and `PulseApplication`), so as a plain prefix it also
    matches `dev.mascwa.pulse.data.update.ApkInstaller$ResultReceiver`, `dev.mascwa.pulse.sky.…` and
    every other shared class. Sub-packages are not missed by this: each is derived on its own merits
    and joins the marker set when it is LCARS-only, which is the whole reason the set is per-package
    rather than one prefix.

    `$` is deliberately inside the name run rather than a boundary — `Outer$Inner` is one class, and
    treating the `$` as a terminator would let a nested class in an LCARS-only package go unreported.

    `sep`/`head`/`tail` carry the two syntaxes: a dex type descriptor is `Lpath/to/Name;` and a
    manifest component is `path.to.Name` with nothing after it, so the manifest form has to say
    "and nothing that would continue this name" as a lookahead instead of matching a terminator.
    `tail` is a regex fragment, NOT a literal — it is not escaped.

    ⚠️ **Leaf names rather than a yes/no, because a SUB-PACKAGE looks exactly like a class here and
    the caller is the only thing that can tell them apart.** Measured before it could reach CI: the
    bare string `dev.mascwa.pulse.sky` — `:core:sky`'s own namespace, which a library module with no
    manifest of its own still carries — matches under the marker `dev.mascwa.pulse` with `sky` as
    the supposed class. Returning the leaf lets [not_a_package] drop it. The convention that saves
    this (packages lowercase, classes capitalised) is only a convention, so the check is against the
    real declared set rather than against the capital letter.
    """
    pattern = (
        head
        + re.escape(package.replace(".", sep))
        + re.escape(sep)
        + r"([A-Za-z_][A-Za-z0-9_$]*)"
        + tail
    )
    return {match.group(1) for match in re.finditer(pattern, text)}


def not_a_package(package: str, leaves: set[str], known: set[str]) -> set[str]:
    """Of `leaves` found under `package`, those that are not themselves a package we declare."""
    return {leaf for leaf in leaves if f"{package}.{leaf}" not in known}


# The two syntaxes, as keyword bundles so a call site reads as "which artifact" rather than as four
# punctuation arguments.
DEX_SYNTAX = {"sep": "/", "head": "L", "tail": ";"}
MANIFEST_SYNTAX = {"sep": ".", "head": "", "tail": r"(?![A-Za-z0-9_$.])"}


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


# The component that MUST be found for the manifest check's absences to mean anything. It is
# `:core:update`'s install-result receiver, written fully qualified in that library's own manifest —
# and the merger only ever expands a relative `android:name` to an absolute one, never the reverse,
# so it survives verbatim. ⚠️ It is also the exact declaration the substring version of this check
# wrongly condemned, which makes it the right control: the rule now has to accept it and find it.
MANIFEST_CONTROL = "dev.mascwa.pulse.data.update"


def check_manifest(apk: str, markers: list[str], known: set[str]) -> bool:
    """No component from an LCARS-only package named in the merged manifest.

    ⚠️ The binary manifest is not text, but its strings survive in it, so the whole blob is decoded
    both ways and searched as text rather than parsed. UTF-16 is the usual encoding for an AXML
    string pool and UTF-8 appears in some; a region that is neither decodes to garbage that cannot
    match an ASCII package name. `latin-1` is not among them on purpose — it would map the UTF-16
    NUL padding into the text and break every name in half.

    Finding an LCARS-only component here would mean a `<queries>` entry, a provider authority or a
    receiver reaching into the other application.
    """
    with zipfile.ZipFile(apk) as archive:
        raw = archive.read("AndroidManifest.xml")

    texts = [raw.decode("utf-8", errors="ignore"), raw.decode("utf-16-le", errors="ignore")]

    # The decode works — a substring, deliberately not routed through the regex, so this control and
    # the one below fail for different reasons and each says which half is broken.
    if not any("dev.mascwa.sky" in text for text in texts):
        print("::error::the manifest does not name dev.mascwa.sky — this check is not reading it.")
        return False

    # And the rule finds what it is supposed to find. Without this, a pattern that matched NOTHING
    # would report every absence as a pass, which is the failure mode a search-for-absence has.
    control = set()
    for text in texts:
        control |= not_a_package(MANIFEST_CONTROL, declared_in(text, MANIFEST_CONTROL, **MANIFEST_SYNTAX), known)
    if not control:
        print(f"::error::the manifest positive control ({MANIFEST_CONTROL}.*) was NOT found.")
        print("The manifest is being read but the matching rule finds nothing in it, so every")
        print("'absent' below would be meaningless. Either :core:update stopped declaring its")
        print("install-result receiver, or `declared_in` is broken. Fix this before trusting a pass.")
        return False

    found: dict[str, set[str]] = {}
    for package in markers:
        leaves: set[str] = set()
        for text in texts:
            leaves |= not_a_package(package, declared_in(text, package, **MANIFEST_SYNTAX), known)
        if leaves:
            found[package] = leaves

    if found:
        print(f"::error::the merged manifest names {len(found)} package(s) that exist only in LCARS:")
        for package in sorted(found):
            print(f"    {package}.{{{', '.join(sorted(found[package]))}}}")
        print("A shared module declares an LCARS component, or a manifest string was left behind")
        print("in an application's own namespace when the module holding it was carved out.")
        return False

    print(f"  the manifest names dev.mascwa.sky and none of the {len(markers)} LCARS-only packages")
    return True


def check_dex(apk: str, markers: list[str], known: set[str]) -> bool:
    raw = dex_bytes(apk)
    print(f"  dex: {len(raw)} bytes")

    # ⚠️ `latin-1` and nothing else: it is the one codec that maps every byte 1:1 to a codepoint, so
    # scanning the decoded text is byte-exact for the ASCII patterns here and the dex's binary
    # regions cannot swallow or split a descriptor. A lossy decode would silently drop bytes and
    # every absence below would be worth less than it looks.
    blob = raw.decode("latin-1")

    if POSITIVE_CONTROL not in blob:
        print(f"::error::the positive control {POSITIVE_CONTROL} is NOT in the dex.")
        print("Every other result here is an absence, and an absence proves nothing when the search")
        print("cannot find something that is certainly there. Either the dex is obfuscated (R8 was")
        print("turned on for :sky), or it was extracted wrongly. This gate is not working — fix it")
        print("before trusting a pass.")
        return False
    if SENTINEL in blob:
        print("::error::the sentinel matched — this search cannot tell present from absent.")
        return False

    # ⚠️ The package filter is applied here too even though a dex descriptor ends in `;` and a bare
    # package can never wear one. One rule, both artifacts — a second spelling of it is what this
    # commit exists to remove, and a filter that is a no-op here costs nothing.
    found: dict[str, set[str]] = {}
    for package in markers:
        leaves = not_a_package(package, declared_in(blob, package, **DEX_SYNTAX), known)
        if leaves:
            found[package] = leaves

    if found:
        print(f"::error::the star map's APK carries {len(found)} package(s) that exist only in LCARS:")
        for package in sorted(found):
            print(f"    {package}.{{{', '.join(sorted(found[package]))}}}")
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
        # Derived once and handed to both artifact checks, so they cannot come to disagree about
        # what "LCARS code" means — which is exactly what happened when each had its own rule.
        markers = sorted(app_only_packages())
        if not markers:
            print("::error::no LCARS-only packages were derived — the derivation is broken, not the APK.")
            return 1
        known = all_packages()
        ok = check_manifest(apk, markers, known) and ok
        ok = check_dex(apk, markers, known) and ok
    else:
        print("  (no APK given — the graph check is all that ran)")
    print("standalone" if ok else "NOT standalone")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
