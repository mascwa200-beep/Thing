#!/usr/bin/env python3
"""Does any package declare the same top-level name twice?

    tools/kotlin_dup_decl_check.py <package-directory> [more...]

⚠️ **THIS EXISTS BECAUSE A CI ROUND WAS SPENT ON EXACTLY THIS AND NO LOCAL GATE COULD SEE IT.**
`TrainingBody.kt` declared `private fun relativeDay(atMs: Long)` in a package where
`HealthBodies.kt` already had an `internal` one. Kotlin's rules are the trap:

  - two `private` top-level declarations in DIFFERENT files do not conflict — `private` at the top
    level is file-scoped, which is why this looks safe;
  - but `private` beside anything more visible IS a conflict, because the wider one is in scope in
    the narrower one's file. The compiler says "Conflicting overloads" and prints the same signature
    twice, which reads like a compiler bug rather than a duplicate.

⚠️ And the two gates that ought to have caught it could not, for reasons worth knowing:

  - the parse-only kotlinc pass does not resolve names at all;
  - `android_resolve_check.sh` DIFFERENCES unresolved names against HEAD, and `relativeDay` already
    resolved at HEAD — the duplicate produces a DIFFERENT message ("Conflicting overloads") whose
    text was masked, and in any case the name itself was never unresolved. That is the documented
    "an identical message at HEAD masks a second one" limit doing exactly what it says.

So this is a lexical check, deliberately: it needs no classpath, no Android SDK and no compiler, and
it answers the one question the compiler answers minutes later.

WHAT IT REPORTS: a top-level `fun` name with the same PARAMETER COUNT, or a top-level `val`/`var`
name, declared in more than one file of a package — unless EVERY declaration is `private`, which is
the one case Kotlin genuinely allows.

WHAT IT DOES NOT DO: overload resolution. Two `fun of(x: Int)` and `fun of(x: String)` in one package
are legal and are reported here, because telling them apart needs a type checker. Arity is the cheap
discriminator that removes nearly all of that noise; the residue is small and worth reading.
"""
import re
import sys
from collections import defaultdict
from pathlib import Path

# A top-level declaration starts at column zero. Anything indented is inside a class or an object
# and is scoped by it, so it cannot collide with another file's top level.
FUN = re.compile(r"^(?P<mods>(?:@\w+\s+)*(?:(?:public|internal|private|inline|suspend|operator|infix|expect|actual)\s+)*)fun\s+(?:<[^>]*>\s+)?(?P<name>[A-Za-z_][A-Za-z0-9_]*)\s*\(")
PROP = re.compile(r"^(?P<mods>(?:@\w+\s+)*(?:(?:public|internal|private|const|lateinit|expect|actual)\s+)*)(?:val|var)\s+(?P<name>[A-Za-z_][A-Za-z0-9_]*)\s*[:=]")


def arity(text: str, open_paren: int) -> int:
    """Count top-level commas between the parentheses, so `f(a: Map<K, V>)` is one parameter."""
    depth = 0
    angle = 0
    count = 0
    seen = False
    i = open_paren
    while i < len(text):
        ch = text[i]
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                return count + 1 if seen else 0
        elif ch == "<":
            angle += 1
        elif ch == ">":
            angle = max(0, angle - 1)
        elif ch == "," and depth == 1 and angle == 0:
            count += 1
        elif depth == 1 and not ch.isspace():
            seen = True
        i += 1
    return -1


def declarations(path: Path):
    """(kind, name, arity, is_private) for every top-level declaration in one file."""
    text = path.read_text(encoding="utf-8", errors="replace")
    out = []
    for line_start in (m.start() for m in re.finditer(r"^", text, re.M)):
        line_end = text.find("\n", line_start)
        line = text[line_start : line_end if line_end != -1 else len(text)]
        m = FUN.match(line)
        if m:
            n = arity(text, line_start + line.index("(", m.end("name") - len(m.group("name"))))
            out.append(("fun", m.group("name"), n, "private" in m.group("mods")))
            continue
        m = PROP.match(line)
        if m:
            out.append(("prop", m.group("name"), 0, "private" in m.group("mods")))
    return out


def check(directory: Path) -> list[str]:
    seen = defaultdict(list)
    for f in sorted(directory.glob("*.kt")):
        for kind, name, n, is_private in declarations(f):
            seen[(kind, name, n)].append((f.name, is_private))
    problems = []
    for (kind, name, n), places in sorted(seen.items()):
        files = {f for f, _ in places}
        if len(files) < 2:
            continue
        # The one case Kotlin allows: every declaration is file-private.
        if all(p for _, p in places):
            continue
        where = ", ".join(sorted(files))
        shape = f"{name}(...{n})" if kind == "fun" else name
        problems.append(f"  {directory}: '{shape}' is declared in {where}")
    return problems


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 64
    found = []
    for arg in sys.argv[1:]:
        d = Path(arg)
        if not d.is_dir():
            print(f"not a directory: {d}", file=sys.stderr)
            return 64
        found += check(d)
    if found:
        print("top-level declarations that collide inside one package:")
        print("\n".join(found))
        print(
            "\n⚠️ Two file-private declarations are fine; anything else in the same package is a\n"
            "'Conflicting overloads' compile error. Arity is the discriminator, so a genuine\n"
            "overload with the same parameter COUNT and different types is reported here too — read\n"
            "it rather than assuming it is noise."
        )
        return 1
    print("no colliding top-level declarations")
    return 0


sys.exit(main())
