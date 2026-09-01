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

⚠️ **Two holes were measured on the day `:core:sky` gained the star catalogue readers, and both are
closed here.** They cost no CI round only because the crossing they hid was noticed by hand.

  1. **The module list was hand-maintained and `:core:sky` was never added**, so a gate that
     reported "ok" had not looked at that module at all. Exactly the shape of the `MirrorDriftTest`
     map that watched 31 of 53 mirrors and the workflow path filter that did not name `data/live`:
     a list beside a set of real modules, where the drift is silent. Both lists are now DERIVED from
     `settings.gradle.kts`, so a module that Gradle builds is a module this checks.
  2. **An `internal companion object` makes every member inside it unreachable without any of them
     carrying the modifier**, and the matcher only ever looked for the modifier. `StarCatalog` had
     exactly that shape, and its `EPOCH_YEAR` is read from two `:app` files.

Both were negative-tested: with the companion put back to `internal`, this reports both crossings;
against the tree as it stands it reports none, so the widening adds no noise.

Usage:
    python3 tools/cross_module_internal_check.py          # every module against every other
    python3 tools/cross_module_internal_check.py --root X # run against a copy of the tree
"""
import os
import re
import subprocess
import sys

DECL = re.compile(
    r"\s*(?:@\w+\s+)*(?:public\s+)?(?:object|class|data class|enum class|sealed class|interface)"
    r"\s+([A-Za-z_]\w*)"
)
INTERNAL = re.compile(
    r"\s*internal\s+(?:fun|val|var|const val|object|class|data class|enum class)\s+([A-Za-z_]\w*)"
)
# ⚠️ An internal companion carries the modifier ONCE, on the block. Everything declared directly
# inside it is unreachable from another module while looking perfectly public on its own line.
INTERNAL_COMPANION = re.compile(r"\s*internal\s+companion\s+object\b")
MEMBER = re.compile(
    r"\s*(?:@\w+\s+)*(?:internal\s+|private\s+|public\s+)?(?:const\s+)?(?:val|var|fun)"
    r"\s+([A-Za-z_]\w*)"
)
INCLUDE = re.compile(r'include\("(:[A-Za-z0-9:_-]+)"\)')


def modules(root):
    """Every Gradle module, derived rather than listed — see hole 1 in the docstring."""
    settings = os.path.join(root, "settings.gradle.kts")
    found = INCLUDE.findall(open(settings, encoding="utf-8").read())
    out = [m.lstrip(":").replace(":", "/") for m in found]
    return [m for m in out if os.path.isdir(os.path.join(root, m, "src"))]


def qualified_internals(root, module):
    """`Owner.member` for everything a caller in another module could not reach."""
    out = []
    base = os.path.join(root, module, "src", "main")
    if not os.path.isdir(base):
        return out
    for dirpath, _, files in os.walk(base):
        for fn in sorted(files):
            if not fn.endswith(".kt"):
                continue
            owner, depth, companion_at = None, 0, None
            path = os.path.join(dirpath, fn)
            for line in open(path, encoding="utf-8"):
                m = DECL.match(line)
                # Only a TOP-LEVEL declaration owns the namespace a caller would qualify with.
                if m and depth == 0:
                    owner = m.group(1)
                m2 = INTERNAL.match(line)
                if m2 and owner:
                    out.append((f"{owner}.{m2.group(1)}", os.path.relpath(path, root)))
                # Inside an internal companion, an ordinary member is just as unreachable.
                if companion_at is not None and depth == companion_at:
                    m3 = MEMBER.match(line)
                    if m3 and owner:
                        out.append((f"{owner}.{m3.group(1)}", os.path.relpath(path, root)))
                if INTERNAL_COMPANION.match(line) and owner:
                    companion_at = depth + line.count("{") - line.count("}")
                depth += line.count("{") - line.count("}")
                if companion_at is not None and depth < companion_at:
                    companion_at = None
    return out


def main():
    root = "."
    if "--root" in sys.argv:
        root = sys.argv[sys.argv.index("--root") + 1]

    mods = modules(root)
    findings = []
    checked = 0
    for module in mods:
        pairs = qualified_internals(root, module)
        checked += len(pairs)
        if not pairs:
            continue
        # ⚠️ The module's OWN sources are the one place these are legal — including its test source,
        # which is a separate compilation but the same module.
        targets = [os.path.join(root, m, "src") for m in mods if m != module]
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
