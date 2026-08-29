#!/usr/bin/env python3
"""Refuse a NUL byte, or any other stray control character, in a source file.

    python3 tools/nul_byte_check.py FILE...

⚠️ **This exists because a NUL byte inside a Kotlin character literal compiles perfectly and is
silently the wrong character.** `DeepSky.kt` was written with `' '` as the "no photometric band"
sentinel; a NUL landed inside those quotes, the compiler accepted `'\\x00'` without a murmur, and the
field came out as the NUL character on every row. Nothing in the toolchain complained. The only tell
was `grep` answering **"binary file matches"** instead of showing the line, which reads as noise.

Two of the three NULs were inside a KDoc, where they do nothing at all — so a file can carry this for
a long time before the one that matters is written.

WHAT IS REFUSED, AND WHY EACH
-----------------------------
  NUL                  the case above; also makes grep, diff and every editor treat the file as binary
  other C0 controls    same class, and none of them can be meant: a real tab or newline is excluded
  a lone carriage return   a CR without a following LF is not a line ending anybody wrote deliberately

TAB AND NEWLINE ARE ALLOWED, obviously. So is every character above U+007F: this repository's sources
are full of ⚠️, é, µ and °, and refusing them would make the check useless.
"""

from __future__ import annotations

import sys
from pathlib import Path

# C0 controls that can never be deliberate in a source file. Tab (0x09), line feed (0x0A) and
# carriage return (0x0D) are handled separately below.
FORBIDDEN = set(range(0x00, 0x09)) | set(range(0x0B, 0x0D)) | set(range(0x0E, 0x20)) | {0x7F}


def check(path: Path) -> list[str]:
    try:
        data = path.read_bytes()
    except OSError as exc:
        return [f"{path}: cannot be read: {exc}"]
    problems: list[str] = []
    line = 1
    for i, byte in enumerate(data):
        if byte == 0x0A:
            line += 1
            continue
        if byte == 0x0D:
            # A carriage return is only a line ending when a line feed follows it.
            if i + 1 >= len(data) or data[i + 1] != 0x0A:
                problems.append(f"{path}:{line}: a lone carriage return (0x0D)")
            continue
        if byte in FORBIDDEN:
            around = data[max(0, i - 40):i + 20]
            problems.append(f"{path}:{line}: control byte 0x{byte:02X} near {around!r}")
    return problems


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__)
        return 2
    problems: list[str] = []
    for name in argv[1:]:
        p = Path(name)
        if p.is_file():
            problems.extend(check(p))
    if problems:
        # Capped: one stray byte in a large file can produce thousands of lines and the first few
        # say everything the rest would.
        for line in problems[:20]:
            print(line, file=sys.stderr)
        if len(problems) > 20:
            print(f"... and {len(problems) - 20} more", file=sys.stderr)
        return 1
    print(f"clean: no stray control bytes in {len(argv) - 1} file(s)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
