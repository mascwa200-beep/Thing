#!/usr/bin/env python3
"""A function that reads a composition local must be @Composable.

⚠️ **This exists because nothing else in the toolchain can catch it.** "Functions which invoke
@Composable functions must be marked with @Composable" is emitted by the COMPOSE COMPILER PLUGIN, so:

  - the parse-only kotlinc pass does not type-check at all;
  - `android_resolve_check.sh` differences unresolved NAMES, and this is not one;
  - `android_compile_check.sh` runs kotlinc WITHOUT the Compose plugin, so it never runs the check
    that would fire.

The failure it catches is a real one this repo shipped: `overColor` in `HealthBodies.kt` read
`Pulse.colors` — a composition-local read, which is a @Composable invocation — and was declared as a
plain function. Every local gate passed and CI failed on `:app:compileDebugKotlin`.

The rule is deliberately narrow. It looks only for reads that are UNAMBIGUOUSLY composition locals in
this codebase, and only inside files that already import Compose, so an ordinary Kotlin file that
happens to contain the word cannot trip it.

Usage:  tools/compose_local_check.py FILE [FILE...]
        tools/compose_local_check.py --all
"""
import re
import subprocess
import sys
from pathlib import Path

# ⚠️ Every entry here is a property whose getter is annotated @Composable in this tree. `Pulse.colors`
# reads `LocalNightwire.current`; `MaterialTheme.colorScheme` and friends read their own locals. A
# bare `SomeLocal.current` is the general form and is matched separately.
LOCAL_READS = [
    r"\bPulse\.colors\b",
    r"\bPulse\.shapes\b",
    r"\bMaterialTheme\.(colorScheme|typography|shapes)\b",
    r"\bLocal[A-Za-z0-9_]+\.current\b",
]
# ⚠️ `remember`, `collectAsState` and friends are deliberately NOT in that list, and the reason was
# measured rather than assumed. They appear constantly inside NESTED composable lambdas — `main()` in
# the desktop shell opens `application { Window { … remember { … } } }` — and this check attributes a
# whole function body to its declaration, so including them produced a standing false positive on the
# first repo-wide run. A gate with standing noise is one people learn to ignore, which is worse than a
# narrower one that is always right. What remains covers the defect actually observed: a plain
# function reading a composition local.
LOCAL_RE = re.compile("|".join(LOCAL_READS))

# A top-level or member function declaration. Kotlin's `fun` keyword, optionally preceded by
# modifiers on the same line.
FUN_RE = re.compile(r"^(\s*)((?:private |internal |public |protected |inline |suspend )*)fun\s+([A-Za-z0-9_`]+)\s*[(<]")


def brace_span(lines: list[str], start: int) -> tuple[int, int] | None:
    """The line range of the function body beginning at or after [start], or None for an expression
    body with no braces on the declaration line group."""
    depth = 0
    opened = False
    i = start
    while i < len(lines):
        for ch in lines[i]:
            if ch == "{":
                depth += 1
                opened = True
            elif ch == "}":
                depth -= 1
                if opened and depth == 0:
                    return (start, i)
        # An expression body that never opens a brace: stop at the next blank line so a one-liner is
        # still scanned but the rest of the file is not attributed to it.
        if not opened and i > start and lines[i].strip() == "":
            return (start, i)
        i += 1
    return (start, len(lines) - 1)


def check(path: Path) -> list[str]:
    text = path.read_text(encoding="utf-8", errors="replace")
    # Only Compose files. An ordinary Kotlin file cannot have this defect.
    if "androidx.compose" not in text:
        return []
    lines = text.split("\n")
    out = []
    for i, line in enumerate(lines):
        m = FUN_RE.match(line)
        if not m:
            continue
        # Is it already annotated? Look back over the annotation block above the declaration.
        j = i - 1
        annotated = False
        while j >= 0:
            s = lines[j].strip()
            if s.startswith("@"):
                if s.startswith("@Composable"):
                    annotated = True
                    break
                j -= 1
                continue
            if s.endswith("*/") or s.startswith("*") or s.startswith("/*") or s.startswith("//") or s == "":
                j -= 1
                continue
            break
        if annotated:
            continue
        lo, hi = brace_span(lines, i)
        # From the line AFTER the declaration, so a parameter or default value cannot match.
        hit = LOCAL_RE.search("\n".join(lines[lo + 1:hi + 1]))
        if hit:
            out.append(f"{path}:{i + 1}: fun {m.group(3)} reads '{hit.group(0)}' and is not @Composable")
    return out


def main() -> int:
    if len(sys.argv) > 1 and sys.argv[1] == "--all":
        files = subprocess.run(
            ["git", "ls-files", "*.kt"], capture_output=True, text=True, check=True
        ).stdout.split()
    else:
        files = sys.argv[1:]
    if not files:
        print("usage: compose_local_check.py FILE [FILE...] | --all", file=sys.stderr)
        return 2
    problems = []
    for f in files:
        p = Path(f)
        if p.suffix == ".kt" and p.exists():
            problems += check(p)
    for line in problems:
        print(line)
    if problems:
        print(f"\n{len(problems)} function(s) read a composition local without @Composable.")
        return 1
    print("ok    every function that reads a composition local is @Composable")
    return 0


if __name__ == "__main__":
    sys.exit(main())
