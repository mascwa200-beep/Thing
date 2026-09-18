#!/usr/bin/env python3
"""A function that declares a return type and never returns.

WHY THIS EXISTS
---------------
⚠️ **This class of defect is invisible to every other local gate here.** The parse pass does not
type-check; `android_resolve_check.sh` differences unresolved NAMES and a missing return is not one;
and the files it happens in are usually the ones `android_compile_check.sh` cannot reach because they
pull in half the application. It cost a CI round the day it was written.

The shape that produces it: a `by lazy { … }` becomes a block-bodied function. A lazy initializer's
last expression IS its value; a block body's is discarded. Nothing about the edit looks wrong.

⚠️ **THE FIRST VERSION OF THIS SCRIPT DID NOT CATCH THE DEFECT IT WAS WRITTEN FOR, and reported a
confident zero across the repository.** It looked for the substring "return" in the function body,
and the body contained the word "returning" — in a COMMENT. Comments and string literals are
stripped before anything is searched, and `return` is matched as a whole word. Negative-tested
against the real defect before being trusted.

Usage:
  tools/kotlin_missing_return.py <file.kt|dir> [more…]

Exits non-zero when it finds one.

⚠️ It is deliberately conservative: expression bodies (`fun f() = …`) are not matched at all, and a
function whose body contains `TODO(` is skipped. What it catches is the one shape that compiles
locally in a reader's head and fails in the compiler.
"""
import re, sys
from pathlib import Path

def strip_noise(s: str) -> str:
    """Remove comments and string literals, keeping length so offsets stay meaningful."""
    out = []
    i, n = 0, len(s)
    while i < n:
        if s.startswith("/*", i):
            j = s.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append(" " * (j - i)); i = j
        elif s.startswith("//", i):
            j = s.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i)); i = j
        elif s.startswith('"""', i):
            j = s.find('"""', i + 3)
            j = n if j < 0 else j + 3
            out.append(" " * (j - i)); i = j
        elif s[i] == '"':
            j = i + 1
            while j < n and s[j] != '"':
                if s[j] == "\\": j += 1
                j += 1
            j = min(j + 1, n)
            out.append(" " * (j - i)); i = j
        else:
            out.append(s[i]); i += 1
    return "".join(out)

FUN = re.compile(r"\n(?:    )*(?:private |internal |public |protected )*fun +(\w+)\s*\([^\n]*\)\s*:\s*([A-Za-z][\w.<>?, ]*?)\s*\{")

def scan(path: Path):
    raw = path.read_text()
    s = strip_noise(raw)
    for m in FUN.finditer(s):
        name, ret = m.group(1), m.group(2).strip()
        if ret == "Unit":
            continue
        b = s.index("{", m.end() - 1)
        depth, j = 0, b
        while j < len(s):
            if s[j] == "{": depth += 1
            elif s[j] == "}":
                depth -= 1
                if depth == 0: break
            j += 1
        body = s[b:j + 1]
        if not re.search(r"\breturn\b", body) and "TODO(" not in body:
            yield f"{path}:{raw[:m.start()].count(chr(10)) + 2} fun {name}(): {ret} never returns"

if __name__ == "__main__":
    hits = 0
    for arg in sys.argv[1:]:
        p = Path(arg)
        files = [p] if p.is_file() else sorted(p.rglob("*.kt"))
        for f in files:
            for line in scan(f):
                print("⚠️", line); hits += 1
    print(f"functions with a declared return type and no return: {hits}")
    sys.exit(1 if hits else 0)
