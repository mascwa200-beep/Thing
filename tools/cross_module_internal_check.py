#!/usr/bin/env python3
"""
Refuse an `internal` member of a shared core that another module tries to reach.

⚠️ **`internal` in Kotlin is MODULE-scoped, not package-scoped**, and nothing local catches the
mistake: the parse gate does not resolve names, `android_resolve_check.sh` differences *unresolved*
references and this is a *visibility* error, and `:app` unit tests cannot be built in this container
at all. So it surfaces only as a red CI round, several minutes in, on `compileDebugUnitTestKotlin`.

This project has paid for it twice:

  - `Stardate.clockOf` — `internal`, so `:app` could not see it (recorded in CLAUDE.md).
  - `StarNames.properKeys` — written expressly so an `:app` test could walk the bundled star
    catalogue, and marked `internal`, so that test could never compile. CI run 2084.

A third is already anticipated in a comment in `nutrition/.../QuickAddCard.kt`, which notes that
`FoodPortion.trim` "is internal to its own module". That is a developer meeting this rule and
writing it down; this script is the same knowledge, enforced.

The rule it checks is narrow on purpose, and so is what it looks at. Only a QUALIFIED reference —
`Owner.member` — can reach a member of an object or companion from another module, and matching a
bare member name against every other module's source produces overwhelming noise (a first attempt
matched ordinary English words inside KDoc). So the pattern is the qualified form, which has almost
no false-positive surface.

Usage:
    python3 tools/cross_module_internal_check.py          # every shared core against every consumer
    python3 tools/cross_module_internal_check.py --root X # run against a copy of the tree
"""
import collections
import os
import re
import subprocess
import sys

# The modules whose `internal` is a boundary somebody else might try to cross.
PROVIDERS = [
    "core/telemetry/src/main",
    "core/feeds/src/main",
    "core/update/src/main",
    "core/health/src/main",
]

# Everywhere that could hold the offending reference — including each provider's own TEST source,
# which is a different compilation but the SAME module, so `internal` is legal there and it must not
# be reported.
CONSUMERS = [
    "app/src",
    "nutrition/src",
    "desktop/src",
    "core/telemetry/src/main",
    "core/feeds/src",
    "core/update/src",
    "core/health/src",
]

DECL = re.compile(
    r"\s*(?:@\w+\s+)*(?:public\s+)?(?:object|class|data class|enum class|sealed class|interface)"
    r"\s+([A-Za-z_]\w*)"
)
INTERNAL = re.compile(
    r"\s*internal\s+(?:fun|val|var|const val|object|class|data class|enum class)\s+([A-Za-z_]\w*)"
)


def qualified_internals(root, provider):
    """`Owner.member` for every internal member declared inside a top-level type."""
    out = []
    base = os.path.join(root, provider)
    for dirpath, _, files in os.walk(base):
        for fn in sorted(files):
            if not fn.endswith(".kt"):
                continue
            owner, depth = None, 0
            path = os.path.join(dirpath, fn)
            for line in open(path, encoding="utf-8"):
                m = DECL.match(line)
                # Only a TOP-LEVEL declaration owns the namespace a caller would qualify with.
                if m and depth == 0:
                    owner = m.group(1)
                m2 = INTERNAL.match(line)
                if m2 and owner:
                    out.append((f"{owner}.{m2.group(1)}", os.path.relpath(path, root)))
                depth += line.count("{") - line.count("}")
    return out


def main():
    root = "."
    if "--root" in sys.argv:
        root = sys.argv[sys.argv.index("--root") + 1]

    findings = []
    checked = 0
    for provider in PROVIDERS:
        if not os.path.isdir(os.path.join(root, provider)):
            continue
        pairs = qualified_internals(root, provider)
        checked += len(pairs)
        if not pairs:
            continue
        # The provider's own sources are the one place these are legal.
        own_module = provider.split("/src")[0]
        targets = [
            os.path.join(root, c)
            for c in CONSUMERS
            if os.path.isdir(os.path.join(root, c)) and not c.startswith(own_module)
        ]
        if not targets:
            continue
        rx = "|".join(re.escape(p) for p, _ in pairs)
        got = subprocess.run(
            ["grep", "-rn", "--include=*.kt", "-E", rx] + targets,
            capture_output=True, text=True,
        ).stdout
        where = dict(pairs)
        for line in got.splitlines():
            # A KDoc or `//` comment naming the member is somebody DOCUMENTING this rule, which is
            # the opposite of breaking it.
            body = line.split(":", 2)[-1].lstrip()
            if body.startswith("*") or body.startswith("//") or body.startswith("/*"):
                continue
            for pat, decl in where.items():
                if pat in body:
                    findings.append((line.split(":")[0], line, pat, decl))
                    break

    if checked == 0:
        print("no internal members found in any provider — the scan cannot have worked", file=sys.stderr)
        return 2

    if findings:
        print(f"internal members of a shared core reached from another module ({len(findings)}):")
        for _, line, pat, decl in findings:
            print(f"  {pat}  declared internal in {decl}")
            print(f"      {line.strip()[:170]}")
        print("\n`internal` is module-scoped. Make it public, or move the caller into the module.")
        return 1

    print(f"ok    {checked} internal core members, none reached across a module boundary")
    return 0


if __name__ == "__main__":
    sys.exit(main())
