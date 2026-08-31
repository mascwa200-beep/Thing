#!/usr/bin/env python3
"""Check GitHub Actions workflow expressions for the mistakes YAML parsing cannot see.

    tools/workflow_expr_check.py [.github/workflows/*.yml ...]

⚠️ **This exists because two runs were spent on an empty expression inside a COMMENT.** A `#` line
inside a `run:` block is a comment to the shell, not to GitHub: `${{ ... }}` is substituted before
the script exists, so writing one out to explain a rule about it is a syntax error in the workflow.
The failure gives you nothing to read — the run completes in under a second with **zero jobs**, no
log, no failing step, and a bare "failure" — because an invalid workflow never reaches the point of
creating a job.

⚠️ **`yaml.safe_load` passes on it, which is why loading the file is not a gate.** Expressions are
opaque strings to YAML; whether they parse is a separate language GitHub evaluates afterwards.

What this checks, all of them unambiguous errors rather than style:

  - an EMPTY expression, `${{ }}` — the one that cost the two runs
  - an UNCLOSED `${{` with no `}}` after it
  - a stray `}}` with no opening `${{`
  - an expression containing a NEWLINE inside a literal block scalar, where the substitution puts a
    real line break into whatever the script was building — the shape that would truncate a value
    written to $GITHUB_OUTPUT, which is a line-oriented file

It does NOT validate the expression LANGUAGE — an unknown function or a misspelt context still only
fails on GitHub. This catches the delimiter mistakes, which are the ones that produce a run with
nothing in it to diagnose.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

OPEN = "${{"
CLOSE = "}}"


def literal_block_lines(text: str) -> set[int]:
    """Line numbers (1-based) that sit inside a `run: |` literal block.

    ⚠️ Approximate on purpose, and it only has to be right about ONE thing: a newline inside an
    expression is harmless in a folded `>-` scalar (GitHub joins the lines) and harmful in a literal
    `|` one. Anything indented past a `run: |` or `script: |` counts until the indentation drops.
    """
    inside: set[int] = set()
    lines = text.splitlines()
    block_indent = None
    for i, line in enumerate(lines, 1):
        if block_indent is not None:
            if not line.strip():
                inside.add(i)
                continue
            indent = len(line) - len(line.lstrip())
            if indent > block_indent:
                inside.add(i)
                continue
            block_indent = None
        m = re.match(r"^(\s*)(?:-\s+)?(?:run|script):\s*\|", line)
        if m:
            block_indent = len(m.group(1))
    return inside


def check(path: Path) -> list[str]:
    text = path.read_text(encoding="utf-8")
    problems: list[str] = []
    blocks = literal_block_lines(text)

    # Walk the raw text so comments are seen exactly as GitHub sees them.
    pos = 0
    while True:
        start = text.find(OPEN, pos)
        if start < 0:
            break
        end = text.find(CLOSE, start + len(OPEN))
        line = text.count("\n", 0, start) + 1
        if end < 0:
            problems.append(f"{path}:{line}: `${{{{` is never closed")
            break
        body = text[start + len(OPEN):end]
        if not body.strip():
            problems.append(
                f"{path}:{line}: EMPTY expression `${{{{ }}}}` — this is a workflow syntax error, "
                f"and it is one even inside a `#` comment in a `run:` block"
            )
        if "\n" in body and line in blocks:
            problems.append(
                f"{path}:{line}: expression spans lines inside a literal block — the substitution "
                f"puts a real newline into the script"
            )
        pos = end + len(CLOSE)

    # A stray close with nothing opening it.
    for m in re.finditer(re.escape(CLOSE), text):
        before = text[:m.start()]
        if before.count(OPEN) <= before.count(CLOSE):
            line = text.count("\n", 0, m.start()) + 1
            problems.append(f"{path}:{line}: `}}}}` with no `${{{{` opening it")
            break
    return problems


def main(argv: list[str]) -> int:
    paths = [Path(a) for a in argv[1:]]
    if not paths:
        paths = sorted(Path(".github/workflows").glob("*.yml"))
    if not paths:
        print("workflow_expr_check: no workflow files given or found", file=sys.stderr)
        return 2
    problems: list[str] = []
    for p in paths:
        if not p.exists():
            continue
        problems += check(p)
    if problems:
        for p in problems:
            print(p)
        return 1
    print(f"clean: expressions well-formed in {len(paths)} workflow file(s)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
